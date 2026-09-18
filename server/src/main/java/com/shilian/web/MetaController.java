package com.shilian.web;

import com.shilian.config.ShilianProperties;
import com.shilian.domain.ContentType;
import com.shilian.domain.Domain;
import com.shilian.domain.Purpose;
import com.shilian.domain.port.EmbeddingPort;
import com.shilian.infrastructure.resilience.AnalysisGate;
import com.shilian.infrastructure.resilience.ResilientLlm;
import com.shilian.web.dto.MetaResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 字典与健康检查。
 *
 * <p>把枚举的 code/label 从服务端吐出来，是为了让中文名只有一份。
 * 之前 {@code biz} 的标签在提示词里是「商业 · 资讯」、在枚举里是「商业 · 行业」，
 * 两边不一致直接把分类判错。前端现在可以直接用这里的 label，
 * 不必再维护第二份中文名。
 */
@RestController
@RequestMapping("/api")
public class MetaController {

    private final ShilianProperties props;
    private final EmbeddingPort embedding;
    private final ResilientLlm llm;
    private final AnalysisGate gate;

    public MetaController(ShilianProperties props, EmbeddingPort embedding,
                          ResilientLlm llm, AnalysisGate gate) {
        this.props = props;
        this.embedding = embedding;
        this.llm = llm;
        this.gate = gate;
    }

    @GetMapping("/meta")
    public MetaResponse meta() {
        List<MetaResponse.Option> domains = Arrays.stream(Domain.values())
                .map(d -> new MetaResponse.Option(d.code(), d.label()))
                .toList();
        List<MetaResponse.Option> purposes = Arrays.stream(Purpose.values())
                .map(p -> new MetaResponse.Option(p.code(), p.label()))
                .toList();
        List<String> contentTypes = Arrays.stream(ContentType.values())
                .map(ContentType::label)
                .toList();
        return new MetaResponse(domains, purposes, contentTypes,
                props.deepseek().configured(), props.deepseek().model(), semanticInfo());
    }

    /**
     * 「AI 能用」和「语义搜索能用」是两件事。
     *
     * <p>分析用 DeepSeek，而 DeepSeek 不提供 embeddings 接口，
     * 所以语义搜索得配一套独立的向量服务。这里分开报，
     * 前端才能把「分析能用、语义搜索缺个 Key」这个状态如实显示出来。
     */
    private MetaResponse.Semantic semanticInfo() {
        boolean ok = embedding.available();
        return new MetaResponse.Semantic(
                ok,
                ok ? null : embedding.unavailableReason(),
                ok ? props.embedding().model() : null,
                props.embedding().minScore());
    }

    /**
     * 用于启动自检：AI Key 没配的话，前端应该直接提示，而不是等分析失败。
     *
     * <p><b>为什么还要报熔断和并发。</b>
     * 「Key 配了」只说明<b>理论上</b>能用。真正决定「现在提交一个网址会不会成功」
     * 的是另外两件事：熔断器是不是开着（上游刚挂过，还在冷却），
     * 以及分析并发是不是满了（同时有几个人在跑）。
     * 这两样都是暂时状态，只报配置等于把「现在用不了」说成「一切正常」。
     *
     * <p>所以 {@code status} 在熔断打开时降级为 {@code degraded}——
     * 前端可以据此把提示从「没配 Key」换成「等一会儿再试」，
     * 那是两种完全不同的下一步动作。
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        boolean circuitOpen = llm.isCircuitOpen();
        return Map.of(
                "status", circuitOpen ? "degraded" : "ok",
                "aiConfigured", props.deepseek().configured(),
                "model", props.deepseek().model(),
                "semanticAvailable", embedding.available(),
                "circuitOpen", circuitOpen,
                "analysisSlots", gate.available() + "/" + gate.capacity());
    }
}

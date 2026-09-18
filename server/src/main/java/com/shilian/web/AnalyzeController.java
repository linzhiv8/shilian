package com.shilian.web;

import com.shilian.analyze.AiUsageRecorder;
import com.shilian.analyze.AnalyzeService;
import com.shilian.domain.AnalyzeOutcome;
import com.shilian.infrastructure.resilience.AnalysisGate;
import com.shilian.web.dto.AnalyzeRequest;
import com.shilian.web.dto.AnalyzeResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分析接口。
 *
 * <p>刻意做成「分析」和「保存」两个接口，而不是一个接口直接入库。
 * 因为 AI 的判断天然有偏差，直接入库等于把不确定性留给用户事后发现并修正；
 * 而让他存之前扫一眼、改一个下拉框，成本几乎为零。
 */
@RestController
@RequestMapping("/api")
public class AnalyzeController {

    private final AnalyzeService analyzeService;
    private final DraftStore draftStore;
    private final AnalysisGate gate;
    private final AiUsageRecorder usage;

    public AnalyzeController(AnalyzeService analyzeService, DraftStore draftStore,
                             AnalysisGate gate, AiUsageRecorder usage) {
        this.analyzeService = analyzeService;
        this.draftStore = draftStore;
        this.gate = gate;
        this.usage = usage;
    }

    /**
     * 名额必须在 finally 里还回去。
     *
     * <p>分析会抛异常（网址打不开、AI 失败、熔断），如果名额只在正常路径释放，
     * 失败几次之后闸门就永久占满，之后每个请求都是 429——
     * 表现为「用着用着突然一直说忙，重启才好」，极难往回查。
     */
    @PostMapping("/analyze")
    public AnalyzeResponse analyze(@Valid @RequestBody AnalyzeRequest request) {
        gate.acquire();
        try {
            AnalyzeOutcome outcome = analyzeService.analyze(request.url(), request.text());
            /*
             * R-15：这里也要记账，哪怕这次分析最后没被保存。
             *
             * 「分析完看一眼，觉得不对，关掉重来」是最常见的用法，也是最容易失控的
             * 烧钱路径——它不落库，所以过去在账上完全看不见。只记 save() 的话，
             * 账单显示的是「成功存下来的那些花了多少钱」，而真实花销可能是它的几倍。
             *
             * link_id 传 null：这一刻还没有记录，硬造一个 id 反而会让账目指向不存在的东西。
             */
            usage.record(null, outcome.draft());
            String draftId = draftStore.put(outcome);
            return new AnalyzeResponse(draftId, outcome.draft());
        } catch (AnalyzeService.AnalysisFailedException e) {
            /*
             * 失败也要记，而且这是最该留痕的一类。
             * 失败往往成串出现（同一个网址反复重试、AI 抽风），
             * 不记的话账单上看到的永远是「风平浪静」。
             */
            usage.record(null, e);
            throw e;
        } finally {
            gate.release();
        }
    }
}

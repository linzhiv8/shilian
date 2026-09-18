package com.shilian.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shilian.config.ShilianProperties;
import com.shilian.domain.port.EmbeddingPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 向量服务客户端。走 OpenAI 的 {@code POST /embeddings} 协议。
 *
 * <p>选这个协议不是为了 OpenAI，而是因为它是事实标准：硅基流动、智谱、DashScope、
 * Ollama、LM Studio、vLLM 都能直接对上。换服务商只改配置里的 base-url 和 model，
 * 不动一行代码。
 *
 * <p><b>为什么不用 DeepSeek。</b> 它只提供 chat completions，没有 embeddings 接口。
 * 这件事很反直觉（都同一个平台了），但确实是拦路虎，所以写在这里免得下次再查一遍。
 */
@Service
public class EmbeddingClient implements EmbeddingPort {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    private final ShilianProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public EmbeddingClient(ShilianProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 没配置向量服务时抛 {@link EmbeddingPort.NotConfiguredException}。
     *
     * <p>异常类型定义在 port 而不是实现类里：它是「业务对这项能力的约定」的一部分
     * ——调用方要能 catch 它，而调用方不该依赖具体实现类。
     */
    @Override
    public boolean available() {
        return props.embedding().configured();
    }

    @Override
    public String unavailableReason() {
        return props.embedding().reason();
    }

    /**
     * 一次算多条。顺序与入参严格对应。
     *
     * <p>批量是必须的：补齐历史记录时，几十条一条一发就是几十个来回，
     * 每个来回都要 TLS 握手。一次发一批，快一个数量级。
     */
    @Override
    public List<float[]> embed(List<String> texts) throws IOException, InterruptedException {
        ShilianProperties.Embedding cfg = props.embedding();
        if (!cfg.configured()) {
            throw new EmbeddingPort.NotConfiguredException(cfg.reason());
        }
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", cfg.model());
        ArrayNode input = payload.putArray("input");
        texts.forEach(input::add);
        /*
         * 刻意不发 encoding_format。默认就是 float 数组，而这个参数
         * 不是所有兼容实现都认——发了反而可能被 400 掉。
         */

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(cfg.endpoint()))
                .timeout(Duration.ofMillis(cfg.timeoutMs()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + cfg.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (resp.statusCode() >= 400) {
            // 不要把 request 打出来，Authorization 头里有 key
            String body = resp.body() == null ? "" : resp.body();
            throw new IOException("向量服务返回 HTTP " + resp.statusCode() + " — "
                    + body.substring(0, Math.min(300, body.length())));
        }

        return parse(resp.body(), texts.size());
    }

    /**
     * 解析响应。<b>按 {@code index} 字段排回原位</b>。
     *
     * <p>OpenAI 的文档说 data 与 input 同序，但这是「实现细节」而不是协议保证，
     * 已经有兼容实现返回乱序或按并发完成顺序返回了。一旦顺序错了，
     * 向量会被贴到别人的记录上——搜索结果的错法极其诡异（搜 A 出来 B），
     * 而且不报任何错。所以宁可多排一次。
     */
    private List<float[]> parse(String body, int expected) throws IOException {
        JsonNode root = mapper.readTree(body);
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            throw new IOException("向量服务的响应里没有 data 数组，原始响应前 300 字："
                    + body.substring(0, Math.min(300, body.length())));
        }
        /*
         * 刻意不单独判「data 是空的」。空数组也是「条数对不上」的一种，
         * 用同一个分支报出去信息更准确——曾经写成两条，结果空数组命中了
         * 「没有返回 data 数组」那句，把「服务端少给了一条」说成了「字段缺失」，
         * 排查时被带偏过一次。
         */

        float[][] out = new float[expected][];
        int filled = 0;
        for (int i = 0; i < data.size(); i++) {
            JsonNode item = data.get(i);
            // 有 index 就按 index 放，没有就按出现顺序放
            int slot = item.path("index").asInt(i);
            if (slot < 0 || slot >= expected) {
                throw new IOException("向量服务返回的 index 越界：" + slot + "（本次发了 " + expected + " 条）");
            }
            JsonNode vec = item.path("embedding");
            if (!vec.isArray() || vec.isEmpty()) {
                throw new IOException("第 " + slot + " 条没有 embedding");
            }
            float[] arr = new float[vec.size()];
            for (int j = 0; j < vec.size(); j++) {
                arr[j] = (float) vec.get(j).asDouble();
            }
            out[slot] = arr;
            filled++;
        }

        if (filled != expected) {
            throw new IOException("向量服务返回的条数对不上：发了 " + expected + " 条，回来 " + filled + " 条。"
                    + "少给会让向量被贴到别人的记录上，所以宁可整个失败");
        }

        List<float[]> list = new ArrayList<>(Arrays.asList(out));
        /*
         * 同一次请求内所有向量的维度必须一致。不一致说明服务端在按内容长度
         * 切换模型之类，这时候算余弦会直接抛越界或者算出垃圾——
         * 早点拦住，报一句能看懂的话。
         */
        int dim = list.get(0).length;
        for (int i = 1; i < list.size(); i++) {
            if (list.get(i).length != dim) {
                throw new IOException("同一次请求返回的向量维度不一致：第 0 条是 " + dim
                        + " 维，第 " + i + " 条是 " + list.get(i).length + " 维");
            }
        }
        return list;
    }

    /** 记一次用量。向量服务多数按 token 计费，值得和对话调用一样可见。 */
    public void logUsage(String scene, int count, JsonNode usage) {
        if (usage == null || usage.isMissingNode()) {
            log.debug("向量调用 {} 条（服务端未返回 usage）", count);
            return;
        }
        log.debug("向量调用 scene={} 条数={} tokens={}", scene, count,
                usage.path("total_tokens").asInt(0));
    }
}

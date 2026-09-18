package com.shilian.analyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.JacksonException;
import com.shilian.config.ShilianProperties;
import com.shilian.domain.port.ChatMessage;
import com.shilian.domain.port.LlmException;
import com.shilian.domain.port.LlmPort;
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
import java.util.List;

/**
 * DeepSeek 客户端。走 OpenAI 兼容协议。
 *
 * <p>用 JDK 自带的 {@link HttpClient} 而不是 WebClient：只是发一个 POST，
 * 不需要响应式栈，也不想为此多引一个依赖。
 *
 * <p>用 {@code response_format: json_object} 让模型直接吐 JSON。
 * 但<b>不能因此就不做解析兜底</b>——这个参数只保证「是合法 JSON」，
 * 不保证「只有一个 JSON 对象、没有围栏、字段齐全」，实测仍会偶尔带 ``` 围栏。
 */
@Service
public class DeepSeekClient implements LlmPort {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

    private final ShilianProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public DeepSeekClient(ShilianProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(10_000L, props.deepseek().timeoutMs())))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 发一次对话请求。
     *
     * <p>messages 是「完整历史」：重试时要把上一次的失败输出和修正指令一起带上，
     * 模型才知道自己错在哪。这也是为什么这个方法接收整个列表而不是单条用户消息。
     */
    @Override
    public LlmPort.ChatResult chat(List<ChatMessage> messages) throws IOException, InterruptedException {
        ShilianProperties.Deepseek cfg = props.deepseek();
        if (!cfg.configured()) {
            throw new IllegalStateException(
                    "没有配置 DeepSeek API Key。请在 server/.env.properties 里写 DEEPSEEK_API_KEY=sk-xxx");
        }

        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", cfg.model());
        ArrayNode arr = payload.putArray("messages");
        for (ChatMessage m : messages) {
            ObjectNode o = arr.addObject();
            o.put("role", m.role());
            o.put("content", m.content());
        }
        payload.putObject("response_format").put("type", "json_object");
        payload.put("temperature", cfg.temperature());
        payload.put("max_tokens", cfg.maxTokens());

        String base = cfg.baseUrl().replaceAll("/+$", "");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + "/chat/completions"))
                .timeout(Duration.ofMillis(cfg.timeoutMs()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + cfg.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // 连不上、超时、TLS 握手失败：请求根本没到服务端。
            // 标成 NETWORK 而不是笼统的 IOException，重试逻辑才知道「这种值得再试一次」。
            throw new LlmException("连不上 AI 服务：" + e.getMessage(), LlmException.NETWORK, e);
        }

        if (resp.statusCode() >= 400) {
            // 注意：不要把 request 打出来，Authorization 头里有 key
            String body = resp.body() == null ? "" : resp.body();
            throw new LlmException("AI 服务返回 HTTP " + resp.statusCode() + " — "
                    + body.substring(0, Math.min(300, body.length())), resp.statusCode());
        }

        JsonNode root;
        try {
            root = mapper.readTree(resp.body());
        } catch (JacksonException e) {
            throw new LlmException("AI 服务返回的不是合法 JSON", LlmException.BAD_RESPONSE, e);
        }
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        JsonNode usage = root.path("usage");
        int prompt = usage.path("prompt_tokens").asInt(0);
        int completion = usage.path("completion_tokens").asInt(0);

        if (content.isBlank()) {
            throw new LlmException("AI 服务返回了空内容", LlmException.BAD_RESPONSE);
        }
        return new LlmPort.ChatResult(content, prompt, completion);
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    /**
     * 从可能带围栏或前后杂字的文本里抠出 JSON 对象。
     * 三级兜底：直接解析 → 去围栏 → 截取第一个 { 到最后一个 }。
     */
    @Override
    public JsonNode parseJson(String raw) {
        String t = raw.trim();
        try {
            return mapper.readTree(t);
        } catch (Exception ignored) {
            // 继续下一级
        }
        String noFence = t.replaceFirst("(?i)^```(?:json)?", "").replaceFirst("```$", "").trim();
        try {
            return mapper.readTree(noFence);
        } catch (Exception ignored) {
            // 继续下一级
        }
        int a = noFence.indexOf('{');
        int b = noFence.lastIndexOf('}');
        if (a >= 0 && b > a) {
            try {
                return mapper.readTree(noFence.substring(a, b + 1));
            } catch (Exception e) {
                log.warn("JSON 解析失败，原始内容前 200 字：{}", raw.substring(0, Math.min(200, raw.length())));
            }
        }
        throw new IllegalArgumentException("无法解析为 JSON");
    }
}

package com.shilian.domain.port;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.List;

/**
 * 对话模型的能力端口。
 *
 * <p>业务只依赖这个接口，不认识 DeepSeek，也不认识 OpenAI。换厂商、换模型，
 * 加一个实现类改一下配置即可，{@code AnalyzeService} 一个字都不用动。
 *
 * <p><b>为什么 {@code parseJson} 也在这里。</b>
 * 「模型会返回什么脏东西」本身就是模型的行为特征——带 {@code ```} 围栏、
 * 前后夹带解释、偶尔干脆不是合法 JSON。由实现来适配自己的输出特征，
 * 比让调用方猜要合理。
 *
 * <p><b>关于 {@code JsonNode}。</b>返回值仍是 Jackson 类型，因为它要直接进
 * {@code ValidationService}。彻底去掉 Jackson 依赖要连带改校验层，
 * 留到阶段 3 和数据隔离一起做——现在先让「能力边界」可替换，这是收益最大的一步。
 */
public interface LlmPort {

    record ChatResult(String content, int promptTokens, int completionTokens) {}

    /**
     * 发一次对话请求。
     *
     * <p>messages 是完整历史：重试时要带上一次次的失败输出和修正指令，
     * 模型才知道自己错在哪。
     */
    ChatResult chat(List<ChatMessage> messages) throws IOException, InterruptedException;

    /** 从可能带围栏或前后杂字的输出里抠出 JSON 对象。 */
    JsonNode parseJson(String raw);
}

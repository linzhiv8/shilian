package com.shilian.domain.port;

/**
 * 一条对话消息。
 *
 * <p><b>为什么不用 Jackson 的 ObjectNode。</b>
 * port 的定义是「业务对外部能力的期望」，它应该只用 Java 基本类型描述清楚。
 * 用 ObjectNode 等于让领域层知道「这个能力是走 JSON 的」——
 * 那是实现细节，不是业务关心的事。顺带的好处是测试用的 Fake 写起来也简单。
 */
public record ChatMessage(String role, String content) {

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }
}

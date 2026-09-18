package com.shilian.domain.port;

import java.io.IOException;

/**
 * 调用对话模型失败，附带失败的类型信息。
 *
 * <p><b>为什么继承 IOException。</b>调用方（{@code AnalyzeService}、
 * {@code WeeklyDigestService}）现在 catch 的就是 IOException，它们其实什么都处理不了、
 * 只是转成一句人话。保持兼容，这次改动就不用碰它们。
 *
 * <p><b>关键是带上 statusCode。</b>「服务暂时不可用」和「我传的参数不对」
 * 在原来都只是一个 IOException，而对重试来说完全是两回事。
 * 以前只能靠解析异常消息里的 {@code "HTTP 429"} 来猜——那种写法一改措辞就失效，
 * 而且失败得很安静：要么该重试的没重试，要么不该重试的白白重试到超时。
 */
public class LlmException extends IOException {

    /** 请求根本没到服务端：连不上、超时、TLS 失败。 */
    public static final int NETWORK = -1;

    /** 服务端响应了，但内容是空的或格式不对——不是网络问题，重试通常也没用。 */
    public static final int BAD_RESPONSE = 0;

    private final int statusCode;

    public LlmException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public LlmException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }

    /**
     * 值不值得重试。
     *
     * <ul>
     *   <li>网络层失败（{@link #NETWORK}）——值得，可能只是抖了一下。</li>
     *   <li>429 限流——值得，但必须等一会儿再来，立刻重试只会再被拒。</li>
     *   <li>5xx——值得，是服务端的问题。</li>
     *   <li>其余 4xx——<b>不值得</b>。参数错了、Key 无效，重试一万次结果一样，
     *       只会浪费用户的时间和他的额度。</li>
     * </ul>
     */
    public boolean retryable() {
        return statusCode == NETWORK || statusCode == 429 || statusCode >= 500;
    }
}

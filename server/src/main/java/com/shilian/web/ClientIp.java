package com.shilian.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 取客户端 IP 的唯一实现。
 *
 * <p><b>为什么抽出来。</b>审计日志要记来源 IP，而取法必须和限流用的那套完全一致
 * ——两处各写一遍的话，迟早出现「审计里记的是 nginx 的地址、限流用的是真实 IP」
 * 这种对不上的情况，而它不报错，只是让审计里的 IP 列变得没法用。
 *
 * <p><b>为什么这里直接读 {@code getRemoteAddr()}，以及如何保证它是对的</b>：
 * 完整理由写在 {@code AuthController.clientIp} 的注释里，这里不复述。
 * 一句话版本：{@code application.yml} 里的
 * {@code server.forward-headers-strategy: native} 让 Tomcat 的
 * {@code RemoteIpValve} 在更下层把 {@code getRemoteAddr()} 重写成真实客户端地址，
 * 所以这一行看起来是「直接读对端地址」，实际读到的是 nginx 追加的真实来源。
 *
 * <p><b>必须是 {@code native} 不是 {@code framework}</b>：nginx 用的是追加语义的
 * {@code $proxy_add_x_forwarded_for}，{@code framework} 取最左值 = 客户端可伪造。
 */
public final class ClientIp {

    private ClientIp() {
    }

    /**
     * @param request 可以为 null（比如从非 Web 上下文调用），返回 {@code "unknown"}
     */
    public static String of(HttpServletRequest request) {
        return request == null ? "unknown" : request.getRemoteAddr();
    }
}

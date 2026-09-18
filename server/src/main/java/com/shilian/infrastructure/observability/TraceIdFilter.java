package com.shilian.infrastructure.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 给每个请求发一个 traceId，并记一条访问日志。
 *
 * <p><b>为什么需要它。</b>
 * 没有 traceId 的时候，日志是一条条孤立的文本。
 * 同一个请求打出的「抓取失败」和「调用 AI 失败」在日志里隔着几十行无关输出，
 * 你没法确定它们是不是同一次操作——而这恰恰是排查时最需要知道的第一件事。
 *
 * <p><b>为什么必须在 Spring Security 之前。</b>
 * 这个过滤器通过 {@code FilterRegistrationBean} 注册在 servlet 容器层、
 * 且优先级最高，所以它在安全过滤器链之前执行。
 * 放在链里（比如做成 {@code @Component}）的话，被 401 拦掉的请求
 * 根本走不到它——而「为什么登录失败」正是最需要 traceId 的场景。
 *
 * <p><b>traceId 会写进响应头。</b>
 * 用户在浏览器 network 面板里就能看到这一次的 id，
 * 拿着它去日志里一搜就能把这次请求的所有日志捞出来。
 *
 * <p><b>外部传来的 traceId 不能直接采信。</b>
 * 如果不校验就写进 MDC，攻击者可以塞一个带换行的字符串
 * 伪造出额外的日志行（日志注入）。所以只接受字母数字和短横线，
 * 其余一律丢弃重新生成。
 */
public class TraceIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);

    public static final String HEADER = "X-Trace-Id";
    private static final String MDC_KEY = "traceId";
    private static final int MAX_INCOMING = 64;

    /**
     * 这些路径不记访问日志。
     *
     * <p>健康检查会被前端反复调用，静态资源每次刷新都是一大串。
     * 它们淹没有效信息，而排查时从来不看它们。
     */
    private static final String[] SILENT = {"/api/health", "/assets/", "/favicon"};

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = sanitize(req.getHeader(HEADER));
        if (traceId == null) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        }

        MDC.put(MDC_KEY, traceId);
        res.setHeader(HEADER, traceId);

        long startedAt = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            long ms = (System.nanoTime() - startedAt) / 1_000_000;
            if (!isSilent(req.getRequestURI())) {
                log.info("{} {} → {} · {} ms", req.getMethod(), req.getRequestURI(),
                        res.getStatus(), ms);
            }
            // 线程会被复用，不清掉的话下一个请求会顶着上一个的 traceId
            MDC.remove(MDC_KEY);
        }
    }

    private static String sanitize(String incoming) {
        if (incoming == null || incoming.isBlank() || incoming.length() > MAX_INCOMING) {
            return null;
        }
        for (int i = 0; i < incoming.length(); i++) {
            char c = incoming.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) {
                return null;
            }
        }
        return incoming;
    }

    private static boolean isSilent(String uri) {
        if (uri == null) {
            return true;
        }
        for (String p : SILENT) {
            if (uri.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}

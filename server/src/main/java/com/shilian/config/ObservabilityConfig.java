package com.shilian.config;

import com.shilian.infrastructure.observability.TraceIdFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 可观测性的装配。
 *
 * <p><b>用 {@code FilterRegistrationBean} 显式注册，而不是给过滤器加 {@code @Component}。</b>
 *
 * <p>加了 {@code @Component} 的话，Spring Security 会把所有 {@code Filter} 类型的 bean
 * 都收进它自己的过滤器链，而且排在链的最后——于是被 401 拦掉的请求
 * 压根走不到 traceId 这一步。而「登录为什么失败」恰恰是最需要 traceId 的场景。
 *
 * <p>通过 {@code FilterRegistrationBean} 注册在 servlet 容器层、
 * 并且把 order 设成最高优先级，它就在安全链之前了——任何请求，
 * 哪怕最后被判定无权访问，也都有一个可以追踪的 id。
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> reg = new FilterRegistrationBean<>(new TraceIdFilter());
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.addUrlPatterns("/*");
        return reg;
    }
}

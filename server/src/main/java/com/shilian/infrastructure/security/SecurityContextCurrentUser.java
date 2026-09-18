package com.shilian.infrastructure.security;

import com.shilian.domain.AuthenticationRequiredException;
import com.shilian.domain.port.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 从 Spring Security 的会话里取当前用户。
 *
 * <p><b>只认 {@link ShilianPrincipal} 这一种主体。</b>
 * 匿名请求的主体是个字符串 {@code "anonymousUser"}，
 * 匹配不上就返回空——这是「认不出来就当没登录」，
 * 比「认不出来就放行」安全得多。
 *
 * <p><b>没有异步，所以 ThreadLocal 是安全的。</b>
 * SecurityContext 默认按线程存储，一旦哪天引入
 * {@code @Async} 或线程池，这里会静默取到空，
 * 表现为「后台任务里存的数据没有归属」。
 * 那时需要显式把 SecurityContext 传进新线程，或改走
 * {@code DelegatingSecurityContextExecutorService}。
 */
@Component
public class SecurityContextCurrentUser implements CurrentUser {

    @Override
    public String id() {
        return maybeId().orElseThrow(AuthenticationRequiredException::new);
    }

    @Override
    public Optional<String> maybeId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return Optional.empty();
        }
        if (auth.getPrincipal() instanceof ShilianPrincipal p && p.userId() != null) {
            return Optional.of(p.userId());
        }
        return Optional.empty();
    }
}

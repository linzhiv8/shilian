package com.shilian.infrastructure.security;

import com.shilian.domain.user.User;
import com.shilian.repo.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 账号被停用之后，把还活着的会话踢掉。
 *
 * <p><b>为什么非得有这个过滤器——光在登录时拦是不够的。</b>
 * {@code app_user.status} 和 {@code User.isDisabled()} 早就写在代码里，
 * 但只在登录那一刻校验的话，已经登录的人会话没过期就继续能用。
 * 而「禁用」这个动作的语义是「让他从现在起不能再用」，
 * 不是「让他下次登录时进不来」。后者等于禁用要等会话过期才生效，
 * 而会话有多长没人知道——这个口子大到等于没禁用。
 *
 * <p><b>为什么每个请求查一次库，不做缓存。</b>
 * 缓存（哪怕是几秒的）都会让禁用延迟生效，而那正是这条需求要避免的。
 * 这里的用户规模是十人以内，一次主键查询的开销可以忽略；
 * 真到了要省这点开销的规模，正确的做法是维护一份「已停用 id 集合」并主动失效，
 * 而不是加 TTL——TTL 就是把延迟重新请回来。
 *
 * <p><b>为什么要先清会话再回 401。</b>
 * 只回 401 的话，前端拿到 401 会跳登录页，但服务端的会话还在，
 * 而这个会话里的用户是「已停用」的——留着它没有任何用途，
 * 只是多占一份内存。更实际的是：不清的话，用户如果手动改回地址栏，
 * 在某些接口上仍可能拿到旧会话里的身份。
 */
@Component
public class DisabledUserFilter extends OncePerRequestFilter {

    private final UserRepository users;

    public DisabledUserFilter(UserRepository users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        /*
         * 只对「已认证且是本站主体」的请求生效。
         *
         * 匿名请求（登录、注册、健康检查）必须放行——否则整个产品没人能登录。
         * 这个判断也顺带保证了本过滤器放在授权之后是安全的：
         * 走到这里还没被拦的请求，要么是放开的公开接口（此时 auth 为匿名，跳过），
         * 要么已通过登录校验。
         */
        if (auth == null || !auth.isAuthenticated()
                || !(auth.getPrincipal() instanceof ShilianPrincipal principal)) {
            chain.doFilter(request, response);
            return;
        }

        Optional<User> found = users.findById(principal.userId());
        if (found.isEmpty() || !found.get().isDisabled()) {
            chain.doFilter(request, response);
            return;
        }

        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"这个账号已被停用，有问题请联系管理员\"}");
    }
}

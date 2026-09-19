package com.shilian.config;

import com.shilian.domain.port.Clock;
import com.shilian.domain.user.User;
import com.shilian.infrastructure.security.DisabledUserFilter;
import com.shilian.infrastructure.security.ShilianPrincipal;
import com.shilian.repo.UserRepository;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;

/**
 * 安全配置。
 *
 * <p><b>用 Session 而不是 JWT。</b>
 * 这是单体部署、有服务端会话需求的普通 Web 应用。Session 的好处是
 * 「服务端能让它立刻失效」——改密码后要让其他设备下线，
 * 用 JWT 就得额外维护一份黑名单，反而更复杂。
 * JWT 真正的优势在无状态横向扩展，这里用不上。
 *
 * <p><b>CSRF 目前是关掉的，这是有意的欠账，不是疏忽。</b>
 * 关掉的直接原因：前端现在完全没有取 XSRF token 并回传的逻辑，
 * 一旦开启，所有 POST 会立刻全部 403，等于把产品打瘫。
 * 缓解：会话 Cookie 走 {@code SameSite=Lax}，现代浏览器跨站 POST 不带它，
 * 已挡掉主要攻击面。彻底修好要前后端一起改（尚未做）。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * 关掉 Spring Boot 对这个过滤器的自动注册。
     *
     * <p><b>为什么要关。</b>
     * Spring Boot 会把每一个 {@code Filter} 类型的 bean 再注册到 Servlet 容器里一次。
     * 于是它会被跑两遍：一次在 Spring Security 的链里（我们要的位置，
     * 在 {@code SecurityContextHolderFilter} 之后），一次在所有链之外。
     *
     * <p>跑两遍在这里不会出错——{@code OncePerRequestFilter} 会按一个请求属性
     * 跳过第二次。但那是靠一个隐式的机制兜着：哪天有人把它换成
     * 普通的 {@code Filter} 实现，「每请求一次查库」就会静默变成两次。
     * 显式关掉，让「它只在安全链里」这件事写在配置里而不是靠巧合。
     */
    @Bean
    public FilterRegistrationBean<DisabledUserFilter> disabledUserFilterRegistration(
            DisabledUserFilter filter) {
        FilterRegistrationBean<DisabledUserFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // cost 12：OWASP 当前建议的下限。登录接口慢一点对防守方有利——
        // bcrypt 每加 1 个 cost，撞库成本就翻一倍。
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public UserDetailsService userDetailsService(UserRepository repo, Clock clock) {
        return identifier -> {
            User u = repo.findByUsernameOrEmail(identifier)
                    .orElseThrow(() -> new UsernameNotFoundException("用户不存在"));
            // 带上 user.id：数据隔离按 id 走，不按用户名（用户名能改）
            return new ShilianPrincipal(u, clock.now());
        };
    }

    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService uds, PasswordEncoder enc) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(uds);
        provider.setPasswordEncoder(enc);
        return new ProviderManager(provider);
    }

    /**
     * @param disabledUserFilter 每个已认证请求查一次账号状态。
     *                           必须挂在 {@code SecurityContextHolderFilter}
     *                           <b>之后</b>——会话里的主体是在那个过滤器里被装载的，
     *                           挂早了取到的主体是空的，这个过滤器会变成永久的空转。
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           DisabledUserFilter disabledUserFilter) throws Exception {
        http
                .addFilterAfter(disabledUserFilter, SecurityContextHolderFilter.class)
                .csrf(csrf -> csrf.disable()) // 见类注释：阶段 5 配合前端一起开
                .authorizeHttpRequests(a -> a
                        /*
                         * 只放开注册和登录这两个，不是整个 /api/auth/**。
                         *
                         * 整段放开的话，/me 和 /password 也能匿名访问：
                         * 虽然它们内部会因为拿不到用户 id 而报 401，
                         * 但「靠实现细节保证安全」不如「在入口就写明」——
                         * 哪天有人在 /api/auth/ 下加一个不查用户的接口，
                         * 这个口子就悄悄开在那了。
                         */
                        /*
                         * forgot / reset 也必须匿名可访问——
                         * 走这两个接口的人**恰恰是登不进去的那个人**，
                         * 要登录才能找回密码是个死循环。
                         *
                         * 而 verify/send 和 verify/confirm 不在这里：
                         * 它们都要求已登录（confirm 还要令牌属于本人，
                         * 见 EmailController 上的注释），所以走下面的 authenticated()。
                         */
                        .requestMatchers("/api/auth/register", "/api/auth/login",
                                "/api/auth/forgot", "/api/auth/reset").permitAll()
                        // 前端启动自检要用
                        .requestMatchers("/api/health", "/api/meta").permitAll()
                        .anyRequest().authenticated())
                .logout(l -> l
                        .logoutUrl("/api/auth/logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        // 前端是 fetch 调用，给它 200 而不是 302 重定向
                        .logoutSuccessHandler((req, res, auth) -> res.setStatus(200)))
                .sessionManagement(s -> s
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        // 防会话固定：认证成功后换一个 sessionId，
                        // 攻击者预置的那个 id 就失效了
                        .sessionFixation(f -> f.changeSessionId()))
                // 纯 API 服务，未登录时返回 401，不要重定向到登录页
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> res.setStatus(401)));

        return http.build();
    }
}

package com.shilian.config;

import com.shilian.domain.port.Clock;
import com.shilian.domain.user.User;
import com.shilian.infrastructure.security.ShilianPrincipal;
import com.shilian.repo.UserRepository;
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
 * 已挡掉主要攻击面。彻底修好要前后端一起改，记在 REQUIREMENTS.md 的 C9（阶段 5）。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

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

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
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
                        .requestMatchers("/api/auth/register", "/api/auth/login").permitAll()
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

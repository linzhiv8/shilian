package com.shilian.web.dto;

import com.shilian.domain.user.User;

/**
 * 登录/注册成功后的返回。
 *
 * <p>刻意<b>不含 passwordHash</b>。这不是靠「记得别写进去」来保证的，
 * 而是这个 record 根本没有那个字段——把不该出去的东西挡在类型层面。
 */
public record AuthResponse(
        String id,
        String username,
        String nickname,
        String email,
        String displayName,
        /**
         * 角色（{@code "user"} / {@code "admin"}）。
         *
         * <p>前端靠它决定要不要把管理端的入口显示出来——
         * 没有这个字段，前端要么多请求一次，要么把入口无条件显示，
         * 而后者等于把「存在管理员、而且你不是」这件事告诉所有人。
         *
         * <p>它是<b>只读展示</b>，不是权限判据：真正的校验在
         * {@code /api/admin/**} 那一层（非管理员返回 404）。
         */
        String role
) {
    public static AuthResponse of(User u) {
        return new AuthResponse(u.id(), u.username(), u.nickname(), u.email(),
                u.displayName(), u.roleOrDefault());
    }
}

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
        String displayName
) {
    public static AuthResponse of(User u) {
        return new AuthResponse(u.id(), u.username(), u.nickname(), u.email(), u.displayName());
    }
}

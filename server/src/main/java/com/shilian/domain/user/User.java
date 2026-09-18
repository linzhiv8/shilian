package com.shilian.domain.user;

import com.shilian.util.RelativeTime;

import java.time.LocalDateTime;

/**
 * 用户。
 *
 * <p>字段和 {@code user} 表一一对应，但<b>不含任何框架依赖</b>——
 * 它不认识 Spring Security，也不认识 JDBC。
 */
public record User(
        String id,
        String username,
        String email,
        String passwordHash,
        String nickname,
        String status,
        int failedAttempts,
        String lockedUntil,
        String createdAt,
        String lastLoginAt,
        /**
         * 角色。库里是 {@code NOT NULL DEFAULT 'user'}，
         * 但读出来的行理论上仍可能是 NULL（老库升上来时的脏数据），
         * 判断一律走 {@link #isAdmin()}，别直接比字符串。
         */
        String role
) {
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DISABLED = "disabled";

    public static final String ROLE_USER = "user";
    public static final String ROLE_ADMIN = "admin";

    /**
     * 此刻是否处于锁定状态。
     *
     * <p>时间由调用方传入而不是在这里读系统时钟：
     * 「锁 15 分钟」这条规则因此可以被单测穷举。
     */
    public boolean isLockedAt(LocalDateTime now) {
        if (lockedUntil == null || lockedUntil.isBlank()) {
            return false;
        }
        LocalDateTime until = RelativeTime.parse(lockedUntil);
        return until != null && until.isAfter(now);
    }

    public boolean isDisabled() {
        return STATUS_DISABLED.equals(status);
    }

    /**
     * 是否管理员。
     *
     * <p><b>这只是一个展示用的标记，不是权限判据。</b>
     * 真正的校验在 {@code /api/admin/**} 那一层：非管理员访问管理接口返回 404。
     * 前端拿它决定「要不要显示管理端入口」——不给它，前端要么多请求一次，
     * 要么把入口无条件显示出来（后者等于把「存在管理员」这件事告诉所有人）。
     */
    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }

    /** 对外展示的角色。空值兜成 {@link #ROLE_USER}，免得接口里出现 {@code "role": null}。 */
    public String roleOrDefault() {
        return role == null || role.isBlank() ? ROLE_USER : role;
    }

    /** 展示名：有昵称用昵称，没有就用用户名。 */
    public String displayName() {
        return nickname != null && !nickname.isBlank() ? nickname : username;
    }
}

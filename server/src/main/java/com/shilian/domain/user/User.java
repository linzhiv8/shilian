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
        String lastLoginAt
) {
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DISABLED = "disabled";

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

    /** 展示名：有昵称用昵称，没有就用用户名。 */
    public String displayName() {
        return nickname != null && !nickname.isBlank() ? nickname : username;
    }
}

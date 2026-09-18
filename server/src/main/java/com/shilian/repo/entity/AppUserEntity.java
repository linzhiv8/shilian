package com.shilian.repo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code app_user} 表的实体。
 *
 * <p>表名是 {@code app_user} 而不是 {@code user}：{@code user} 是 MySQL 的保留字，
 * 用它当表名要处处加反引号，而且 {@code SecurityConfig} 里的查询也得跟着改。
 */
@TableName("app_user")
public class AppUserEntity {

    /** 业务主键，12 位十六进制，Java 侧生成。理由同 {@code LinkEntity#id}。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private String username;
    private String email;
    private String passwordHash;
    private String nickname;
    private String status;

    /** 连续登录失败次数。在 SQL 里自增，见 {@code AppUserMapper.recordFailure}。 */
    private Integer failedAttempts;

    /** 锁定到什么时候。时间列一律是 {@code VARCHAR(19)}，理由见 {@code LinkEntity}。 */
    private String lockedUntil;

    private String createdAt;
    private String lastLoginAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getFailedAttempts() { return failedAttempts; }
    public void setFailedAttempts(Integer failedAttempts) { this.failedAttempts = failedAttempts; }

    public String getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(String lockedUntil) { this.lockedUntil = lockedUntil; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(String lastLoginAt) { this.lastLoginAt = lastLoginAt; }
}

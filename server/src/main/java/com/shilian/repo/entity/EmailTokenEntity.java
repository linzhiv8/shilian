package com.shilian.repo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code email_token} 的一行：一次「找回密码」或「验证邮箱」的令牌。
 *
 * <p><b>这个类里没有「令牌本身」这个字段，只有它的哈希。</b>
 * 是刻意的：如果实体上有一个 {@code token} 属性，迟早会有人图省事
 * 把它整个存下来或者打进日志。把明文令牌挡在类型之外，
 * 比靠「记得别存」可靠——明文只存在于生成它的那个方法的局部变量里，
 * 出了那个方法就没有了。
 */
@TableName("email_token")
public class EmailTokenEntity {

    /** 自增。和 {@code ai_log} / {@code audit_log} 一致：它不参与业务，没有业务主键的必要。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String userId;
    private String email;
    private String purpose;
    private String tokenHash;
    private String expiresAt;
    private String usedAt;
    private String createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public String getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(String expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getUsedAt() {
        return usedAt;
    }

    public void setUsedAt(String usedAt) {
        this.usedAt = usedAt;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}

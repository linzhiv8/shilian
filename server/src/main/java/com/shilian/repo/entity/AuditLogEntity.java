package com.shilian.repo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code audit_log} 表的实体。「谁在什么时候做了什么，成了没有」。
 *
 * <p>和 {@link AiLogEntity} 一样是自增主键、只插不改——
 * 审计记录被事后修改就失去意义了，所以这一层也不提供 update。
 *
 * <p><b>为什么不复用 {@code ai_log} 加一个 type 字段。</b>
 * 两类日志的读者完全不同：{@code ai_log} 是给「这个月花了多少」算账用的，
 * 关心 token 数；{@code audit_log} 是给「出事了谁干的」追溯用的，
 * 关心动作和来源。合成一张表之后两种查询都要多带一个 {@code type = ?}，
 * 而漏掉它的后果是「算账算进了审计记录」——不报错，只是数字悄悄不对。
 */
@TableName("audit_log")
public class AuditLogEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 操作者。**可以为 NULL**，且这是刻意的。
     *
     * <p>登录失败而用户名不存在时就没有 userId——而那恰恰是最该留痕的一类请求
     * （有人在挨个试账号）。如果因为「没有 userId 就不记」，最有用的一类记录就丢了。
     */
    private String userId;

    /**
     * 用户名快照。
     *
     * <p>冗余存一份而不是只留 {@code user_id}：用户被删掉之后，
     * 只留 id 的记录就变成一个谁也看不懂的字符串，而审计存在的理由
     * 正是「人没了也要能查到他做过什么」。
     */
    private String username;

    /** 动作。取值见 {@code AuditService} 的七个常量。 */
    private String action;

    /** 被操作的对象：链接 id、被禁用 / 恢复的账号 id。没有就为空。 */
    private String target;

    /** 补充说明，例如失败原因。 */
    private String detail;

    /** 结果：{@code success} / {@code failure} / {@code denied}。 */
    private String result;

    private String ip;

    private String createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}

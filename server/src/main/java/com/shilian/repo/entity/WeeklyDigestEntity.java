package com.shilian.repo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code weekly_digest} 表的实体：周报里 AI 写的那段摘要（含数据指纹）。
 *
 * <p><b>⚠ 这张表的主键是复合的：{@code (user_id, week_key)}。</b>
 * MyBatis-Plus 的 {@code BaseMapper<T>} 只认单个 {@code @TableId}，
 * 它的 {@code selectById / updateById / deleteById} 对这张表<b>语义是错的</b>——
 * 只按 {@code user_id} 去定位，会命中该用户的任意一周。
 *
 * <p>所以这里标 {@code userId} 为 {@code @TableId} 只是为了满足 MyBatis-Plus
 * 「必须有且只有一个主键字段」的要求，让 {@code TableInfo} 能建起来；
 * 对应的 {@code WeeklyDigestMapper} <b>刻意不继承 BaseMapper</b>，
 * 从类型上就让人拿不到那些会静默出错的 CRUD 方法。这张表的全部操作都是手写 SQL。
 *
 * <p>另外注意 {@code user_id} 在 DDL 里是 {@code NOT NULL DEFAULT ''}——
 * 主键列在 MySQL 里不能为 NULL，而「还没有归属」必须有个表示，空串就是那个占位符
 * （别的表用的是 {@code IS NULL}）。真实用户 id 是 12 位十六进制，永远不可能是空串。
 */
@TableName("weekly_digest")
public class WeeklyDigestEntity {

    /** 复合主键的一半。见类注释：不要用 BaseMapper 的按主键方法。 */
    @TableId(value = "user_id", type = IdType.INPUT)
    private String userId;

    /** 复合主键的另一半，形如 {@code 2026-W38}。 */
    private String weekKey;

    /** AI 写的摘要，JSON，里面含 {@code _fp} 数据指纹。 */
    private String summary;

    private String model;
    private Integer promptTokens;
    private Integer completionTokens;
    private String createdAt;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getWeekKey() { return weekKey; }
    public void setWeekKey(String weekKey) { this.weekKey = weekKey; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }

    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}

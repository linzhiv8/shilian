package com.shilian.repo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code ai_log} 表的实体。AI 调用日志，用于成本可见与排查。
 *
 * <p>这张表是自增主键、只插不改 —— 也正是 MyBatis-Plus 的
 * {@code BaseMapper.insert} 真正省掉样板代码的那一类，见 {@code AiLogMapper}。
 */
@TableName("ai_log")
public class AiLogEntity {

    /** 自增主键。全库只有 {@code ai_log} 和 {@code correction} 两张表是自增的。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String linkId;
    private String userId;
    private String model;

    /** 第几次调用（从 1 开始）。 */
    private Integer attempt;

    private Integer promptTokens;
    private Integer completionTokens;

    private Boolean ok;

    /** 重试原因。刻意记 repairLog 而不是 validationErrors，理由见 {@code LinkController.logAiCall}。 */
    private String errors;

    private String createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getLinkId() { return linkId; }
    public void setLinkId(String linkId) { this.linkId = linkId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer attempt) { this.attempt = attempt; }

    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }

    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }

    public Boolean getOk() { return ok; }
    public void setOk(Boolean ok) { this.ok = ok; }

    public String getErrors() { return errors; }
    public void setErrors(String errors) { this.errors = errors; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}

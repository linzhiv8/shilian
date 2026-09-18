package com.shilian.repo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code correction} 表的实体：AI 判成什么 → 用户改成什么。
 *
 * <p>这张表是「用户真正想要什么」的唯一证据，后续作为 few-shot 喂回提示词。
 * 它<b>刻意没有外键级联</b>：链接被删掉时这里的记录保留 —— 存的是用户表达过的
 * 偏好，不是某条链接的属性。
 */
@TableName("correction")
public class CorrectionEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String linkId;
    private String userId;

    /**
     * 列名是 {@code field_name} 而不是 {@code field}：{@code field} 是 SQL 函数名
     * （{@code FIELD()}），在 MySQL 里当列名要处处加反引号，不如换个名字。
     */
    private String fieldName;

    private String aiValue;
    private String userValue;
    private String createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getLinkId() { return linkId; }
    public void setLinkId(String linkId) { this.linkId = linkId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getFieldName() { return fieldName; }
    public void setFieldName(String fieldName) { this.fieldName = fieldName; }

    public String getAiValue() { return aiValue; }
    public void setAiValue(String aiValue) { this.aiValue = aiValue; }

    public String getUserValue() { return userValue; }
    public void setUserValue(String userValue) { this.userValue = userValue; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}

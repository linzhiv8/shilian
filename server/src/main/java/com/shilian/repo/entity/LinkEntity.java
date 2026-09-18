package com.shilian.repo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.shilian.repo.handler.JsonListTypeHandler;

import java.util.List;

/**
 * {@code link} 表的实体。30 列。
 *
 * <p><b>实体不是领域对象，两者刻意分开。</b>
 * {@code LinkItem}（domain 包）是「界面要的那几个字段」，只有 22 个，其中 3 个
 * （{@code createdLabel} / {@code idleDays} / {@code monogram}）还是算出来的、
 * 库里根本没有；而这个实体是「表里有什么」，30 列一个不少。
 *
 * <p>不合并的理由：这个实体里有 10 列是 {@code LinkItem} 用不到的
 * （{@code url_normalized} / {@code user_id} / {@code is_private} /
 * {@code snapshot_text} / {@code content_hash} / {@code ai_raw} /
 * {@code analyze_status} / {@code ai_attempts} / 两个 token 计数）。
 * 把它们塞进 {@code LinkItem} 会让「这个字段界面用不用得上」变得不可知；
 * 反过来，把算出来的 3 个字段塞进实体，会让「哪一列是真实存在的」变得不可知。
 * 转换集中在一处：{@code LinkRepository.toItem()}。
 */
@TableName("link")
public class LinkEntity {

    /**
     * 业务主键，12 位十六进制，由 Java 侧生成。
     *
     * <p>{@link IdType#INPUT} 不能改成 {@code ASSIGN_UUID}：后者生成的是
     * 32 位无横线 UUID，和历史数据的 12 位格式对不上，
     * 于是同一个库里的 id 会长短不一。
     */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private String url;

    /** 去重键。列是 {@code VARCHAR(700) COLLATE utf8mb4_bin}，理由见 DDL 第四节。 */
    private String urlNormalized;

    private String userId;

    /** 主机名（去 www），不是分类。分类在 {@code domainCategory}。 */
    private String domain;

    private String siteName;

    private String title;
    private String summaryShort;
    private String summaryLong;
    private String note;

    /** JSON 数组文本。三列都是 {@code TEXT} 存的 JSON。 */
    @TableField(typeHandler = JsonListTypeHandler.class)
    private List<String> noteOptions;

    private String domainCategory;

    @TableField(typeHandler = JsonListTypeHandler.class)
    private List<String> purposeCategories;

    @TableField(typeHandler = JsonListTypeHandler.class)
    private List<String> tags;

    private String contentType;

    private Double confidence;

    /**
     * 三个 {@code TINYINT} 列用 Boolean 而不是 Integer。
     *
     * <p>MySQL 的 {@code TINYINT(1)} 就是它的布尔类型，MyBatis 的
     * {@code BooleanTypeHandler} 走 {@code rs.getBoolean()/ps.setBoolean()}，
     * 对 TINYINT 同样成立，读写都还是 0/1。
     */
    private Boolean needsReview;

    private Boolean isPrivate;

    private String status;

    private Boolean starred;

    private String snapshotText;
    private String contentHash;
    private String aiRaw;
    private String analyzeStatus;
    private Integer aiAttempts;
    private Integer promptTokens;
    private Integer completionTokens;

    /**
     * 时间列全是 {@code VARCHAR(19)}，不是 {@code DATETIME}。
     *
     * <p>所以这里用 String 而不是 {@code LocalDateTime}，
     * 也<b>刻意不开 MyBatis-Plus 的自动填充</b>（{@code @TableField(fill = ...)}）：
     * 自动填充往字段里写的是 {@code LocalDateTime}，类型对不上；而且填出来的
     * 格式未必是 {@code yyyy-MM-dd'T'HH:mm:ss}，那会静默破坏
     * 「字典序等于时间序」这条被回顾队列和周报依赖的前提
     * （见 {@code RelativeTime.STORE}）。时间一律由 Java 侧生成后传进来。
     */
    private String createdAt;
    private String updatedAt;
    private String lastOpenedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getUrlNormalized() { return urlNormalized; }
    public void setUrlNormalized(String urlNormalized) { this.urlNormalized = urlNormalized; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }

    public String getSiteName() { return siteName; }
    public void setSiteName(String siteName) { this.siteName = siteName; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getSummaryShort() { return summaryShort; }
    public void setSummaryShort(String summaryShort) { this.summaryShort = summaryShort; }

    public String getSummaryLong() { return summaryLong; }
    public void setSummaryLong(String summaryLong) { this.summaryLong = summaryLong; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public List<String> getNoteOptions() { return noteOptions; }
    public void setNoteOptions(List<String> noteOptions) { this.noteOptions = noteOptions; }

    public String getDomainCategory() { return domainCategory; }
    public void setDomainCategory(String domainCategory) { this.domainCategory = domainCategory; }

    public List<String> getPurposeCategories() { return purposeCategories; }
    public void setPurposeCategories(List<String> purposeCategories) { this.purposeCategories = purposeCategories; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }

    public Boolean getNeedsReview() { return needsReview; }
    public void setNeedsReview(Boolean needsReview) { this.needsReview = needsReview; }

    public Boolean getIsPrivate() { return isPrivate; }
    public void setIsPrivate(Boolean isPrivate) { this.isPrivate = isPrivate; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Boolean getStarred() { return starred; }
    public void setStarred(Boolean starred) { this.starred = starred; }

    public String getSnapshotText() { return snapshotText; }
    public void setSnapshotText(String snapshotText) { this.snapshotText = snapshotText; }

    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }

    public String getAiRaw() { return aiRaw; }
    public void setAiRaw(String aiRaw) { this.aiRaw = aiRaw; }

    public String getAnalyzeStatus() { return analyzeStatus; }
    public void setAnalyzeStatus(String analyzeStatus) { this.analyzeStatus = analyzeStatus; }

    public Integer getAiAttempts() { return aiAttempts; }
    public void setAiAttempts(Integer aiAttempts) { this.aiAttempts = aiAttempts; }

    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }

    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    public String getLastOpenedAt() { return lastOpenedAt; }
    public void setLastOpenedAt(String lastOpenedAt) { this.lastOpenedAt = lastOpenedAt; }
}

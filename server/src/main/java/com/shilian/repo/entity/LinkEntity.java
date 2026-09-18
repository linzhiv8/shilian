package com.shilian.repo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.shilian.repo.handler.JsonListTypeHandler;

import java.util.List;

/**
 * {@code link} 表的实体。29 列。
 *
 * <p><b>实体不是领域对象，两者刻意分开。</b>
 * {@code LinkItem}（domain 包）是「界面要的那几个字段」，只有 23 个，其中 3 个
 * （{@code createdLabel} / {@code idleDays} / {@code monogram}）还是算出来的、
 * 库里根本没有；而这个实体是「表里有什么」，一列不少。
 *
 * <p>不合并的理由：这个实体里有 9 列是 {@code LinkItem} 用不到的
 * （{@code url_normalized} / {@code user_id} /
 * {@code snapshot_text} / {@code ai_raw} /
 * {@code analyze_status} / {@code ai_attempts} / 两个 token 计数）。
 * 把它们塞进 {@code LinkItem} 会让「这个字段界面用不用得上」变得不可知；
 * 反过来，把算出来的 3 个字段塞进实体，会让「哪一列是真实存在的」变得不可知。
 * 转换集中在一处：{@code LinkRepository.toItem()}。
 *
 * <p><b>V4 删掉了 {@code content_hash} 一列</b>（见 {@code db/V4__review.sql}）。
 * 它是设计阶段留下的，打算做「内容没变就不重新分析」，功能没做，
 * 而且从来没有被写过——每一行的值都是 NULL。
 * 这里同步删掉字段而不是标 {@code @TableField(exist = false)}——
 * 后者看起来更省事，但它把「这列不存在」这件事藏进了一个注解里，
 * 而下一次有人写 {@code new LinkEntity()} 再 insert 时，
 * 报的错会是「Unknown column」，到那时谁也想不起还有一个注解。
 * 让实体和表严格一一对应，错误就在编译期而不是运行期。
 *
 * <p>{@code is_private} 当初也在候选名单里，但没删，理由见那个字段的注释。
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
     * 四个 {@code TINYINT} 列用 Boolean 而不是 Integer。
     *
     * <p>MySQL 的 {@code TINYINT(1)} 就是它的布尔类型，MyBatis 的
     * {@code BooleanTypeHandler} 走 {@code rs.getBoolean()/ps.setBoolean()}，
     * 对 TINYINT 同样成立，读写都还是 0/1。
     */
    private Boolean needsReview;

    /**
     * 1 = 跳过 AI 直接存下来的（{@code POST /api/links/quick} 那条路）。
     *
     * <p><b>V4 差点删掉这一列，后来撤销了。</b>当时判断它是死列，
     * 依据是「没有代码读写」——写是有的（{@code LinkRepository.insertQuick}），
     * 只是没有查询读它。它记的是「这条记录是怎么来的」，有真实取值，
     * 和 {@code content_hash}（从未被写过、恒为 NULL）不是一回事。
     * 删列不可逆，而没想清楚就删，丢的是补不回来的信息。
     */
    private Boolean isPrivate;

    private String status;

    /**
     * 用没用上。V4 从 {@code status} 里拆出来的，见 {@code db/V4__review.sql}。
     *
     * <p>和 {@code status} 的分工：{@code status} 是「看过了没有、还要不要再推给我」，
     * 这一列是「我有没有真的用上」。两件事都能各自来回拨，互不影响——
     * 混在一个列时，取消「已用」不知道该回 unread 还是 read，只能二选一地猜。
     */
    private Boolean used;

    private Boolean starred;

    private String snapshotText;
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

    public Boolean getUsed() { return used; }
    public void setUsed(Boolean used) { this.used = used; }

    public Boolean getStarred() { return starred; }
    public void setStarred(Boolean starred) { this.starred = starred; }

    public String getSnapshotText() { return snapshotText; }
    public void setSnapshotText(String snapshotText) { this.snapshotText = snapshotText; }

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

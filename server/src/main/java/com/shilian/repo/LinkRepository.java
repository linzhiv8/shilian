package com.shilian.repo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shilian.domain.LinkItem;
import com.shilian.domain.policy.ReviewPolicy;
import com.shilian.domain.port.Clock;
import com.shilian.domain.port.CurrentUser;
import com.shilian.repo.entity.AiLogEntity;
import com.shilian.repo.entity.CorrectionEntity;
import com.shilian.repo.entity.LinkEntity;
import com.shilian.repo.handler.JsonListTypeHandler;
import com.shilian.repo.mapper.AiLogMapper;
import com.shilian.repo.mapper.CorrectionMapper;
import com.shilian.repo.mapper.LinkMapper;
import com.shilian.repo.mapper.WeeklyDigestMapper;
import com.shilian.util.RelativeTime;
import com.shilian.util.TagNames;
import com.shilian.util.Urls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 链接仓储。
 *
 * <p><b>数据访问走 MyBatis-Plus</b>（2026-09-18 从手写 JdbcTemplate 迁过来）。
 * 这个类的公开方法签名<b>一个都没改</b> —— 调用方（Controller / AnalyzeService /
 * CorrectionsFeed / WeeklyDigestService）不需要知道底层换了什么。
 *
 * <p>迁移时的取舍记在 {@code LinkMapper} 的类注释里：{@code link} 表的每一条查询
 * 都必须带 {@code user_id} 过滤，所以那边刻意用手写 SQL 而不是 Wrapper，
 * 让过滤条件留在 {@code WHERE} 里肉眼可见。
 *
 * <p>这个类保留的职责是：<b>领域对象转换 + 业务规则（白名单、截断、留痕）</b>。
 * SQL 全部下放到 Mapper。
 *
 * <p><b>用途过滤仍然放在 Java 侧做。</b>
 * {@code purpose_categories} 是 JSON 数组文本。以前在 SQLite 上要用
 * {@code json_each} 才能展开，换到 MySQL 之后有 {@code JSON_CONTAINS} 了，
 * 所以「方言上做不到」这个理由已经不成立 —— 现在留着它的理由是另外两条：
 * 个人库撑死几千条，全量取回来在内存里过滤是毫秒级；
 * 而且「用途包含全部所选项」这种语义用 SQL 表达要绕一圈，用 Java 一行
 * {@code containsAll} 就说清了。等到真的卡了再换 {@code JSON_CONTAINS}
 * 或独立的关联表。
 */
@Repository
public class LinkRepository {

    private static final Logger log = LoggerFactory.getLogger(LinkRepository.class);

    /** {@code url_normalized} 的列宽。理由见 {@link #fitToColumn}。 */
    private static final int MAX_URL_NORMALIZED = 700;

    private final LinkMapper links;
    private final WeeklyDigestMapper digests;
    private final CorrectionMapper corrections;
    private final AiLogMapper aiLogs;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final CurrentUser current;

    public LinkRepository(LinkMapper links, WeeklyDigestMapper digests,
                          CorrectionMapper corrections, AiLogMapper aiLogs,
                          ObjectMapper mapper, Clock clock, CurrentUser current) {
        this.links = links;
        this.digests = digests;
        this.corrections = corrections;
        this.aiLogs = aiLogs;
        this.mapper = mapper;
        this.clock = clock;
        this.current = current;
    }

    /**
     * 当前用户 id。
     *
     * <p>每一条 SQL 都要带上它，所以收成一个方法：
     * 「按用户过滤」在这个类里只有一处实现，
     * 将来要加「管理员看全部」这类例外，改这里就够了。
     */
    private String uid() {
        return current.id();
    }

    /**
     * {@code url_normalized} 这一列是 {@code VARCHAR(700)}，长度上限是从索引键长
     * 倒推出来的（见 {@code V1__baseline.sql} 的第四节），不是随手定的。
     *
     * <p>超长的键在这里截断，而不是让它去撞数据库的长度限制：
     * 撞上去的表现是保存时报 {@code Data too long for column}，
     * 一条本来正常的收藏因为这个存不进去，而错误信息里没有任何线索指向 URL 长度。
     * 截断的代价只是「前 700 字符相同的两个网址算作同一条」——
     * 实测库里最长的键是 102 字符，这个代价实际上不存在。
     *
     * <p><b>写和查必须用同一套截断</b>，否则会出现「明明存过却查不出重复」。
     * 所以下面每一处用到去重键的地方（写入和查询）都过一遍，
     * 不是只靠调用方记得。
     *
     * <p><b>700 这个数是算出来的，不是拍的。</b>
     * 唯一索引是 {@code (user_id, url_normalized)}，而 InnoDB 对索引键长度有硬上限
     * —— 3072 字节（DYNAMIC 行格式，5.7 之后的默认值）。列是 utf8mb4，
     * 每个字符最多占 4 字节，所以：
     *
     * <pre>
     *   user_id        VARCHAR(32)  →    32 × 4 =  128 字节
     *   url_normalized VARCHAR(700) →   700 × 4 = 2800 字节
     *                                       合计 = 2928 字节  &lt; 3072 ✓
     * </pre>
     *
     * <p>余量只有 144 字节，所以**这两个长度都不能随手加大**：
     * 加到 750 就会超，报错是 {@code Specified key was too long}，
     * 而那句话不会告诉你「是索引太宽」，只会让人去查 URL。
     * 真要放宽，正规做法是加一列存 URL 的哈希、索引哈希而不是原文。
     */
    private static String fitToColumn(String dedupKey) {
        if (dedupKey == null || dedupKey.length() <= MAX_URL_NORMALIZED) {
            return dedupKey;
        }
        log.warn("去重键超过 {} 字符（{}），已截断后入库", MAX_URL_NORMALIZED, dedupKey.length());
        return dedupKey.substring(0, MAX_URL_NORMALIZED);
    }

    /* ────────────── 归属 ────────────── */

    /**
     * 把没有主人的历史数据划给某个用户。
     *
     * <p>V2 迁移只改了结构没划归属，升级上来的老数据 user_id 全是 NULL。
     * 这些是用户一条条攒下来的，不能就这么沉下去——第一个注册的账号把它们接过去。
     *
     * <p><b>只在「第一个账号」时调。</b>第二个账号再调就会把第一个人的数据也划走，
     * 判断由调用方做（{@code UserRepository.countAll() == 1}）。
     *
     * <p><b>并发是安全的</b>，靠的是条件本身而不是「串行执行」：
     * {@code WHERE user_id IS NULL} 是原子的，先执行完的把所有 NULL 行划走，
     * 后到的匹配 0 行（并发时由 InnoDB 的行锁保证顺序，拿到锁时条件已不成立）。
     * 不需要额外加锁。
     *
     * @return 划过去了多少条链接
     */
    public int claimOrphans(String userId) {
        int count = links.claimOrphans(userId);
        corrections.claimOrphans(userId);
        aiLogs.claimOrphans(userId);
        // weekly_digest 的主键是 (user_id, week_key)，而主键列在 MySQL 里不能为 NULL，
        // 所以它的「还没有归属」是空字符串而不是 NULL（见 V1__baseline.sql）。
        // 这是全项目唯一一处用 '' 表示无归属的地方，差异就出在「这一列进了主键」。
        // 第一个账号不可能已有同周记录，countAll() == 1 的判断保证了这里不会撞主键。
        digests.claimOrphans(userId);
        return count;
    }

    /* ────────────── 写入 ────────────── */

    /*
     * analyze_status 的两个取值。
     *
     * 以前这两处是散在代码里的裸字符串 'done' / 'pending'，
     * 而这一列当时没有任何读者——写错了也没人会发现。
     * 现在它通过 LinkItem 暴露给了界面（用来区分两种「待补」），
     * 值就得有个能查到定义的地方，否则改一处漏一处，
     * 界面会开始显示第三种谁也不认识的状态。
     */
    public static final String ANALYZE_DONE = "done";
    public static final String ANALYZE_PENDING = "pending";

    /** 保存时要落库的全部字段。来自 AnalyzeOutcome + 用户在前端确认后的编辑。 */
    public record NewLink(
            String url,
            String site,
            String siteName,
            String title,
            String summary,
            String summaryLong,
            String note,
            List<String> noteOptions,
            String domainCategory,
            List<String> purposes,
            List<String> tags,
            String contentType,
            double confidence,
            boolean needsReview,
            String snapshotText,
            String aiRaw,
            int aiAttempts,
            int promptTokens,
            int completionTokens
    ) {}

    /** URL 已存在。带上已有记录的 id，前端可以直接跳过去。 */
    public static class DuplicateUrlException extends RuntimeException {
        private final String existingId;

        public DuplicateUrlException(String existingId) {
            super("这个网址已经存过了");
            this.existingId = existingId;
        }

        public String existingId() {
            return existingId;
        }
    }

    public LinkItem insert(NewLink in) {
        // 写和查用同一套截断。少了这一步，超长的键会在 INSERT 时撞
        // VARCHAR(700) 报 Data too long，而报错信息不会指向 URL 长度。
        String dedupKey = fitToColumn(Urls.dedupKey(in.url()));
        Optional<LinkItem> existing = findByDedupKey(dedupKey);
        if (existing.isPresent()) {
            throw new DuplicateUrlException(existing.get().id());
        }

        String id = newId();
        String now = RelativeTime.now();

        LinkEntity e = new LinkEntity();
        e.setId(id);
        e.setUrl(in.url());
        e.setUrlNormalized(dedupKey);
        e.setUserId(uid());
        e.setDomain(in.site());
        e.setSiteName(in.siteName());
        e.setTitle(in.title());
        e.setSummaryShort(in.summary());
        e.setSummaryLong(in.summaryLong());
        e.setNote(in.note());
        e.setNoteOptions(in.noteOptions());
        e.setDomainCategory(in.domainCategory());
        e.setPurposeCategories(in.purposes());
        e.setTags(in.tags());
        e.setContentType(in.contentType());
        e.setConfidence(in.confidence());
        e.setNeedsReview(in.needsReview());
        e.setStatus("unread");
        e.setStarred(false);
        e.setSnapshotText(in.snapshotText());
        e.setAiRaw(in.aiRaw());
        e.setAnalyzeStatus(ANALYZE_DONE);
        e.setAiAttempts(in.aiAttempts());
        e.setPromptTokens(in.promptTokens());
        e.setCompletionTokens(in.completionTokens());
        e.setCreatedAt(now);

        try {
            // 其余字段（updated_at / last_opened_at / content_hash / is_private）
            // 留 null，MyBatis-Plus 的插入策略会跳过 null 字段，让 DDL 的默认值生效
            // —— 和迁移前手写 INSERT 时「不列这些列」等价。
            links.insert(e);
        } catch (DuplicateKeyException ex) {
            // 并发下的兜底：唯一索引挡住的
            Optional<LinkItem> again = findByDedupKey(dedupKey);
            throw new DuplicateUrlException(again.map(LinkItem::id).orElse(null));
        }

        return findById(id).orElseThrow(() -> new IllegalStateException("刚写入的记录读不出来：" + id));
    }

    /** 跳过 AI 直接保存。对应 {@code link.is_private = 1}。 */
    public record QuickSave(String url, String title) {}

    /**
     * 不经过 AI，直接把网址存下来。
     *
     * <p><b>为什么单独一个方法而不是给 {@link NewLink} 加字段。</b>
     * 这两条路的差别不只是「有没有 AI 产物」：这条路上 confidence、分类、
     * 标签、快照全都是空的，硬塞进 {@code NewLink} 会让那个 record
     * 多出一堆「这种情况下必然是 null」的字段，读的人反而要猜哪些能空。
     * 分开写，每条路各自只声明自己真有的东西。
     *
     * <p><b>几个字段为什么这么设：</b>
     * <ul>
     *   <li>{@code is_private = 1}——schema 里预留这个字段时就写了
     *       「跳过 AI，仅本地保存」，这里终于对上了。</li>
     *   <li>{@code analyze_status = 'pending'}——标记「还没分析过」。
     *       不写 'done'：写了就等于说这条分析过了，以后没人会再来补。</li>
     *   <li>{@code needs_review = 1}——让它和普通卡片看起来不一样，
     *       用户知道这条还差一道工序。</li>
     * </ul>
     *
     * <p>去重规则和普通保存一致：同一个网址不会存成两条。
     */
    public LinkItem insertQuick(QuickSave in) {
        String dedupKey = fitToColumn(Urls.dedupKey(in.url()));
        Optional<LinkItem> existing = findByDedupKey(dedupKey);
        if (existing.isPresent()) {
            throw new DuplicateUrlException(existing.get().id());
        }

        String id = newId();
        String now = RelativeTime.now();
        String host = Urls.hostOf(in.url());

        LinkEntity e = new LinkEntity();
        e.setId(id);
        e.setUrl(in.url());
        e.setUrlNormalized(dedupKey);
        e.setUserId(uid());
        e.setDomain(host);
        // 没给标题就用域名，总比空白的卡片强
        e.setTitle(in.title() == null || in.title().isBlank() ? host : in.title().trim());
        e.setIsPrivate(true);
        e.setNeedsReview(true);
        e.setStatus("unread");
        e.setStarred(false);
        e.setAnalyzeStatus(ANALYZE_PENDING);
        e.setCreatedAt(now);

        try {
            links.insert(e);
        } catch (DuplicateKeyException ex) {
            Optional<LinkItem> again = findByDedupKey(dedupKey);
            throw new DuplicateUrlException(again.map(LinkItem::id).orElse(null));
        }

        return findById(id).orElseThrow(() -> new IllegalStateException("刚写入的记录读不出来：" + id));
    }

    /**
     * 重分析要覆盖的字段。
     *
     * <p>刻意<b>不含</b> {@code url} / {@code starred} / {@code status} /
     * {@code created_at} / {@code last_opened_at}。用独立 record 而不是复用
     * {@link NewLink}，就是为了让「哪些字段不该被这次操作碰到」在类型上就写死——
     * 复用 NewLink 的话，调用方传什么进来都可能被写进去。
     */
    public record Reanalysis(
            String title,
            String summary,
            String summaryLong,
            String note,
            List<String> noteOptions,
            String domainCategory,
            List<String> purposes,
            List<String> tags,
            String contentType,
            double confidence,
            boolean needsReview,
            /** 这次真正用到的正文。补正文的全部意义就在把它存下来 */
            String snapshotText,
            String aiRaw,
            int aiAttempts,
            int promptTokens,
            int completionTokens
    ) {}

    /**
     * 用一次新的分析结果替换掉已存记录里「属于 AI 的那部分」。
     *
     * <p><b>为什么不能直接用 {@link #patch}。</b> 两个原因：
     * <ol>
     *   <li>{@code patch} 只认 {@link #EDITABLE} 白名单，而这里要写的是
     *       {@code title} / {@code snapshot_text} / {@code ai_raw} 这类「AI 的产物」，
     *       它们本来就不该让前端随便改。</li>
     *   <li>更要紧的是：{@code patch} 会往 {@code correction} 表记一条
     *       「AI 判 X → 用户改成 Y」。而补正文<b>不是用户在纠正 AI</b>，
     *       是 AI 拿到了更好的输入重新判了一次。把模型自己的新输出
     *       当成用户的偏好信号喂回去，等于让它照着自己的影子学习——
     *       那张表是「用户真正想要什么」的唯一证据，不能这么污染。</li>
     * </ol>
     *
     * <p><b>刻意不动的字段：</b> {@code url} 是这条记录的身份；{@code starred} /
     * {@code status} / {@code created_at} / {@code last_opened_at} 是「用户和它的关系」。
     * 补一次正文不该让一条已加星、已标已用的记录退回未读，也不该把「存了多久」重置成现在。
     *
     * <p>这几条约束在 SQL 侧由 {@code LinkMapper.replaceAnalysis} 的列清单兜住 ——
     * 那个语句只列了该动的列，而不是拿实体去全量更新。
     */
    public Optional<LinkItem> replaceAnalysis(String id, Reanalysis in) {
        if (findById(id).isEmpty()) {
            return Optional.empty();
        }

        LinkEntity e = new LinkEntity();
        e.setId(id);
        e.setTitle(in.title());
        e.setSummaryShort(in.summary());
        e.setSummaryLong(in.summaryLong());
        e.setNote(in.note());
        e.setNoteOptions(in.noteOptions());
        e.setDomainCategory(in.domainCategory());
        e.setPurposeCategories(in.purposes());
        e.setTags(in.tags());
        e.setContentType(in.contentType());
        e.setConfidence(in.confidence());
        e.setNeedsReview(in.needsReview());
        e.setSnapshotText(in.snapshotText());
        e.setAiRaw(in.aiRaw());
        e.setAiAttempts(in.aiAttempts());
        e.setPromptTokens(in.promptTokens());
        e.setCompletionTokens(in.completionTokens());

        int n = links.replaceAnalysis(e, uid(), RelativeTime.now());
        return n == 0 ? Optional.empty() : findById(id);
    }

    /* ────────────── 查询 ────────────── */

    /**
     * 按 id 查，带用户过滤。
     *
     * <p><b>过滤必须写在这里，不能靠 controller 先查再比对。</b>
     * 那样做的话，「忘了比对」的表现是 A 能读到 B 的记录——不报错、不告警，
     * 只有等用户真的看见别人的数据才会被发现。而写在 WHERE 里，
     * 结果就是「找不到」，和记录不存在完全一样，天然不泄露。
     */
    public Optional<LinkItem> findById(String id) {
        return Optional.ofNullable(links.findByIdForUser(id, uid())).map(this::toItem);
    }

    /**
     * 同一用户下是否存过这个网址。
     *
     * <p>带上用户条件不只是为了隔离——V2 之后唯一索引本来就是
     * {@code (user_id, url_normalized)}，A 存过的网址 B 是可以再存一次的。
     * 不加这个条件，B 存一个 A 存过的网址会收到「重复」的提示，属于凭空报错。
     *
     * <p>这里再过一遍 {@link #fitToColumn}：调用方可能已经截过，但仓储自己也要兜住，
     * 因为「写和查用同一套截断」这件事靠调用方记住是不可靠的。
     */
    public Optional<LinkItem> findByDedupKey(String dedupKey) {
        if (dedupKey == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(links.findByDedupKey(fitToColumn(dedupKey), uid())).map(this::toItem);
    }

    /**
     * 列表查询。
     *
     * @param domainCategory 领域筛选，null 表示不限
     * @param purposes       用途筛选，取交集（每条记录要同时具备选中的全部用途）
     * @param keyword        关键词，命中标题/摘要/备注/标签
     * @param sort           recent | starred | stale
     */
    public List<LinkItem> search(String domainCategory, List<String> purposes, String keyword, String sort) {
        String likePattern = (keyword == null || keyword.isBlank())
                ? null
                : "%" + escapeLike(keyword.trim()) + "%";

        List<LinkEntity> rows = links.search(uid(), domainCategory, likePattern, orderBy(sort));

        List<LinkItem> items = new ArrayList<>(rows.size());
        for (LinkEntity e : rows) {
            items.add(toItem(e));
        }
        if (purposes == null || purposes.isEmpty()) {
            return items;
        }
        List<LinkItem> filtered = new ArrayList<>();
        for (LinkItem item : items) {
            if (item.purposes().containsAll(purposes)) {
                filtered.add(item);
            }
        }
        return filtered;
    }

    /** 排序白名单。绝不让用户输入直接进 ORDER BY。 */
    private static String orderBy(String sort) {
        return switch (sort == null ? "recent" : sort) {
            case "starred" -> "starred DESC, created_at DESC";
            // 「该回头看了」：最久没打开的排最前，从没打开过的按创建时间算
            case "stale" -> "COALESCE(last_opened_at, created_at) ASC";
            default -> "created_at DESC";
        };
    }

    /**
     * 把用户输入里的 LIKE 通配符转义掉，免得搜「100%」变成搜「以 100 开头」。
     *
     * <p><b>转义符用 {@code !}，不用默认的反斜杠。</b>
     * 反斜杠在 MySQL 的字符串字面量里本身就是转义符，所以 {@code ESCAPE '\'}
     * 是「字符串没闭合」的语法错误，得写成 {@code ESCAPE '\\'}。
     * 而 Java 源码里的 {@code "\\"} 只是一个字符、{@code "\\\\"} 才是两个——
     * 也就是说这一个转义符要在「Java 字符串 → SQL 字面量 → LIKE 模式」三层里
     * <b>各自数一遍反斜杠</b>，任何一层数错都不会报错，只会静默地不转义。
     *
     * <p>换一个普通字符当转义符，这三层就都不用翻倍了。
     * {@code !} 在正常标题和摘要里很少出现，多转义一层没有代价。
     *
     * <p>（{@code ESCAPE '!'} 写在 {@code LinkMapper.search} 的 apply 片段里。）
     */
    private static String escapeLike(String s) {
        return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    /**
     * 回顾队列：存了 N 天以上、没星标、也没处理过的。
     * 这是「收藏了却再也不看」这个隐性问题的唯一解药，所以单独一条查询。
     *
     * <p>筛选条件和判据都见 {@code LinkMapper.REVIEW_WHERE} 与
     * {@link ReviewPolicy}：前者保证「取一批」和「数总数」用同一个条件，
     * 后者把「闲置 N 天」这条业务规则放在纯 Java 里、可以单独验。
     */
    public List<LinkItem> dueForReview(int days, int limit) {
        return links.dueForReview(uid(), cutoffOf(days), limit).stream().map(this::toItem).toList();
    }

    /** 队列里一共有多少条。用于侧栏计数和「今天还剩几张」。 */
    public int countDueForReview(int days) {
        return links.countDueForReview(uid(), cutoffOf(days));
    }

    /**
     * 把「闲置 N 天」翻译成一个时间点。
     *
     * <p>取一批和数总数都走这里，所以两边用的必然是同一个分界线——
     * 不会再现「侧栏说 5 条、点进去 3 条」那种不一致。
     */
    private String cutoffOf(int days) {
        return RelativeTime.format(new ReviewPolicy(days).cutoff(clock.now()));
    }

    /* ────────────── 标签 ────────────── */

    /** 一个标签以及有多少条链接在用。 */
    public record TagCount(String name, int count) {}

    /**
     * 当前用户的全部标签及条数。
     *
     * <p><b>统计放在 Java 侧而不是 SQL。</b>
     * {@code tags} 是 JSON 数组文本，用 SQL 展开要绕一圈 {@code JSON_TABLE}，
     * 而个人库的量级（几百条，标签几十个）全量取回来在内存里数是微秒级。
     * 同一个取舍在 {@link #search} 的用途过滤上做过一次，理由一致。
     *
     * <p><b>同一条链接上的重复标签只算一次。</b>
     * 脏数据里可能有 {@code ["a","a"]}，不去重的话条数会虚高，
     * 用户看到一个标签写着 2 条、点进去只有 1 条。
     *
     * <p>排序按条数降序、同条数按名字升序：条数多的在前（那是他真正在用的），
     * 而同条数时按名字排是为了输出稳定——前端 chips 的顺序不会每次刷新都变。
     */
    public List<TagCount> tagCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (LinkMapper.TagRow row : links.tagRows(uid())) {
            for (String tag : distinct(JsonListTypeHandler.fromJson(row.tags()))) {
                if (!tag.isBlank()) {
                    counts.merge(tag, 1, Integer::sum);
                }
            }
        }
        return counts.entrySet().stream()
                .map(e -> new TagCount(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingInt(TagCount::count).reversed()
                        .thenComparing(TagCount::name))
                .toList();
    }

    /**
     * 标签改名。
     *
     * @return 改了多少条链接
     */
    @Transactional(rollbackFor = Exception.class)
    public int renameTag(String from, String to) {
        String src = TagNames.normalize(from);
        String dst = TagNames.normalize(to);
        if (src == null || dst == null || src.equals(dst)) {
            return 0;
        }
        int affected = 0;
        for (LinkMapper.TagRow row : links.tagRows(uid())) {
            List<String> tags = distinct(JsonListTypeHandler.fromJson(row.tags()));
            if (!tags.contains(src)) {
                continue;
            }
            List<String> updated = new ArrayList<>(tags.size());
            for (String t : tags) {
                updated.add(src.equals(t) ? dst : t);
            }
            /*
             * 目标标签已经在这条上的时候（["a","b"] 把 a 改成 b），
             * 直接替换会产生 ["b","b"]。去重一次，合并后不出现重复。
             */
            affected += writeTags(row.id(), distinct(updated));
        }
        return affected;
    }

    /**
     * 把几个标签并到一个上。
     *
     * <p>和「改名」分开成两个接口，是因为它们的语义不同：
     * 改名是一对一地换名字，合并是多对一地<b>减少</b>标签个数。
     * 合并一定要保证目标标签存在——只删来源不加目标的话，
     * 「合并」会变成「全部删掉」，那是一条记录上的标签凭空消失。
     *
     * @return 改了多少条链接
     */
    @Transactional(rollbackFor = Exception.class)
    public int mergeTags(List<String> sources, String target) {
        String dst = TagNames.normalize(target);
        if (dst == null || sources == null) {
            return 0;
        }
        Set<String> src = new LinkedHashSet<>();
        for (String s : sources) {
            String n = TagNames.normalize(s);
            // 目标本身也在 sources 里时跳过：它是要保留的那个，不是要删掉的
            if (n != null && !n.equals(dst)) {
                src.add(n);
            }
        }
        if (src.isEmpty()) {
            return 0;
        }
        int affected = 0;
        for (LinkMapper.TagRow row : links.tagRows(uid())) {
            List<String> tags = distinct(JsonListTypeHandler.fromJson(row.tags()));
            if (tags.stream().noneMatch(src::contains)) {
                continue;
            }
            List<String> updated = new ArrayList<>();
            for (String t : tags) {
                if (!src.contains(t)) {
                    updated.add(t);
                }
            }
            if (!updated.contains(dst)) {
                updated.add(dst);
            }
            affected += writeTags(row.id(), updated);
        }
        return affected;
    }

    /**
     * 删掉一个标签。<b>只摘标签，不动链接本身</b>——
     * 「删标签」和「删链接」是两件事，混在一起会让用户不敢点。
     *
     * @return 改了多少条链接
     */
    @Transactional(rollbackFor = Exception.class)
    public int deleteTag(String name) {
        String target = TagNames.normalize(name);
        if (target == null) {
            return 0;
        }
        int affected = 0;
        for (LinkMapper.TagRow row : links.tagRows(uid())) {
            List<String> tags = distinct(JsonListTypeHandler.fromJson(row.tags()));
            if (!tags.contains(target)) {
                continue;
            }
            List<String> updated = new ArrayList<>();
            for (String t : tags) {
                if (!target.equals(t)) {
                    updated.add(t);
                }
            }
            affected += writeTags(row.id(), updated);
        }
        return affected;
    }

    /** 写回一行标签，并顺手刷 updated_at。 */
    private int writeTags(String linkId, List<String> tags) {
        return links.updateTags(linkId, uid(), JsonListTypeHandler.toJson(tags),
                RelativeTime.format(clock.now()));
    }

    /** 去空格、去空项、保留顺序去重。 */
    private static List<String> distinct(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String t : tags) {
            String name = TagNames.normalize(t);
            if (name != null) {
                seen.add(name);
            }
        }
        return new ArrayList<>(seen);
    }

    /* ────────────── 导出 ────────────── */

    /**
     * 导出用的全量列表（R-07）。
     *
     * <p>和 {@link #search} 的区别只是<b>不拉正文快照</b>：
     * 导出的形状用不到它，而它是全表最大的一列。
     */
    public List<LinkItem> forExport() {
        return links.selectForExport(uid()).stream().map(this::toItem).toList();
    }

    /* ────────────── 管理端 ────────────── */

    /** 每个用户各有多少条链接。给管理端的用户列表用。 */
    public Map<String, Integer> linkCountsByUser() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (LinkMapper.UserLinkCount c : links.countByUser()) {
            out.put(c.userId(), c.count());
        }
        return out;
    }

    /** 某个用户的 AI 用量合计。 */
    public record AiUsage(long calls, long promptTokens, long completionTokens) {
        public static final AiUsage EMPTY = new AiUsage(0L, 0L, 0L);
    }

    /**
     * 每个用户各花了多少（R-15 的入账在这里变成管理端能看的数字）。
     *
     * <p>一次 GROUP BY 全量取回，而不是给页面上的 20 个用户各查一次。
     */
    public Map<String, AiUsage> aiUsageByUser() {
        Map<String, AiUsage> out = new LinkedHashMap<>();
        for (AiLogMapper.AiUsageRow r : aiLogs.usageByUser()) {
            out.put(r.userId(), new AiUsage(r.calls(), r.promptTokens(), r.completionTokens()));
        }
        return out;
    }

    /* ────────────── 周报 ────────────── */

    /** 领域分布的一项。 */
    public record DomainCount(String domain, int count) {}

    /**
     * 周报的统计部分。
     *
     * <p>刻意<b>不</b>包含 AI 摘要——那个由服务层负责并且要缓存。
     * 这里的数字都是几条 COUNT，毫秒级，每次重算反而不会过期。
     */
    public record WeeklyStats(
            /** 这段时间存了几条 */
            int saved,
            /** 这段时间打开过几条 */
            int opened,
            /** 这段时间标为已用的几条（用 updated_at 算，因为改状态会刷新它） */
            int used,
            /** 从存下来到现在一次都没打开过的总数——「数字坟场」最直接的度量 */
            int neverOpenedTotal,
            /** 库里的总数 */
            int total,
            List<DomainCount> topDomains,
            /** 这段时间新增的条目本身，给 AI 写摘要用 */
            List<LinkItem> newLinks
    ) {}

    /** 最近 N 天的收藏情况。周报的数据源。 */
    public WeeklyStats weeklyStats(int days) {
        String uid = uid();
        String since = RelativeTime.format(clock.now().minusDays(days));

        int saved = links.countSavedSince(uid, since);
        int opened = links.countOpenedSince(uid, since);
        int used = links.countUsedSince(uid, since);
        int neverOpened = links.countNeverOpened(uid);
        int total = links.countAllForUser(uid);

        // Mapper 返回的是它自己那个 DomainCount，这里换成仓储对外暴露的那个。
        // 两处同名 record 是刻意的：Mapper 不该反向依赖仓储的公开类型。
        List<DomainCount> domains = links.topDomainsSince(uid, since).stream()
                .map(d -> new DomainCount(d.domain(), d.count()))
                .toList();

        List<LinkItem> newLinks = links.createdSince(uid, since).stream().map(this::toItem).toList();

        return new WeeklyStats(saved, opened, used, neverOpened, total, domains, newLinks);
    }

    /* ────────────── 周报摘要的缓存 ────────────── */

    /** 读缓存的 AI 摘要。没有就返回空。 */
    public Optional<String> findDigestSummary(String weekKey) {
        return Optional.ofNullable(digests.findSummary(uid(), weekKey));
    }

    /**
     * 存 AI 摘要。
     *
     * <p>用 {@code ON DUPLICATE KEY UPDATE}：同一周内重跑（比如换了模型、
     * 或想重写一次）直接覆盖，不需要先查再决定插还是更。
     * 两种写法的语义差异（{@code INSERT OR REPLACE} 是先删后插）记在
     * {@code WeeklyDigestMapper.saveSummary} 上。
     */
    public void saveDigestSummary(String weekKey, String summary, String model,
                                  int promptTokens, int completionTokens) {
        digests.saveSummary(uid(), weekKey, summary, model, promptTokens, completionTokens,
                RelativeTime.now());
    }

    /** 清掉某一周的缓存，让下次打开重写。 */
    public int clearDigestSummary(String weekKey) {
        return digests.clear(uid(), weekKey);
    }

    /* ────────────── 修改与删除 ────────────── */

    /**
     * 局部更新。允许改的字段在 {@link #EDITABLE} 里白名单化。
     *
     * <p>如果改的是 AI 判断过的字段，会顺手记一条 correction。
     * 这个差值就是最真实的偏好信号——用户嘴上说的偏好常常和实际行为不一致，
     * 而他动手改的这一下不会骗人。
     */
    private static final List<String> EDITABLE = List.of(
            "title", "summary_short",
            "note", "domain_category", "purpose_categories", "tags",
            /*
             * used 也在白名单里——V4 之后它是独立列，前端要能直接拨这个开关。
             * 它和 status 的区别是：status 只有服务端和「标已读」会动，
             * 而 used 是用户随手来回拨的，所以它必须走和 starred 一样的路径。
             */
            "starred", "status", "used", "last_opened_at");

    /**
     * 改动时需要留痕的字段 → correction 表里的 field_name。
     *
     * <p>为什么 title / summary_short 也要留痕：这两个是最容易被用户动手改的
     * （AI 起的标题经常不对味）。而「用户把标题从 X 改成 Y」这个信号，
     * 比领域和备注更能说明他想要什么样的表达——是那种调提示词时最想要的证据。
     *
     * <p>不加 summary_long：它只是摘要的展开版，用户几乎不会去改；
     * 而且它很长，进 correction 表会把「偏好信号」稀释成一大段文本。
     */
    private static final Map<String, String> TRACKED = Map.of(
            "domain_category", "domain_category",
            "purpose_categories", "purpose_categories",
            "note", "note",
            "title", "title",
            "summary_short", "summary_short");

    /**
     * 改一条记录。
     *
     * <p><b>为什么要 {@code @Transactional}。</b>
     * 这个方法做两件事：改 link、写 correction。没有事务时它们是各自独立提交的，
     * 于是「改成功了、写留痕失败了」会留下一个很难受的状态：
     * 接口返回 500（客户端以为没改成），但数据其实已经变了。
     * 历史上有一次真撞上过这一幕（当时是 correction 的列名写错）。
     *
     * <p>correction 是提示词的反馈来源，静默丢一条就是静默降低一点效果。
     * 与其让它们不一致，不如整件事一起成功或一起失败。
     *
     * <p><b>为什么显式写 {@code rollbackFor}。</b>Spring 默认只对
     * {@code RuntimeException} 和 {@code Error} 回滚，受检异常是**提交**的
     * （这个行为反直觉，而且不报错，只在真出事时才发现没回滚）。
     * 这里目前只抛运行时异常，所以写不写行为一样——但这是「碰巧对」，
     * 哪天有人在方法体里加一个受检异常，事务边界会静默失效。
     * 显式声明把这条约束固定在类型上，也符合阿里巴巴开发手册的强制项。
     */
    @Transactional(rollbackFor = Exception.class)
    public Optional<LinkItem> patch(String id, Map<String, Object> changes) {
        Optional<LinkItem> before = findById(id);
        if (before.isEmpty()) {
            return Optional.empty();
        }
        LinkItem old = before.get();

        Map<String, Object> sets = new LinkedHashMap<>();
        Map<String, String> corrections = new LinkedHashMap<>();

        for (Map.Entry<String, Object> e : changes.entrySet()) {
            String field = e.getKey();
            if (!EDITABLE.contains(field) || e.getValue() == null) {
                continue;
            }
            Object value = e.getValue();
            if (value instanceof List<?> list) {
                value = JsonListTypeHandler.toJson(list.stream().map(String::valueOf).toList());
            } else if (value instanceof Boolean b) {
                value = b ? 1 : 0;
            }
            sets.put(field, value);

            if (TRACKED.containsKey(field)) {
                String oldValue = oldValueOf(old, field);
                String newValue = String.valueOf(value);
                if (oldValue != null && !oldValue.equals(newValue)) {
                    corrections.put(TRACKED.get(field), oldValue + "\u0000" + newValue);
                }
            }
        }

        if (sets.isEmpty()) {
            return Optional.of(old);
        }

        links.patchForUser(id, uid(), sets, RelativeTime.now());

        corrections.forEach((field, pair) -> {
            String[] parts = pair.split("\u0000", 2);
            insertCorrection(id, field, parts[0], parts[1]);
        });

        return findById(id);
    }

    /**
     * 取改动前的值。
     *
     * <p>优先从 {@code ai_raw} 里读 AI 的原始判断，这样即使用户改了两次，
     * 记下来的仍然是「AI 原本判的 → 用户最终改成的」。
     * 读不到就退回当前值——第一次改动时两者相同，结果一样。
     */
    private String oldValueOf(LinkItem item, String field) {
        String fromAi = readFromAiRaw(item.id(), field);
        if (fromAi != null) {
            return fromAi;
        }
        return switch (field) {
            case "title" -> item.title();
            case "summary_short" -> item.summary();
            case "domain_category" -> item.domainKey();
            case "purpose_categories" -> JsonListTypeHandler.toJson(item.purposes());
            case "note" -> item.note();
            default -> null;
        };
    }

    private String readFromAiRaw(String linkId, String field) {
        try {
            String raw = links.readAiRaw(linkId, uid());
            if (raw == null || raw.isBlank()) {
                return null;
            }
            JsonNode node = mapper.readTree(raw).get(field);
            if (node == null || node.isNull()) {
                return null;
            }
            return node.isArray() ? mapper.writeValueAsString(node) : node.asText();
        } catch (Exception e) {
            log.debug("读 ai_raw 失败，回退到当前值：{}", e.toString());
            return null;
        }
    }

    /**
     * 删除。
     *
     * <p><b>返回值在这里有两层含义，这是刻意的。</b>
     * 「不存在」和「不是你的」都返回 false，最终都是 404。
     * 区分二者等于告诉调用方「这条记录存在，只是不属于你」——
     * 那正是数据泄露的起点。
     */
    public boolean delete(String id) {
        return links.deleteForUser(id, uid()) > 0;
    }

    /** 记一次打开，用于「多久没看了」的判断。 */
    public void touchOpened(String id) {
        links.touchOpened(id, uid(), RelativeTime.now());
    }

    /* ────────────── 修正与日志 ────────────── */

    public void insertCorrection(String linkId, String field, String aiValue, String userValue) {
        // 列名是 field_name，不是 field：field 是 SQL 函数名（FIELD()），
        // 在 MySQL 里当列名要处处加反引号，不如换个名字。
        CorrectionEntity c = new CorrectionEntity();
        c.setLinkId(linkId);
        c.setUserId(uid());
        c.setFieldName(field);
        c.setAiValue(aiValue);
        c.setUserValue(userValue);
        c.setCreatedAt(RelativeTime.now());
        corrections.insert(c);
    }

    public void insertAiLog(String linkId, String model, int attempt,
                            int promptTokens, int completionTokens, boolean ok, String errors) {
        AiLogEntity a = new AiLogEntity();
        a.setLinkId(linkId);
        a.setUserId(uid());
        a.setModel(model);
        a.setAttempt(attempt);
        a.setPromptTokens(promptTokens);
        a.setCompletionTokens(completionTokens);
        a.setOk(ok);
        a.setErrors(errors);
        a.setCreatedAt(RelativeTime.now());
        aiLogs.insert(a);
    }

    /** 某一类修正出现过多少次。 */
    public record CorrectionStat(String field, String aiValue, String userValue, int count) {}

    /**
     * 取出「用户把 AI 的判断改成什么」的汇总，供提示词使用。
     *
     * <p>这里只给数据，<b>不拼提示词</b>——拼装是
     * {@code CorrectionsFeed} 的事。仓储不该知道提示词长什么样。
     */
    public List<CorrectionStat> correctionStats(int limit) {
        return corrections.correctionStats(uid(), limit).stream()
                .map(c -> new CorrectionStat(c.field(), c.aiValue(), c.userValue(), c.count()))
                .toList();
    }

    /* ────────────── 映射 ────────────── */

    /**
     * 实体 → 领域对象。<b>整个工程只有这一处做这个转换。</b>
     *
     * <p>三个字段是这里算出来的、库里没有：{@code createdLabel}（「3 天前」）、
     * {@code idleDays}、{@code monogram}（卡片左上角那个字母块）。
     * 它们是「界面要什么」而不是「表里有什么」，所以归在这一层。
     *
     * <p>几个兜底和迁移前逐字一致：{@code confidence} 为 NULL 时算 0.0，
     * {@code starred} / {@code needs_review} 按 {@code == 1} 判真，
     * {@code last_opened_at} 为空时用 {@code created_at} 算闲置天数。
     */
    private LinkItem toItem(LinkEntity e) {
        String url = e.getUrl();
        String createdAt = e.getCreatedAt();
        String lastOpenedAt = e.getLastOpenedAt();
        /*
         * 站点名优先用 og:site_name（站点自称的名字），没抓到才退回主机名。
         *
         * 「mp.weixin.qq.com」和「微信公众号」是同一回事，
         * 但后者才是人认得的那一个，而存网址的人半年后要靠这一行想起这是哪。
         *
         * 退回主机名而不是留空：绝大多数页面根本不写 og:site_name，
         * 留空的话这一列十次有九次是空白，等于没有。
         */
        String site = e.getSiteName() == null || e.getSiteName().isBlank()
                ? e.getDomain()
                : e.getSiteName();
        return new LinkItem(
                e.getId(),
                url,
                site,
                e.getTitle(),
                e.getSummaryShort(),
                e.getSummaryLong(),
                e.getNote(),
                e.getNoteOptions(),
                e.getDomainCategory(),
                e.getPurposeCategories(),
                e.getTags(),
                e.getContentType(),
                RelativeTime.label(createdAt),
                RelativeTime.idleDays(lastOpenedAt != null ? lastOpenedAt : createdAt),
                Boolean.TRUE.equals(e.getStarred()),
                e.getStatus(),
                Boolean.TRUE.equals(e.getUsed()),
                e.getConfidence() == null ? 0.0 : e.getConfidence(),
                Boolean.TRUE.equals(e.getNeedsReview()),
                e.getAnalyzeStatus(),
                Urls.monogram(url),
                createdAt,
                lastOpenedAt);
    }

    /** 12 位十六进制。用 {@code IdType.INPUT} 保住这个格式，别改成 UUID 全串。 */
    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}

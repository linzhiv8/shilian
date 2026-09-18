package com.shilian.search;

import com.shilian.config.ShilianProperties;
import com.shilian.domain.Domain;
import com.shilian.domain.LinkItem;
import com.shilian.domain.Purpose;
import com.shilian.domain.port.EmbeddingPort;
import com.shilian.repo.EmbeddingRepository;
import com.shilian.repo.LinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 语义搜索。
 *
 * <p><b>为什么不是「先建索引再搜」。</b> 个人库的量级（几百到几千条）不值得维护一个
 * 后台索引任务：它要么得有个定时器、要么得在保存时异步触发，两条路都会引入
 * 「索引落后于数据」的状态，而搜索结果的诡异程度和这个滞后成正比。
 * 这里改成<b>惰性补齐</b>：搜索时看哪些记录的文本变了、没有向量，批量补上再排。
 * 第一次搜会慢一点（多一次批量调用），之后每条都命中缓存，一次调用搞定。
 *
 * <p><b>连接纪律。</b> 这条链路上要调外部 API（几百毫秒到几十秒），所以整个流程
 * 刻意分成「读库 → 调 API → 写库」三段，中间绝不持有连接。方法上没有
 * {@code @Transactional}：加上之后事务会跨着那几百毫秒一直开着——池子只有 8 个
 * 连接，占住几个别人就开始排队，而且事务开得越久、锁持有越久、回滚代价越大。
 */
@Service
public class SemanticSearchService {

    private static final Logger log = LoggerFactory.getLogger(SemanticSearchService.class);

    private final LinkRepository links;
    private final EmbeddingRepository embeddings;
    private final EmbeddingPort client;
    private final ShilianProperties props;

    public SemanticSearchService(LinkRepository links, EmbeddingRepository embeddings,
                                 EmbeddingPort client, ShilianProperties props) {
        this.links = links;
        this.embeddings = embeddings;
        this.client = client;
        this.props = props;
    }

    /** 一条命中。score 是余弦相似度，-1 到 1，越大越像。 */
    public record Hit(String id, double score) {}

    /**
     * @param hits     命中的记录，按相似度降序
     * @param fallback true 表示「没有一条达到阈值，这几条是矮子里拔将军」
     * @param computed 本次补算了几条向量（>0 说明这次慢在了补齐上）
     * @param total    参与比较的记录总数
     * @param minScore 本次使用的阈值，让前端能解释「为什么只有这几条」
     */
    public record Result(List<Hit> hits, boolean fallback, int computed, int total,
                         double minScore, String model) {}

    /**
     * 向量服务连不上 / 返回垃圾时抛这个，映射到 502（上游不听话，不是我们的 bug）。
     * 和「没配 Key」（503）分开：前者重试可能有用，后者重试一万次也没用。
     */
    public static class SearchFailedException extends RuntimeException {
        public SearchFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 调向量服务，并把两种失败翻译成能看懂的话。
     *
     * <p>抽成一个方法是为了让 {@link #search} 不必声明受检异常——
     * 否则调用链上每一层都要写 throws，而它们其实什么都处理不了。
     */
    private List<float[]> embedOrFail(List<String> texts) {
        try {
            return client.embed(texts);
        } catch (EmbeddingPort.NotConfiguredException e) {
            throw e;
        } catch (IOException e) {
            throw new SearchFailedException("连不上向量服务：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SearchFailedException("向量请求被中断", e);
        }
    }

    public Result search(String query, int limit) {
        if (!client.available()) {
            throw new EmbeddingPort.NotConfiguredException(client.unavailableReason());
        }
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return new Result(List.of(), false, 0, 0, props.embedding().minScore(),
                    props.embedding().model());
        }

        /* ── 第一段：读库。把要用的东西全读出来，然后立刻把连接还回去 ── */

        // search(null,null,null,...) 就是「全部」，和前端列表用的是同一个查询
        List<LinkItem> all = links.search(null, null, null, "recent");
        if (all.isEmpty()) {
            return new Result(List.of(), false, 0, 0, props.embedding().minScore(),
                    props.embedding().model());
        }

        String model = props.embedding().model();
        Map<String, String> textHash = new LinkedHashMap<>();
        Map<String, String> textOf = new LinkedHashMap<>();
        for (LinkItem l : all) {
            String t = searchTextOf(l);
            textOf.put(l.id(), t);
            textHash.put(l.id(), sha256(t));
        }

        // 指纹表不带向量本体，很便宜
        Map<String, String> cached = embeddings.fingerprints(model);

        /*
         * 分成「有效的」和「要重算的」。
         *
         * 判据是 text_hash 相等，而不是「有没有这一行」。这个区别很要紧：
         * 用户改了标题或备注之后，行还在、指纹变了——如果只看「有没有行」，
         * 就会拿旧向量去搜新文本，表现为「刚改完标题，搜新标题搜不到」。
         */
        List<String> stale = new ArrayList<>();
        for (LinkItem l : all) {
            if (!textHash.get(l.id()).equals(cached.get(l.id()))) {
                stale.add(l.id());
            }
        }

        /* ── 第二段：调 API。这一步不持有任何数据库连接 ── */

        float[] queryVec = embedOrFail(List.of(q)).get(0);

        int computed = 0;
        if (!stale.isEmpty()) {
            int batchSize = Math.max(1, props.embedding().batchSize());
            List<EmbeddingRepository.Entry> toWrite = new ArrayList<>();
            for (int i = 0; i < stale.size(); i += batchSize) {
                List<String> chunk = stale.subList(i, Math.min(i + batchSize, stale.size()));
                List<String> texts = chunk.stream().map(textOf::get).toList();
                List<float[]> vecs = embedOrFail(texts);
                for (int j = 0; j < chunk.size(); j++) {
                    toWrite.add(new EmbeddingRepository.Entry(chunk.get(j), vecs.get(j), textHash.get(chunk.get(j))));
                }
            }

            /* ── 第三段：写库 ── */
            embeddings.upsertAll(model, toWrite);
            computed = toWrite.size();
            log.info("语义搜索补齐了 {} 条向量（模型 {}），本次查询「{}」", computed, model, q);
        }

        /* ── 排序。向量全在内存里，暴力算余弦 ── */

        // 刚补算的向量已经落库了，所以这里直接按当前模型全量取一次即可
        Map<String, float[]> vecs = embeddings.vectorsOf(allIds(all), model);

        List<Hit> hits = new ArrayList<>();
        int skipped = 0;
        for (LinkItem l : all) {
            float[] v = vecs.get(l.id());
            if (v == null) {
                skipped++;
                continue;
            }
            if (v.length != queryVec.length) {
                /*
                 * 维度对不上就是不可比。理论上不该发生（存的时候带 model，读的时候按 model 过滤），
                 * 但如果服务商偷偷换了模型而名字没变，这里会第一个发现。
                 * 跳过并记账，比抛异常好——一条坏数据不该让整个搜索不可用。
                 */
                skipped++;
                continue;
            }
            hits.add(new Hit(l.id(), cosine(queryVec, v)));
        }
        if (skipped > 0) {
            log.warn("语义搜索跳过了 {} 条向量不可用的记录（维度不符或写入失败），"
                    + "它们会在下次搜索时重算", skipped);
        }

        double minScore = props.embedding().minScore();
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());

        List<Hit> passed = hits.stream().filter(h -> h.score >= minScore).toList();
        boolean fallback = false;
        if (passed.isEmpty() && !hits.isEmpty()) {
            /*
             * 一条都没过阈值时，硬返回空是语义搜索最典型的失败方式：
             * 用户明明记得存过，搜完一条都不给，于是他得出的结论是「这功能坏了」，
             * 而不是「我搜的词不对」。宁可把最像的三条端出去，并明确标注
             * 「没有很接近的」——让他自己一眼扫过去判断，比给他一片空白强。
             */
            passed = hits.subList(0, Math.min(3, hits.size()));
            fallback = true;
        }

        if (limit > 0 && passed.size() > limit) {
            passed = passed.subList(0, limit);
        }
        return new Result(passed, fallback, computed, all.size(), minScore, model);
    }

    private static List<String> allIds(List<LinkItem> all) {
        return all.stream().map(LinkItem::id).toList();
    }

    /**
     * 一条记录「可被搜到」的文本。
     *
     * <p>拼的是**描述这条记录是什么、以及用户为什么存它**的那几个字段：
     * 标题、摘要、备注、标签、分类、来源。刻意<b>不含正文快照</b>——
     * 正文有八千字，会把标题和备注的信号稀释掉（一句话的查询在一篇长文里
     * 找相似度，结果基本由长文的主题决定，而不是用户存它的理由）。
     * 备注一定要放进去，因为「我打算拿它干嘛」恰恰是用户搜索时最常用的线索。
     *
     * <p><b>改这个拼法会让所有 text_hash 失效、触发全量重算。</b>这是设计如此：
     * 拼法变了，旧向量描述的就不是同一段文字了。但那是一次真实的 API 开销，
     * 所以别为了「好看」去调这里的格式。
     */
    static String searchTextOf(LinkItem l) {
        StringBuilder sb = new StringBuilder(256);
        field(sb, "标题", l.title());
        field(sb, "摘要", l.summary());
        field(sb, "摘要", l.summaryLong());
        field(sb, "备注", l.note());
        field(sb, "标签", l.tags() == null ? "" : String.join(" ", l.tags()));
        field(sb, "分类", Domain.labelOf(l.domainKey()));
        if (l.purposes() != null && !l.purposes().isEmpty()) {
            field(sb, "用途", l.purposes().stream()
                    .map(Purpose::labelOf).collect(Collectors.joining(" ")));
        }
        field(sb, "来源", l.site());
        return sb.toString().trim();
    }

    private static void field(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append(label).append('：').append(value.trim()).append('\n');
    }

    static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("算不出 SHA-256", e);
        }
    }

    /**
     * 余弦相似度。
     *
     * <p>有些服务（包括 bge 系列）返回的向量已经归一化过，那时点积就等于余弦。
     * 但**不能依赖这一点**：归一化是实现细节，不是协议保证，
     * 而一旦某个服务返回未归一化的向量，用点积会把「长向量」全部排在前面，
     * 搜索结果会变成「哪条记录的文本最长」——错得毫无提示。
     */
    static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 给 /api/meta 用：前端据此决定语义按钮是能点还是该说明原因。 */
    public EmbeddingPort client() {
        return client;
    }
}

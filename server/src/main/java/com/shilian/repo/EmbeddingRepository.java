package com.shilian.repo;

import com.shilian.repo.entity.LinkEmbeddingEntity;
import com.shilian.repo.mapper.LinkEmbeddingMapper;
import com.shilian.util.RelativeTime;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 向量缓存仓储。
 *
 * <p>刻意把「读指纹」和「读向量」拆成两个方法。指纹是每行几十字节，向量是每行几 KB
 * （1024 维 × 4 字节），而搜索时**绝大多数行的向量是不用读的**——
 * 只有文本没变的那些才有效。先读指纹判断哪些有效，再去读那几个向量，
 * 比每次都把十几 MB 的 blob 全捞上来划算得多。
 *
 * <p>数据访问走 MyBatis-Plus（2026-09-18 迁）。{@code float[] ↔ MEDIUMBLOB}
 * 的转换从原来的私有静态方法搬到了 {@code FloatArrayTypeHandler} ——
 * 那是 MyBatis 的 TypeHandler 该干的事，放在仓储里的话，任何一条
 * 手写 SQL 都得记得调它。
 */
@Repository
public class EmbeddingRepository {

    private final LinkEmbeddingMapper embeddings;

    public EmbeddingRepository(LinkEmbeddingMapper embeddings) {
        this.embeddings = embeddings;
    }

    /**
     * {@code link_id → 上次算向量时用的文本指纹}。不含向量本体。
     *
     * <p>带上 model 条件：换模型之后旧行虽然还在库里，但**不该被当成有效的**，
     * 所以查询时直接按模型过滤，让它们自然落进「需要重算」那一类。
     * 旧行留着不删，万一以后想换回去还能省一次调用。
     */
    public Map<String, String> fingerprints(String model) {
        return embeddings.selectFingerprints(model).stream()
                .collect(java.util.stream.Collectors.toMap(
                        LinkEmbeddingEntity::getLinkId,
                        LinkEmbeddingEntity::getTextHash,
                        (a, b) -> a));
    }

    /** 只取指定几条的向量。空集合直接返回，不拼一个 {@code IN ()} 出来当语法错。 */
    public Map<String, float[]> vectorsOf(Collection<String> linkIds, String model) {
        return embeddings.vectorsOf(linkIds, model);
    }

    /**
     * 写一条向量（有则覆盖）。
     *
     * <p>语义见 {@code LinkEmbeddingMapper.upsert}：{@code ON DUPLICATE KEY UPDATE}
     * 是「任何一个唯一键冲突都走更新」，当前这张表只有一个唯一键所以等价于
     * 原来的 {@code ON CONFLICT(link_id)}。
     */
    public void upsert(String linkId, String model, float[] vec, String textHash) {
        embeddings.upsert(linkId, model, vec.length, vec, textHash, RelativeTime.now());
    }

    /** 一批一次写完，减少往返。 */
    public void upsertAll(String model, List<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        String now = RelativeTime.now();
        List<LinkEmbeddingEntity> rows = entries.stream().map(e -> {
            LinkEmbeddingEntity row = new LinkEmbeddingEntity();
            row.setLinkId(e.linkId());
            row.setModel(model);
            row.setDim(e.vec().length);
            row.setVec(e.vec());
            row.setTextHash(e.textHash());
            row.setUpdatedAt(now);
            return row;
        }).toList();
        embeddings.upsertAll(model, rows, now);
    }

    public record Entry(String linkId, float[] vec, String textHash) {}

    public int count() {
        return embeddings.count();
    }

    /** 删掉全部向量。正常情况靠外键级联，这个方法是给换模型时清场用的。 */
    public int deleteAll() {
        return embeddings.deleteAll();
    }
}

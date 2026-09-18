package com.shilian.repo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shilian.repo.entity.LinkEmbeddingEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code link_embedding} 的 Mapper：向量缓存。
 *
 * <p>继承 {@code BaseMapper} 只用它两件事：{@code selectCount} 和
 * {@code delete}。{@code selectById(linkId)} 在这张表上是成立的
 * （主键就是 {@code link_id}，单列），但当前没有场景用到。
 */
@Mapper
public interface LinkEmbeddingMapper extends BaseMapper<LinkEmbeddingEntity> {

    /**
     * {@code link_id → 上次算向量时用的文本指纹}。不含向量本体。
     *
     * <p>刻意只 SELECT 两列：指纹是每行几十字节，向量是每行几 KB
     * （1024 维 × 4 字节）。而搜索时<b>绝大多数行的向量是不用读的</b>——
     * 只有文本没变的那些才有效。先读指纹判断哪些有效，再去读那几个向量，
     * 比每次都把十几 MB 的 blob 全捞上来划算得多。这个拆分是从原来的
     * {@code EmbeddingRepository} 原样保留的，别为了「一个方法查全」合并掉。
     *
     * <p>带上 {@code model} 条件：换模型之后旧行虽然还在库里，但<b>不该被当成
     * 有效的</b>，所以查询时直接按模型过滤，让它们自然落进「需要重算」那一类。
     * 旧行留着不删，万一以后想换回去还能省一次调用。
     */
    @Select("SELECT link_id, text_hash FROM link_embedding WHERE model = #{model}")
    List<LinkEmbeddingEntity> selectFingerprints(@Param("model") String model);

    /**
     * 只取指定几条的向量。只 SELECT 两列，理由同上。
     *
     * <p>这个方法不要直接调 —— 用下面的 {@link #vectorsOf}，
     * 它会挡住空集合。原因：{@code <foreach>} 遇到空集合会生成 {@code IN ()}，
     * 那是 SQL 语法错误。
     */
    @Select("<script>SELECT link_id, vec FROM link_embedding "
            + "WHERE model = #{model} AND link_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<LinkEmbeddingEntity> selectVectors(@Param("model") String model,
                                            @Param("ids") Collection<String> ids);

    /** 空集合直接返回空 Map，不拼一个 {@code IN ()} 出来当语法错。 */
    default Map<String, float[]> vectorsOf(Collection<String> linkIds, String model) {
        if (linkIds == null || linkIds.isEmpty()) {
            return Map.of();
        }
        Map<String, float[]> out = new HashMap<>();
        for (LinkEmbeddingEntity e : selectVectors(model, linkIds)) {
            out.put(e.getLinkId(), e.getVec());
        }
        return out;
    }

    /**
     * 写一条向量（有则覆盖）。
     *
     * <p>不用 {@code insertOrUpdate}：那个是「先 selectById 再决定 insert 还是
     * updateById」，两次往返且不是原子的。{@code ON DUPLICATE KEY UPDATE}
     * 一条语句搞定。
     *
     * <p>这里原来是 SQLite 的 {@code ON CONFLICT(link_id) DO UPDATE SET x = excluded.x}。
     * 两个写法的<b>语义有细微差别，要留意</b>：{@code ON CONFLICT(link_id)}
     * 明确指定了「按哪个唯一键判断冲突」，而 {@code ON DUPLICATE KEY UPDATE}
     * 是「任何一个唯一键冲突都走这条更新」。当前 {@code link_embedding} 上只有
     * 主键 {@code link_id} 一个唯一键，所以两者等价。
     * <b>以后要是加了第二个唯一索引，这里的语义就会变。</b>
     */
    @Insert("INSERT INTO link_embedding (link_id, model, dim, vec, text_hash, updated_at) "
            + "VALUES (#{linkId}, #{model}, #{dim}, "
            + "#{vec, typeHandler=com.shilian.repo.handler.FloatArrayTypeHandler}, "
            + "#{textHash}, #{updatedAt}) "
            + "ON DUPLICATE KEY UPDATE model = VALUES(model), dim = VALUES(dim), "
            + "vec = VALUES(vec), text_hash = VALUES(text_hash), updated_at = VALUES(updated_at)")
    int upsert(@Param("linkId") String linkId,
               @Param("model") String model,
               @Param("dim") int dim,
               @Param("vec") float[] vec,
               @Param("textHash") String textHash,
               @Param("updatedAt") String updatedAt);

    /**
     * 一批一次写完，减少往返。
     *
     * <p>用一条多值 {@code INSERT} 而不是循环调 {@link #upsert}：
     * 补齐历史向量时一次几十条，循环就是几十个来回。
     * 这也和连接串里 {@code rewriteBatchedStatements=true} 的意图一致
     * （那条参数是给 JDBC 批处理用的，而这里直接就是一条语句，更省）。
     */
    @Insert("<script>"
            + "INSERT INTO link_embedding (link_id, model, dim, vec, text_hash, updated_at) VALUES "
            + "<foreach collection='entries' item='e' separator=','>"
            + "(#{e.linkId}, #{model}, #{e.dim}, "
            + "#{e.vec, typeHandler=com.shilian.repo.handler.FloatArrayTypeHandler}, "
            + "#{e.textHash}, #{updatedAt})"
            + "</foreach>"
            + " ON DUPLICATE KEY UPDATE model = VALUES(model), dim = VALUES(dim), "
            + "vec = VALUES(vec), text_hash = VALUES(text_hash), updated_at = VALUES(updated_at)"
            + "</script>")
    int upsertAll(@Param("model") String model,
                  @Param("entries") List<LinkEmbeddingEntity> entries,
                  @Param("updatedAt") String updatedAt);

    /**
     * 清空整表。给换模型时清场用。
     *
     * <p>写成显式 SQL 而不是 {@code delete(null)} ——
     * 后者虽然也能删全表，但「传个 null 就把表删了」这件事在代码审查里
     * 看着像 bug，不如把它说清楚。
     */
    @Delete("DELETE FROM link_embedding")
    int deleteAll();

    default int count() {
        return Math.toIntExact(selectCount(null));
    }
}

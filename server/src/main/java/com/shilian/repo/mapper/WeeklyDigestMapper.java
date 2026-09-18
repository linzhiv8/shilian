package com.shilian.repo.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code weekly_digest} 的 Mapper。
 *
 * <p><b>⚠ 这里刻意不继承 {@code BaseMapper}。</b>
 * 这张表的主键是复合的 {@code (user_id, week_key)}，而 {@code BaseMapper<T>}
 * 只认单个 {@code @TableId}，它的 {@code selectById / updateById / deleteById}
 * 只会按 {@code user_id} 定位 —— 那会命中该用户的<b>任意一周</b>，而且不报错。
 * 既然那些方法在这里全是错的，就不要让它们出现在类型上。
 */
@Mapper
public interface WeeklyDigestMapper {

    /** 读缓存的 AI 摘要。没有就返回 null。 */
    @Select("SELECT summary FROM weekly_digest WHERE user_id = #{uid} AND week_key = #{weekKey}")
    String findSummary(@Param("uid") String uid, @Param("weekKey") String weekKey);

    /**
     * 存 AI 摘要（有则覆盖）。
     *
     * <p>用 {@code ON DUPLICATE KEY UPDATE} 而不是「先查再决定插还是更」：
     * 同一周内重跑（换了模型、或想重写一次）直接覆盖，少一次往返。
     *
     * <p>这里原来是 SQLite 的 {@code INSERT OR REPLACE}。换写法时踩到过一个区别：
     * {@code INSERT OR REPLACE} 是<b>先删后插</b>，没写到的列会被重置成默认值；
     * 而 {@code ON DUPLICATE KEY UPDATE} 是<b>就地更新</b>，只动列出来的列。
     * 下面把 5 个非主键列全列出来了，所以当前两者等价 ——
     * <b>以后加新列而忘了加进这里，两种写法的表现就不一样了。</b>
     *
     * <p>值用 {@code VALUES(col)} 而不是 MySQL 8.0.19 之后推荐的别名写法
     * （{@code AS new ... = new.col}）：别名写法要求 8.0.19+，
     * 而 {@code VALUES()} 在所有 8.x 上都能用（只是新版把它标记成待废弃）。
     * 这个工程没有规定 MySQL 的小版本，所以挑兼容面更宽的那个。
     */
    @Insert("INSERT INTO weekly_digest "
            + "(user_id, week_key, summary, model, prompt_tokens, completion_tokens, created_at) "
            + "VALUES (#{uid}, #{weekKey}, #{summary}, #{model}, #{promptTokens}, "
            + "#{completionTokens}, #{createdAt}) "
            + "ON DUPLICATE KEY UPDATE summary = VALUES(summary), model = VALUES(model), "
            + "prompt_tokens = VALUES(prompt_tokens), "
            + "completion_tokens = VALUES(completion_tokens), created_at = VALUES(created_at)")
    int saveSummary(@Param("uid") String uid,
                    @Param("weekKey") String weekKey,
                    @Param("summary") String summary,
                    @Param("model") String model,
                    @Param("promptTokens") int promptTokens,
                    @Param("completionTokens") int completionTokens,
                    @Param("createdAt") String createdAt);

    /** 清掉某一周的缓存，让下次打开重写。 */
    @Delete("DELETE FROM weekly_digest WHERE user_id = #{uid} AND week_key = #{weekKey}")
    int clear(@Param("uid") String uid, @Param("weekKey") String weekKey);

    /**
     * 把没有主人的历史周报划给某个用户。
     *
     * <p>条件是 {@code user_id = ''} 而不是 {@code IS NULL}，这是全项目唯一一处 ——
     * 因为 {@code user_id} 是这张表主键的一半，而主键列在 MySQL 里不能为 NULL，
     * 所以「还没有归属」用空串表示（见 DDL 里的注释）。
     */
    @Update("UPDATE weekly_digest SET user_id = #{userId} WHERE user_id = ''")
    int claimOrphans(@Param("userId") String userId);
}

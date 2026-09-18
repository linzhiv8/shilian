package com.shilian.repo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shilian.repo.entity.AiLogEntity;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * {@code ai_log} 的 Mapper。
 *
 * <p>这张表只需要一个 {@code insert}（自增主键、只插不改），
 * 而 {@code BaseMapper.insert} 就是它 —— 这里一个自定义方法都不用写。
 * 迁移前这段是手写的 {@code INSERT INTO ai_log (...) VALUES (?,?,...)} 加九个
 * 位置参数，加一列就要数一遍问号。
 */
@Mapper
public interface AiLogMapper extends BaseMapper<AiLogEntity> {

    /** 把没有主人的历史日志划给某个用户。只在「第一个账号」时调。 */
    @Update("UPDATE ai_log SET user_id = #{userId} WHERE user_id IS NULL")
    int claimOrphans(@Param("userId") String userId);

    /**
     * 每个用户各花了多少（R-15 → R-19）。
     *
     * <p>一次 GROUP BY 拿全量，而不是给页面上那二十个用户各查一次：
     * {@code ai_log} 的行数等于「AI 被调用过多少次」，个人库是几百到几千，
     * 全量聚合一次是毫秒级；而 N+1 次查询会随着用户数线性变慢。
     *
     * <p>{@code COALESCE} 不能省：token 列允许为 NULL（早期某些记录没记到），
     * 少了它，一个用户的 SUM 会变成 NULL，加起来就整段变成 null——
     * 表现为「这个人明明用过，调用次数却是 0」。
     */
    @Select("SELECT user_id AS uid, COUNT(*) AS calls, "
            + "COALESCE(SUM(prompt_tokens), 0) AS pt, "
            + "COALESCE(SUM(completion_tokens), 0) AS ct "
            + "FROM ai_log WHERE user_id IS NOT NULL GROUP BY user_id")
    @ConstructorArgs({
            @Arg(column = "uid", javaType = String.class),
            @Arg(column = "calls", javaType = long.class),
            @Arg(column = "pt", javaType = long.class),
            @Arg(column = "ct", javaType = long.class)
    })
    List<AiUsageRow> usageByUser();

    /** {@link #usageByUser} 的一行：某个用户的调用次数与 token 合计。 */
    record AiUsageRow(String userId, long calls, long promptTokens, long completionTokens) {}
}

package com.shilian.repo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shilian.repo.entity.AuditLogEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * {@code audit_log} 的 Mapper。
 *
 * <p><b>这张表不需要按 user_id 隔离</b>，这是它和 {@code LinkMapper} 最根本的区别。
 * {@code link} 表里每一行都属于某个用户，漏掉 {@code user_id} 条件就是数据泄露；
 * 而审计记录本来就只有一个读者——管理员，他要看到的正是「所有人做了什么」。
 * 所以这里用 {@code BaseMapper.insert} 和普通的条件查询，不需要那套手写 SQL 的纪律。
 *
 * <p>把这条区别写在这里是必要的：下一个给这张表加查询的人，很容易照着
 * {@code LinkMapper} 的样子补一个 {@code AND user_id = #{uid}}，
 * 而那会让管理端永远只能看到自己的记录——审计变成只能审自己。
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLogEntity> {

    /**
     * 按用户 / 动作 / 结果查一页审计记录。
     *
     * <p>三个条件都可为 null，表示不筛——管理端顶部就是「全部」。
     *
     * <p><b>为什么用 {@code <script>} 动态拼而不是写多条 SQL。</b>
     * 三个可选条件组合出八种查询，写死就是八段几乎一样的 SQL，
     * 将来加第四个条件要变成十六段。动态拼的代价是 XML 标签混在注解里不好看，
     * 换来的是「条件永远只有一份」——加条件只改一处，不会出现
     * 「列表筛了、总数没筛」这种分页越翻越乱的错。
     *
     * <p><b>排序用 {@code created_at DESC, id DESC} 而不是单列。</b>
     * {@code created_at} 是秒级字符串，同一秒内发生的登录和删链接
     * 只按时间排的话顺序是未定义的——表现是「刷新一下前后两条换了位置」。
     * 加上自增 id 兜底，同一秒内的顺序也稳定。
     */
    @Select("<script>"
            + "SELECT * FROM audit_log "
            + "<where>"
            + "  <if test='userId != null and userId != \"\"'>AND user_id = #{userId}</if>"
            + "  <if test='action != null and action != \"\"'>AND action = #{action}</if>"
            + "  <if test='result != null and result != \"\"'>AND result = #{result}</if>"
            + "</where>"
            + "ORDER BY created_at DESC, id DESC "
            + "LIMIT #{limit} OFFSET #{offset}"
            + "</script>")
    List<AuditLogEntity> searchPage(@Param("userId") String userId,
                                    @Param("action") String action,
                                    @Param("result") String result,
                                    @Param("limit") int limit,
                                    @Param("offset") int offset);

    /** 同上条件的总数。条件刻意和 {@link #searchPage} 一一对应，别在两处各写一遍。 */
    @Select("<script>"
            + "SELECT COUNT(*) FROM audit_log "
            + "<where>"
            + "  <if test='userId != null and userId != \"\"'>AND user_id = #{userId}</if>"
            + "  <if test='action != null and action != \"\"'>AND action = #{action}</if>"
            + "  <if test='result != null and result != \"\"'>AND result = #{result}</if>"
            + "</where>"
            + "</script>")
    int countBy(@Param("userId") String userId,
                @Param("action") String action,
                @Param("result") String result);
}

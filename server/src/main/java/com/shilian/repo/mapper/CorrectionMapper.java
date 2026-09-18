package com.shilian.repo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shilian.repo.entity.CorrectionEntity;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * {@code correction} 的 Mapper：AI 判成什么 → 用户改成什么。
 */
@Mapper
public interface CorrectionMapper extends BaseMapper<CorrectionEntity> {

    /**
     * 一条修正汇总。给提示词做 few-shot 用。
     *
     * <p>只取 {@code domain_category} 和 {@code purpose_categories} 两类：
     * 它们是「分类判断」，纠正信号最干净。标题和摘要虽然也记了 correction，
     * 但那属于表达风格的差异，混进来会让模型学到「这个用户喜欢短标题」
     * 这种和分类无关的偏好。
     */
    record CorrectionStat(String field, String aiValue, String userValue, int count) {}

    /**
     * 这里只给数据，<b>不拼提示词</b> —— 拼装是 {@code CorrectionsFeed} 的事。
     * 仓储不该知道提示词长什么样。
     *
     * <p><b>{@code @ConstructorArgs} 不能省。</b> record 没有无参构造器，
     * 而 MyBatis 在结果映射时默认会去调一个无参构造器再逐个 set 属性 ——
     * 对 record 来说两条路都不通（字段是 final，没有 setter）。
     * 所以必须显式告诉它「用哪个构造器、第几个参数接哪一列」。
     * 少了这个注解的报错是
     * {@code Cannot construct instance of ... (no Creators, like default constructor, exist)}
     * 或者直接 {@code ExecutorException}，而它不会提示你「是因为用了 record」。
     */
    @Select("SELECT field_name, ai_value, user_value, COUNT(*) AS c "
            + "FROM correction WHERE user_id = #{uid} "
            + "AND field_name IN ('domain_category','purpose_categories') "
            + "GROUP BY field_name, ai_value, user_value "
            + "ORDER BY c DESC, MAX(created_at) DESC LIMIT #{limit}")
    @ConstructorArgs({
            @Arg(column = "field_name", javaType = String.class),
            @Arg(column = "ai_value", javaType = String.class),
            @Arg(column = "user_value", javaType = String.class),
            @Arg(column = "c", javaType = int.class)
    })
    List<CorrectionStat> correctionStats(@Param("uid") String uid, @Param("limit") int limit);

    /** 把没有主人的历史修正划给某个用户。只在「第一个账号」时调。 */
    @Update("UPDATE correction SET user_id = #{userId} WHERE user_id IS NULL")
    int claimOrphans(@Param("userId") String userId);
}

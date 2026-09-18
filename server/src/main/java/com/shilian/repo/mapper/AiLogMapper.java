package com.shilian.repo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shilian.repo.entity.AiLogEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

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
}

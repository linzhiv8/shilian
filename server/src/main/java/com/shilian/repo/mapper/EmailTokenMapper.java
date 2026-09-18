package com.shilian.repo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shilian.repo.entity.EmailTokenEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code email_token} 的 Mapper。
 *
 * <p><b>这里所有查询都以 {@code token_hash} 而非令牌明文为条件。</b>
 * 存哈希的那个决定只有在「查也按哈希查」时才成立——
 * 只要有一处按明文查，就等于白存了哈希。
 *
 * <p>不做「按 user_id 隔离」：和 {@code AuditLogMapper} 同理，
 * 这张表的读者是流程本身，不是「某个用户在看自己的东西」。
 * 但这不等于可以随便查——每次查询都必须带上 {@code purpose}，
 * 免得「找回密码」的令牌被拿去当「验证邮箱」的令牌用
 * （两种操作的后果完全不同，混用就是权限提升）。
 */
@Mapper
public interface EmailTokenMapper extends BaseMapper<EmailTokenEntity> {

    /**
     * 取一条还没用过、还没过期的令牌。
     *
     * <p><b>{@code used_at IS NULL} 必须写在这里，不能在 Java 里判。</b>
     * 「用后即废」如果被放到 Java 侧，并发点两次链接就会两次都通过——
     * 两个请求都读到 {@code used_at == null}，然后都去改密码。
     * 写在 SQL 里至少让「读」和「标记已用」挨在一起，且判定只有一份。
     */
    @Select("SELECT * FROM email_token "
            + "WHERE token_hash = #{tokenHash} AND purpose = #{purpose} "
            + "AND used_at IS NULL AND expires_at > #{now} "
            + "LIMIT 1")
    EmailTokenEntity findUsable(@Param("tokenHash") String tokenHash,
                                @Param("purpose") String purpose,
                                @Param("now") String now);

    /**
     * 标记一条令牌已用。
     *
     * <p>条件里带 {@code used_at IS NULL}，让「标记」这个动作本身是原子的：
     * 返回 0 就说明这一瞬间它已经被别人用掉了，调用方据此拒绝第二次。
     */
    @Update("UPDATE email_token SET used_at = #{now} "
            + "WHERE id = #{id} AND used_at IS NULL")
    int markUsed(@Param("id") Long id, @Param("now") String now);

    /**
     * 把某个用户某一用途下所有未用的令牌一次性作废。
     *
     * <p><b>为什么换密码成功后要这么一下。</b>
     * 用户的收件箱里可能躺着好几封重置邮件（他点了两次、或者点了旧的那一封）。
     * 只作废刚用掉的那一条，剩下的链接在接下来半小时里仍然能改他的密码——
     * 「我明明已经改好了，怎么又被改了」就是这么来的。
     */
    @Update("UPDATE email_token SET used_at = #{now} "
            + "WHERE user_id = #{userId} AND purpose = #{purpose} AND used_at IS NULL")
    int invalidateAll(@Param("userId") String userId,
                      @Param("purpose") String purpose,
                      @Param("now") String now);
}

package com.shilian.repo.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shilian.repo.entity.AppUserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * {@code app_user} 的 Mapper。
 *
 * <p><b>这张表是 MyBatis-Plus 真正省事的地方</b>：它是标准的单主键表，
 * 查询条件简单，没有「按当前用户过滤」这类必须手写的东西。
 * 所以这里大量使用 {@code BaseMapper} 的现成方法和 {@code LambdaQueryWrapper}，
 * 而不是像 {@code LinkMapper} 那样句句手写 SQL。
 *
 * <p>对比一下就能看出分界线：<b>有数据隔离要求的表（link）必须手写 SQL
 * 让 {@code user_id} 条件留在 {@code WHERE} 里肉眼可见；没有隔离要求的表
 * （app_user，它本身就是用户表）可以放心用 Wrapper。</b>
 * 这不是风格偏好，是这两张表的安全属性不同。
 */
@Mapper
public interface AppUserMapper extends BaseMapper<AppUserEntity> {

    /**
     * 按用户名查。
     *
     * <p>{@code username} 上有唯一索引 {@code uk_app_user_username}，
     * 所以最多一行 —— {@code selectOne} 不会抛
     * {@code TooManyResultsException}。
     */
    default AppUserEntity findByUsername(String username) {
        return selectOne(new LambdaQueryWrapper<AppUserEntity>()
                .eq(AppUserEntity::getUsername, username));
    }

    /**
     * 登录时按「用户名或邮箱」查。
     *
     * <p>只支持这两个字段，别改成任意字段匹配 —— 那会多出一个
     * 可以被用来探测账号的入口。
     */
    default AppUserEntity findByUsernameOrEmail(String identifier) {
        return selectOne(new LambdaQueryWrapper<AppUserEntity>()
                .eq(AppUserEntity::getUsername, identifier)
                .or()
                .eq(AppUserEntity::getEmail, identifier));
    }

    default boolean existsByUsername(String username) {
        return exists(new LambdaQueryWrapper<AppUserEntity>()
                .eq(AppUserEntity::getUsername, username));
    }

    default boolean existsByEmail(String email) {
        return exists(new LambdaQueryWrapper<AppUserEntity>()
                .eq(AppUserEntity::getEmail, email));
    }

    default int countAll() {
        return Math.toIntExact(selectCount(null));
    }

    default List<AppUserEntity> findAll() {
        return selectList(new LambdaQueryWrapper<AppUserEntity>()
                .orderByDesc(AppUserEntity::getCreatedAt));
    }

    /**
     * 管理端的用户搜索。
     *
     * <p><b>只能用 Wrapper，不能用 {@code LIKE} 拼字符串。</b>
     * 关键词是用户输入的，直接拼进 SQL 就是一个注入点；
     * {@code apply()} 会把参数转成占位符，通配符由下面的
     * {@link #like} 转义掉（不转义的话，搜 {@code 100%} 会变成「以 100 开头」）。
     *
     * <p><b>{@code LIMIT / OFFSET} 直接拼数字而不是占位符</b>：这两个值是
     * {@code int}，调用方已经夹过上下限，拼进去没有注入风险；
     * 而 MySQL 对 {@code LIMIT ?} 的支持在某些版本上有坑，不如直接拼。
     *
     * @param keyword 邮箱 / 用户名 / 昵称的模糊匹配，null 或空表示不筛
     */
    default List<AppUserEntity> searchPage(String keyword, int offset, int limit) {
        LambdaQueryWrapper<AppUserEntity> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String like = like(keyword);
            w.and(q -> q
                    .apply("username LIKE {0} ESCAPE '!'", like)
                    .or().apply("nickname LIKE {0} ESCAPE '!'", like)
                    .or().apply("email LIKE {0} ESCAPE '!'", like));
        }
        w.orderByDesc(AppUserEntity::getCreatedAt);
        w.last("LIMIT " + limit + " OFFSET " + offset);
        return selectList(w);
    }

    /** 同上条件的总数。和 {@link #searchPage} 共用同一套条件，别在两处各写一遍。 */
    default int countByKeyword(String keyword) {
        LambdaQueryWrapper<AppUserEntity> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String like = like(keyword);
            w.and(q -> q
                    .apply("username LIKE {0} ESCAPE '!'", like)
                    .or().apply("nickname LIKE {0} ESCAPE '!'", like)
                    .or().apply("email LIKE {0} ESCAPE '!'", like));
        }
        return Math.toIntExact(selectCount(w));
    }

    /**
     * 把用户输入包成 LIKE 模式，并转义掉它自己带的通配符。
     *
     * <p>转义符用 {@code !} 而不是默认的反斜杠，理由见
     * {@code LinkRepository.escapeLike}——那三层数反斜杠的坑这里一样存在。
     */
    private static String like(String keyword) {
        String escaped = keyword.trim()
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }

    /**
     * 禁用 / 恢复账号。
     *
     * <p>写成单列 UPDATE 而不是 {@code updateById} 传实体：
     * 后者「塞什么字段就改什么字段」，而这里要表达的语义是
     * 「只改状态这一个字段」——用户表上其他列（密码哈希、角色、失败计数）
     * 都不该被这次操作碰到。
     */
    @Update("UPDATE app_user SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") String id, @Param("status") String status);

    /**
     * 记一次登录失败。
     *
     * <p><b>计数必须在 SQL 里自增</b>，不能「读出来 +1 再写回」：
     * 后者在并发登录下会丢更新，表现为「连错十次也没锁住」。
     * 这也是这里唯一一处非用 {@code @Update} 不可的地方 ——
     * {@code updateById} 传进去的是绝对值，表达不了「在原值上加一」。
     */
    @Update("UPDATE app_user SET failed_attempts = failed_attempts + 1 WHERE id = #{id}")
    int incrementFailedAttempts(@Param("id") String id);

    @Select("SELECT failed_attempts FROM app_user WHERE id = #{id}")
    Integer readFailedAttempts(@Param("id") String id);

    @Update("UPDATE app_user SET locked_until = #{until} WHERE id = #{id}")
    int lockUntil(@Param("id") String id, @Param("until") String until);

    /**
     * 登录成功：清零失败计数、解掉锁定、记下登录时间。
     *
     * <p>三列一次改完。{@code locked_until = NULL} 是刻意的解绑，
     * 所以这里也不能用 {@code updateById} —— 那个策略会跳过 null 字段，
     * 表达不了「把这个字段清成 NULL」。
     */
    @Update("UPDATE app_user SET failed_attempts = 0, locked_until = NULL, last_login_at = #{at} "
            + "WHERE id = #{id}")
    int recordSuccess(@Param("id") String id, @Param("at") String at);

    /**
     * 改密码。只更新哈希这一列。
     *
     * <p>刻意<b>不</b>顺手清掉 {@code failed_attempts / locked_until}：
     * 那会变成「被锁住了？改个密码就解锁」的绕行路线。
     *
     * <p>调用方用 {@code updateById} 传一个只填了 id 和 passwordHash 的实体即可
     * —— MyBatis-Plus 默认的更新策略会跳过 null 字段，生成的 SQL
     * 只有 {@code SET password_hash = ?}。这条注释是给下一个人的提醒：
     * <b>别往那个实体里多塞字段，塞什么就会更新什么。</b>
     */
    @Update("UPDATE app_user SET password_hash = #{hash} WHERE id = #{id}")
    int updatePasswordHash(@Param("id") String id, @Param("hash") String hash);
}

package com.shilian.repo;

import com.shilian.domain.port.Clock;
import com.shilian.domain.user.User;
import com.shilian.repo.entity.AppUserEntity;
import com.shilian.repo.mapper.AppUserMapper;
import com.shilian.util.RelativeTime;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 用户仓储。
 *
 * <p>登录失败的计数和锁定时点<b>存在库里而不是内存</b>：
 * 内存方案一重启就清零，等于给正在撞库的人一个「重启即解锁」的口子。
 * 而且多用户产品里，按人计数本来就该是持久状态。
 *
 * <p>数据访问走 MyBatis-Plus（2026-09-18 迁）。这张表没有「按当前用户过滤」
 * 的要求，所以 {@code AppUserMapper} 那边可以放心用 {@code LambdaQueryWrapper}，
 * 不像 {@code LinkMapper} 必须句句手写 SQL —— 两张表的安全属性不同。
 */
@Repository
public class UserRepository {

    private final AppUserMapper users;
    private final Clock clock;

    public UserRepository(AppUserMapper users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    public record NewUser(String username, String email, String passwordHash, String nickname) {}

    public Optional<User> findById(String id) {
        return Optional.ofNullable(users.selectById(id)).map(UserRepository::toUser);
    }

    public Optional<User> findByUsername(String username) {
        return Optional.ofNullable(users.findByUsername(username)).map(UserRepository::toUser);
    }

    /**
     * 登录时按「用户名或邮箱」查。
     *
     * <p>只支持这两个字段，别做成任意字段匹配——
     * 那会多出一个可以被用来探测账号的入口。
     */
    public Optional<User> findByUsernameOrEmail(String identifier) {
        return Optional.ofNullable(users.findByUsernameOrEmail(identifier)).map(UserRepository::toUser);
    }

    public boolean existsByUsername(String username) {
        return users.existsByUsername(username);
    }

    public boolean existsByEmail(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        return users.existsByEmail(email);
    }

    public User insert(NewUser in) {
        String id = newId();
        AppUserEntity e = new AppUserEntity();
        e.setId(id);
        e.setUsername(in.username());
        e.setEmail(in.email());
        e.setPasswordHash(in.passwordHash());
        e.setNickname(in.nickname());
        e.setStatus(User.STATUS_ACTIVE);
        e.setFailedAttempts(0);
        e.setCreatedAt(RelativeTime.format(clock.now()));

        users.insert(e);
        return findById(id).orElseThrow(() -> new IllegalStateException("刚写入的用户读不出来：" + id));
    }

    /**
     * 记一次登录失败，够了就锁上。
     *
     * <p>计数在 SQL 里自增而不是「读出来 +1 再写回」：
     * 后者在并发登录下会丢更新，表现为「连错十次也没锁住」。
     */
    public void recordFailure(String userId, int maxAttempts, long lockMinutes) {
        users.incrementFailedAttempts(userId);

        Integer attempts = users.readFailedAttempts(userId);
        if (attempts != null && attempts >= maxAttempts) {
            users.lockUntil(userId, RelativeTime.format(clock.now().plusMinutes(lockMinutes)));
        }
    }

    /** 登录成功：清零失败计数、解掉锁定、记下登录时间。 */
    public void recordSuccess(String userId) {
        users.recordSuccess(userId, RelativeTime.format(clock.now()));
    }

    /**
     * 改密码。
     *
     * <p>只更新哈希这一列，不动 failed_attempts / locked_until。
     * 改密码是本人操作成功之后的事，没理由顺手把锁定状态也清掉——
     * 那会变成「被锁住了？改个密码就解锁」的绕行路线。
     */
    public void updatePasswordHash(String userId, String newHash) {
        users.updatePasswordHash(userId, newHash);
    }

    public int countAll() {
        return users.countAll();
    }

    public List<User> findAll() {
        return users.findAll().stream().map(UserRepository::toUser).toList();
    }

    /* ────────────── 管理端 ────────────── */

    /**
     * 管理端的用户分页。
     *
     * <p>这个查询<b>不带用户过滤</b>，和 {@code LinkRepository} 里那些
     * 「必须带 user_id」的查询不是一类：{@code app_user} 本身就是用户表，
     * 「看所有用户」正是管理端要的。权限由调用方（{@code AdminController}）
     * 先把住——不是靠这条 SQL 自己。
     */
    public List<User> searchPage(String keyword, int offset, int limit) {
        return users.searchPage(keyword, offset, limit).stream().map(UserRepository::toUser).toList();
    }

    /** {@link #searchPage} 同一个条件下的总数。 */
    public int countByKeyword(String keyword) {
        return users.countByKeyword(keyword);
    }

    /**
     * 禁用 / 恢复账号。
     *
     * @return 实际改动的行数。0 表示没有这个 id——由调用方翻译成 404。
     */
    public int updateStatus(String userId, String status) {
        return users.updateStatus(userId, status);
    }

    /** 供 ApplicationRunner 用：判断要不要初始化首个账号。 */
    public boolean isEmpty() {
        return countAll() == 0;
    }

    /**
     * 实体 → 领域对象。整个工程只有这一处做这个转换。
     *
     * <p>{@code failedAttempts} 在实体里是 {@code Integer}（列可以为 NULL），
     * 在领域对象里是 {@code int}，所以这里兜一下 0 —— 和迁移前
     * {@code rs.getInt("failed_attempts")} 对 NULL 返回 0 的行为一致。
     */
    private static User toUser(AppUserEntity e) {
        return new User(
                e.getId(),
                e.getUsername(),
                e.getEmail(),
                e.getPasswordHash(),
                e.getNickname(),
                e.getStatus(),
                e.getFailedAttempts() == null ? 0 : e.getFailedAttempts(),
                e.getLockedUntil(),
                e.getCreatedAt(),
                e.getLastLoginAt(),
                e.getRole());
    }

    /** 12 位十六进制，和 link 的 id 同一套格式。 */
    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}

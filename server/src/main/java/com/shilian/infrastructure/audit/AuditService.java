package com.shilian.infrastructure.audit;

import com.shilian.domain.port.Clock;
import com.shilian.repo.UserRepository;
import com.shilian.repo.entity.AuditLogEntity;
import com.shilian.repo.mapper.AuditLogMapper;
import com.shilian.util.RelativeTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 审计日志：记录「谁在什么时候做了什么」，并给管理端查。
 *
 * <p><b>七个动作取值就定在这里，改一处等于改全部。</b>
 * 前端按这些值做中文映射，所以<b>取值和拼写都不能动</b>——
 * 多加一个值前端就不认识，少一个值管理端会漏一段历史。
 *
 * <p><b>写失败不能影响主流程。</b>
 * 审计是「事后能查」，不是「做这件事的前提」：
 * 因为记不下来而让登录失败，等于把一个记录问题变成一次故障。
 * 所以 {@link #record} 自己吞掉异常并记 error 日志。
 * 代价是它可能静默地少记几条——这个取舍是有意的，
 * 而且它比「漏一个调用点」好发现：日志里会有 error。
 *
 * <p>真正的隐性成本是<b>埋点漏一处</b>——那不会报错，只会留下一段查不到的空白。
 * 现在的埋点一共六处：登录成功 / 失败、改密码、删链接、禁用、恢复、越权访问。
 * 加新的敏感操作时，回来把 {@link #ACTION_VALUES} 和埋点一起补上。
 */
@Component
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /** 登录成功。 */
    public static final String LOGIN_OK = "LOGIN_OK";

    /** 登录失败：密码错、账号被锁、账号被停用，都记这一个。 */
    public static final String LOGIN_FAIL = "LOGIN_FAIL";

    /** 改自己的密码。 */
    public static final String PASSWORD_CHANGE = "PASSWORD_CHANGE";

    /** 删除一条链接。 */
    public static final String LINK_DELETE = "LINK_DELETE";

    /** 管理员禁用某个账号。 */
    public static final String ADMIN_DISABLE = "ADMIN_DISABLE";

    /** 管理员恢复某个账号。 */
    public static final String ADMIN_ENABLE = "ADMIN_ENABLE";

    /** 非管理员访问了管理接口。 */
    public static final String ADMIN_ACCESS_DENIED = "ADMIN_ACCESS_DENIED";

    /** 全部动作。给「参数是否合法」的校验用，避免合法值散落在两个地方。 */
    public static final List<String> ACTION_VALUES = List.of(
            LOGIN_OK, LOGIN_FAIL, PASSWORD_CHANGE, LINK_DELETE,
            ADMIN_DISABLE, ADMIN_ENABLE, ADMIN_ACCESS_DENIED);

    public static final String RESULT_SUCCESS = "success";
    public static final String RESULT_FAILURE = "failure";
    public static final String RESULT_DENIED = "denied";

    /** detail 最长多长。审计要的是「发生了什么」，不是把整段正文存进来。 */
    private static final int MAX_DETAIL = 500;

    /** 用户名最长多长。和 {@code app_user.username} 的列宽一致。 */
    private static final int MAX_USERNAME = 190;

    /** target 最长多长。和表里 {@code target VARCHAR(190)} 一致。 */
    private static final int MAX_TARGET = 190;

    private final AuditLogMapper auditLogs;
    private final UserRepository users;
    private final Clock clock;

    public AuditService(AuditLogMapper auditLogs, UserRepository users, Clock clock) {
        this.auditLogs = auditLogs;
        this.users = users;
        this.clock = clock;
    }

    /** 管理端看到的一条记录。字段名和前端的 {@code AuditEntry} 一一对应。 */
    public record Entry(
            String id,
            String userId,
            String username,
            String action,
            String target,
            String detail,
            String result,
            String ip,
            String createdAt
    ) {}

    /**
     * 记一条。
     *
     * @param userId   谁。可以为 null（比如「查无此人」的登录失败）
     * @param username 用户名快照。为 null 时按 userId 去查一次；查不到就留 null
     * @param action   {@link #ACTION_VALUES} 之一
     * @param target   被操作的对象（链接 id / 被禁用的账号 id），没有就传 null
     * @param result   {@link #RESULT_SUCCESS} / {@link #RESULT_FAILURE} / {@link #RESULT_DENIED}
     * @param detail   补充说明，可以为空
     * @param ip       来源 IP，可以为空
     */
    public void record(String userId, String username, String action, String target,
                       String result, String detail, String ip) {
        try {
            AuditLogEntity e = new AuditLogEntity();
            e.setUserId(userId);
            e.setUsername(fit(resolveUsername(userId, username), MAX_USERNAME));
            e.setAction(action);
            e.setTarget(fit(target, MAX_TARGET));
            e.setDetail(fit(detail, MAX_DETAIL));
            e.setResult(result);
            e.setIp(fit(ip, 64));
            e.setCreatedAt(RelativeTime.format(clock.now()));
            auditLogs.insert(e);
        } catch (Exception ex) {
            // 见类注释：审计写不进去不能让主流程失败，但必须留下痕迹
            log.error("审计日志写入失败（action={}, userId={}）：{}", action, userId, ex.toString());
        }
    }

    /** 登录成功 / 失败这两个最常用的入口，省得每个调用方都填一次 result。 */
    public void recordLogin(String userId, String username, boolean ok, String detail, String ip) {
        record(userId, username, ok ? LOGIN_OK : LOGIN_FAIL, null,
                ok ? RESULT_SUCCESS : RESULT_FAILURE, detail, ip);
    }

    /**
     * 管理端翻页查。
     *
     * <p><b>空串先转成 null</b>：Mapper 的 {@code <if>} 只判 null，
     * 传 {@code ""} 进去会变成「筛出 user_id 为空的行」，和「不筛」是两回事。
     */
    public List<Entry> search(String userId, String action, String result, int offset, int limit) {
        String uid = blankToNull(userId);
        String act = blankToNull(action);
        String res = blankToNull(result);
        return auditLogs.searchPage(uid, act, res, limit, offset).stream()
                .map(AuditService::toEntry)
                .toList();
    }

    public int count(String userId, String action, String result) {
        return auditLogs.countBy(blankToNull(userId), blankToNull(action), blankToNull(result));
    }

    private String resolveUsername(String userId, String username) {
        if (username != null && !username.isBlank()) {
            return username;
        }
        if (userId == null || userId.isBlank()) {
            return null;
        }
        return users.findById(userId).map(u -> u.username()).orElse(null);
    }

    private static Entry toEntry(AuditLogEntity e) {
        return new Entry(
                e.getId() == null ? null : String.valueOf(e.getId()),
                e.getUserId(),
                e.getUsername(),
                e.getAction(),
                e.getTarget(),
                e.getDetail(),
                e.getResult(),
                e.getIp(),
                e.getCreatedAt());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String fit(String s, int max) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}

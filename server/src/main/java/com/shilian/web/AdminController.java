package com.shilian.web;

import com.shilian.domain.port.CurrentUser;
import com.shilian.domain.user.User;
import com.shilian.infrastructure.audit.AuditService;
import com.shilian.repo.LinkRepository;
import com.shilian.repo.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端接口（R-19 / R-20）：看全部用户、禁用 / 恢复、查审计日志。
 *
 * <p><b>越权一律返回 404，不是 403。</b>
 * 403 等于确认「这个资源存在，只是你不能碰」——
 * 对一个不该知道管理端存在的人，那句话本身就是信息。
 * 404 让所有人在这一点上看到的是同一个结果：没有这个地方。
 *
 * <p><b>每个方法都先过 {@link #requireAdmin}，不看前端有没有藏好入口。</b>
 * 前端不显示入口只是不把路标立出来，路本身还在；
 * 真正的门在这里。这也是为什么「管理端入口只对 admin 显示」不能算安全措施——
 * 它只是省掉了普通用户一次注定失败的点击。
 *
 * <p><b>权限判据是库里的 {@code role}，现查现用，不做缓存。</b>
 * 和 {@code DisabledUserFilter} 同一个理由：管理权被收回时应当立刻生效，
 * 而不是等一个缓存过期。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    /** 一页最多几个。上限是防 {@code ?size=999999} 把整张表倒出来。 */
    private static final int MAX_SIZE = 100;

    /** 一页最少一个。0 会让 LIMIT 0 返回一个空页，而调用方看不出是自己传错了。 */
    private static final int MIN_SIZE = 1;

    private final UserRepository users;
    private final LinkRepository links;
    private final AuditService audit;
    private final CurrentUser current;

    public AdminController(UserRepository users, LinkRepository links,
                           AuditService audit, CurrentUser current) {
        this.users = users;
        this.links = links;
        this.audit = audit;
        this.current = current;
    }

    /* ────────────── 用户 ────────────── */

    /**
     * 用户列表。
     *
     * <p>两个统计（链接数、AI 用量）都是<b>一次聚合查询拿出来</b>再和这一页的用户拼上，
     * 而不是给这 20 个人各查一次——那会随页数线性变慢，
     * 而且代码里会多出两个「按 id 查」的方法，看着像 N+1 却不容易发现。
     *
     * @param q    邮箱 / 用户名 / 昵称的模糊匹配，不传表示全部
     * @param page 从 0 开始
     */
    @GetMapping("/users")
    public Page<AdminUser> listUsers(@RequestParam(required = false) String q,
                                     @RequestParam(required = false, defaultValue = "0") int page,
                                     @RequestParam(required = false, defaultValue = "20") int size) {
        User me = requireAdmin(null);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(MIN_SIZE, Math.min(size, MAX_SIZE));

        String keyword = q == null || q.isBlank() ? null : q.trim();
        int total = users.countByKeyword(keyword);
        List<User> page0 = users.searchPage(keyword, safePage * safeSize, safeSize);

        Map<String, Integer> linkCounts = links.linkCountsByUser();
        Map<String, LinkRepository.AiUsage> usage = links.aiUsageByUser();

        List<AdminUser> items = page0.stream()
                .map(u -> toAdminUser(u, linkCounts, usage))
                .toList();
        log.info("管理员 {} 查看用户列表：q={} page={} size={}", me.username(), keyword, safePage, safeSize);
        return new Page<>(items, total, safePage, safeSize);
    }

    /**
     * 禁用 / 恢复一个账号。
     *
     * <p><b>不允许改自己。</b>
     * 管理员把自己停用的那一刻，管理端就没有人能进来了——
     * 恢复它需要开数据库手写 SQL，而「能用 SQL」恰恰是这个功能想省掉的事。
     * 这不是一条权限规则，是防呆。
     */
    @PatchMapping("/users/{id}")
    public AdminUser setStatus(@PathVariable String id,
                               @Valid @RequestBody SetStatusRequest req,
                               HttpServletRequest request) {
        User me = requireAdmin(request);
        if (me.id().equals(id)) {
            throw new IllegalArgumentException("不能停用自己的账号——那样管理端就没人能进来了");
        }
        if (!User.STATUS_ACTIVE.equals(req.status()) && !User.STATUS_DISABLED.equals(req.status())) {
            throw new IllegalArgumentException("状态只能是 active 或 disabled");
        }

        User target = users.findById(id)
                .orElseThrow(() -> new NotFoundException("找不到这个用户"));
        if (req.status().equals(target.status())) {
            return toAdminUser(target);
        }

        int rows = users.updateStatus(id, req.status());
        if (rows == 0) {
            throw new NotFoundException("找不到这个用户");
        }

        boolean disabling = User.STATUS_DISABLED.equals(req.status());
        /*
         * target 存被操作的账号 id，username 只进 detail。
         * 账号 id 是稳定的，用户名将来改了对不上；而读日志的人要看的是名字，
         * 所以两个都留——机器对 id，人对名字。
         */
        audit.record(me.id(), me.username(),
                disabling ? AuditService.ADMIN_DISABLE : AuditService.ADMIN_ENABLE,
                id,
                AuditService.RESULT_SUCCESS,
                target.username() + "（" + id + "）",
                ClientIp.of(request));
        log.info("管理员 {} {} 账号 {}", me.username(), disabling ? "停用" : "恢复", target.username());

        return toAdminUser(users.findById(id).orElse(target));
    }

    /* ────────────── 审计日志 ────────────── */

    /**
     * 审计日志。
     *
     * @param userId 按人筛，不传表示全部
     * @param action 按动作筛，取值见 {@code AuditService} 的七个常量；
     *               传了不在其中的值会返回空页而不报错——
     *               那比 400 好：前端的筛选下拉框将来加了新选项时，
     *               老后端不该把它变成一个错误。
     * @param result 按结果筛（{@code success} / {@code failure} / {@code denied}），同样「认不出就返回空页」。
     *               <p><b>为什么它是独立的一列、独立的一个筛选项。</b>
     *               翻审计的人十有八九在找「哪次没成」——谁在反复试密码、谁被拦在了管理端外面。
     *               只能按动作筛的话，想看失败得把每个动作都翻一遍，而那恰好是最费眼睛的做法。
     *               也正因为要能筛，result 就不能并进 detail：并进去以后筛「失败」
     *               只能靠 LIKE 匹配文案，而文案是会改的。
     */
    @GetMapping("/audit")
    public Page<AuditService.Entry> listAudit(@RequestParam(required = false) String userId,
                                              @RequestParam(required = false) String action,
                                              @RequestParam(required = false) String result,
                                              @RequestParam(required = false, defaultValue = "0") int page,
                                              @RequestParam(required = false, defaultValue = "20") int size) {
        requireAdmin(null);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(MIN_SIZE, Math.min(size, MAX_SIZE));
        String uid = blankToNull(userId);
        String act = blankToNull(action);
        return new Page<>(audit.search(uid, act, blankToNull(result), safePage * safeSize, safeSize),
                audit.count(uid, act, blankToNull(result)), safePage, safeSize);
    }

    /* ────────────── 内部 ────────────── */

    /**
     * 确认当前用户是管理员，不是就 404。
     *
     * @param request 传进来会顺手记一条 {@code ADMIN_ACCESS_DENIED}；
     *                列表类接口传 null（那些接口只有管理员能走到这里，
     *                越权的记录由下面的接口补上就够，不必每个都记）
     */
    private User requireAdmin(HttpServletRequest request) {
        User me = users.findById(current.id())
                .orElseThrow(() -> new com.shilian.domain.AuthenticationRequiredException(
                        "登录状态已失效，请重新登录"));
        if (!me.isAdmin()) {
            if (request != null) {
                /*
                 * target 存他试图访问的那个 URI：越权记录里「想进的是哪儿」
                 * 比「为什么被拦」更值得留意——同一个账号反复撞同一个地址，
                 * 和到处乱撞，是两件不同的事，只有记了 URI 才分得出来。
                 */
                audit.record(me.id(), me.username(), AuditService.ADMIN_ACCESS_DENIED,
                        request.getRequestURI(), AuditService.RESULT_DENIED,
                        "非管理员访问管理接口", ClientIp.of(request));
            }
            log.warn("非管理员 {} 访问了管理接口{}",
                    me.username(), request == null ? "" : "：" + request.getRequestURI());
            throw new AdminAccessDeniedException();
        }
        return me;
    }

    private AdminUser toAdminUser(User u) {
        return toAdminUser(u, Map.of(), Map.of());
    }

    private AdminUser toAdminUser(User u, Map<String, Integer> linkCounts,
                                  Map<String, LinkRepository.AiUsage> usage) {
        LinkRepository.AiUsage ai = usage.getOrDefault(u.id(), LinkRepository.AiUsage.EMPTY);
        return new AdminUser(
                u.id(),
                u.username(),
                u.email(),
                u.nickname(),
                u.roleOrDefault(),
                u.status(),
                u.createdAt(),
                u.lastLoginAt(),
                linkCounts.getOrDefault(u.id(), 0),
                ai.calls(),
                ai.promptTokens(),
                ai.completionTokens(),
                ai.promptTokens() + ai.completionTokens());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /* ────────────── 类型 ────────────── */

    /**
     * 管理端看到的一个用户。
     *
     * <p>刻意<b>不含</b> passwordHash / failedAttempts / lockedUntil：
     * 和管理动作无关，而它们一旦出去就多一处泄露面。
     * 这个 record 根本没有那些字段，不是靠「记得别写进去」保证的。
     */
    public record AdminUser(
            String id,
            String username,
            String email,
            String nickname,
            String role,
            String status,
            String createdAt,
            String lastLoginAt,
            int linkCount,
            long aiCalls,
            long promptTokens,
            long completionTokens,
            /** 上面两项的合计。管理端要显示的是「一共花了多少」，不该让前端去加。 */
            long tokenTotal
    ) {}

    /** 一页结果。{@code total} 是满足条件的总数，不是本页条数。 */
    public record Page<T>(List<T> items, long total, int page, int size) {}

    /** 改状态。 */
    public record SetStatusRequest(
            @jakarta.validation.constraints.NotBlank(message = "状态不能为空") String status
    ) {}

    /** 找不到。映射成 404，和「没有这个接口」一致——见类注释。 */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }
}

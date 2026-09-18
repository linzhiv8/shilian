package com.shilian.web;

import com.shilian.config.ShilianProperties;
import com.shilian.domain.AuthenticationRequiredException;
import com.shilian.domain.port.Clock;
import com.shilian.domain.port.CurrentUser;
import com.shilian.domain.user.User;
import com.shilian.infrastructure.audit.AuditService;
import com.shilian.infrastructure.security.RateLimiter;
import com.shilian.repo.LinkRepository;
import com.shilian.repo.UserRepository;
import com.shilian.web.dto.AuthResponse;
import com.shilian.web.dto.ChangePasswordRequest;
import com.shilian.web.dto.LoginRequest;
import com.shilian.web.dto.RegisterRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 注册、登录、登出、查看当前用户。
 *
 * <p>这里每一个「多余」的步骤都有原因，改动前请先读完对应的注释——
 * 它们防的都不是「功能坏掉」，而是「功能好着，但被绕过去了」。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuthenticationManager authManager;
    private final ShilianProperties props;
    private final Clock clock;
    private final LinkRepository links;
    private final CurrentUser current;
    private final RateLimiter limiter;
    private final AuditService audit;

    /**
     * 用户不存在时用来空转一次的哈希。
     *
     * <p>为什么需要它：如果用户不存在就直接返回，
     * 「用户不存在」的响应会比「密码错误」快几十毫秒——
     * bcrypt 的耗时正是它防御力的来源，这个时间差足以让人
     * 挨个试出哪些用户名是真实存在的。所以不存在时也要做一次同等耗时的比较。
     */
    private final String dummyHash;

    public AuthController(UserRepository users, PasswordEncoder encoder,
                          AuthenticationManager authManager, ShilianProperties props,
                          Clock clock, LinkRepository links, CurrentUser current,
                          RateLimiter limiter, AuditService audit) {
        this.users = users;
        this.encoder = encoder;
        this.authManager = authManager;
        this.props = props;
        this.clock = clock;
        this.links = links;
        this.current = current;
        this.limiter = limiter;
        this.audit = audit;
        this.dummyHash = encoder.encode("timing-attack-placeholder");
    }

    /* ────────────── 注册 ────────────── */

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest req,
                                HttpServletRequest request) {
        /*
         * 用户名和邮箱的占用情况在这里如实报出去。
         *
         * 这和「登录失败不区分用户不存在/密码错误」不矛盾：
         * 注册时必须告诉用户「换个名字吧」，否则他会对着一个
         * 永远提交不上去的表单发呆。用户名本来也不是秘密。
         * 真正的防线是注册限流（阶段 5 的 C14），不是藏着这个信息。
         */
        /*
         * 注册限流按 IP。不做的话这个接口可以被无限刷——
         * 刷出来的账号哪怕毫无用处，也占着存储、拖慢之后每一次查询。
         */
        RateLimiter.Decision reg = limiter.check("register:" + clientIp(request),
                props.auth().maxRegistrationsPerHour(), java.time.Duration.ofHours(1));
        if (!reg.allowed()) {
            throw new TooManyRequestsException(
                    "注册太频繁了，请 " + reg.retryAfterSeconds() + " 秒后再试",
                    reg.retryAfterSeconds());
        }

        /*
         * 第二个维度：邮箱。
         *
         * 只按 IP 限是不够的——代理池很便宜，而「每个邮箱只试一次」的攻击者
         * 在 IP 维度上看起来完全正常。按邮箱再拦一道，
         * 把「同一个地址被反复使用」这件事本身变得有代价。
         *
         * key 必须先规范化：不规范化的话，改个大小写就是一个新桶，
         * 这道限制形同虚设。
         *
         * 代价也说清楚：这意味着别人可以用你的邮箱把额度打满，
         * 让你一天之内注册不了。所以额度给得宽松（默认 5 次/天），
         * 正常人手滑重试碰不到，而它挡的是「反复」。
         */
        RateLimiter.Decision byEmail = limiter.check(
                "register-email:" + normalizeEmail(req.email()),
                props.auth().maxRegistrationsPerEmailPerDay(),
                java.time.Duration.ofDays(1));
        if (!byEmail.allowed()) {
            throw new TooManyRequestsException(
                    "这个邮箱今天试得太多次了，请 " + byEmail.retryAfterSeconds() + " 秒后再试",
                    byEmail.retryAfterSeconds());
        }

        if (users.existsByUsername(req.username())) {
            throw new ConflictException("这个用户名已经被用了，换一个");
        }
        if (users.existsByEmail(req.email())) {
            throw new ConflictException("这个邮箱已经注册过了");
        }

        User saved = users.insert(new UserRepository.NewUser(
                req.username(),
                req.email(),
                encoder.encode(req.password()),
                req.nickname() == null || req.nickname().isBlank() ? req.username() : req.nickname()));

        /*
         * 第一个账号接手历史数据。
         *
         * V2 迁移只改了表结构，没划归属——升级上来的那批老数据 user_id 全是 NULL，
         * 谁都看不见。它们是用户一条条攒下来的，不能就这么沉下去，
         * 所以让第一个注册的账号把它们领走。
         *
         * 只在 countAll() == 1 时做：第二个账号再执行就会把第一个人的数据也划走。
         */
        if (users.countAll() == 1) {
            int claimed = links.claimOrphans(saved.id());
            if (claimed > 0) {
                log.info("首个账号 {} 认领了 {} 条历史数据", saved.username(), claimed);
            }
        }

        log.info("新用户注册：{}", saved.username());
        return AuthResponse.of(saved);
    }

    /* ────────────── 登录 ────────────── */

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest request) {
        /*
         * 登录限流按 IP。和「账号失败锁定」是两件事：
         * 那个防的是盯着一个账号反复试，这个防的是拿一堆账号各试一次——
         * 后者靠账号锁定完全拦不住，因为每个账号都只错了两次。
         */
        RateLimiter.Decision gate = limiter.check("login:" + clientIp(request),
                props.auth().maxLoginAttemptsPerMinute(), java.time.Duration.ofMinutes(1));
        if (!gate.allowed()) {
            throw new TooManyRequestsException(
                    "试得太快了，请 " + gate.retryAfterSeconds() + " 秒后再试",
                    gate.retryAfterSeconds());
        }

        User target = users.findByUsernameOrEmail(req.username()).orElse(null);

        // 已锁定：如实告诉他还要等多久。不说的话他只会一直点，
        // 而这里泄露的「用户名存在」在锁定时已经无所谓了——
        // 能试到锁定，说明他已经知道这个账号存在。
        if (target != null && target.isLockedAt(clock.now())) {
            audit.recordLogin(target.id(), target.username(), false, "账号已锁定", clientIp(request));
            throw new AccountLockedException(
                    "尝试次数太多了，请 " + props.auth().lockMinutes() + " 分钟后再试");
        }

        /*
         * R-17：停用的账号在这里就进不来。
         *
         * <b>响应必须和「用户名或密码不对」一字不差。</b>
         * 「账号已被停用」这句话同时泄露了两件事：这个账号存在、它被人处理过。
         * 拿它去撞库的人先得到一份「真实账号清单」，比密码错有用得多。
         * 真正的提示由管理员线下告知，不在接口里说。
         *
         * <b>仍然要空转一次 bcrypt</b>：这里一次密码比较都没做，
         * 直接返回会比「密码错」快几十毫秒，光是这个时间差就足以判断
         * 「这个账号存在，只是被停了」——前面的措辞就白统一了。
         */
        if (target != null && target.isDisabled()) {
            burnTime(req.password());
            audit.recordLogin(target.id(), target.username(), false, "账号已被停用", clientIp(request));
            throw new InvalidCredentialsException();
        }

        try {
            Authentication auth = authManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.username(), req.password()));

            /*
             * 防会话固定攻击：认证成功后必须换一个 sessionId。
             *
             * Spring Security 的 sessionFixation 只在它自己的登录过滤器里触发，
             * 这里是手动调 authenticate，那条链路不走，所以得自己换。
             * 不换的话，攻击者预先塞给受害者一个已知 sessionId，
             * 等受害者一登录，那个 id 就变成已认证会话了。
             *
             * 顺序不能反：changeSessionId() 要求「当前请求已经关联了会话」，
             * 而首次登录时会话还不存在，直接调会抛
             * "Cannot change session ID. There is no session associated with this request."
             * 表现就是登录接口 500——这个坑只有真的发一次请求才会遇到。
             */
            HttpSession session = request.getSession(true);
            request.changeSessionId();

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            session.setAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

            if (target != null) {
                users.recordSuccess(target.id());
                audit.recordLogin(target.id(), target.username(), true, null, clientIp(request));
            }
            log.info("用户登录：{}", req.username());
            return AuthResponse.of(users.findByUsernameOrEmail(req.username()).orElseThrow());

        } catch (LockedException e) {
            // 走到这里说明「查库时还没锁、认证时才锁上」，是个窄窗口，但仍要记下来
            audit.recordLogin(target == null ? null : target.id(), req.username(),
                    false, "账号已锁定", clientIp(request));
            throw new AccountLockedException(
                    "尝试次数太多了，请 " + props.auth().lockMinutes() + " 分钟后再试");
        } catch (org.springframework.security.authentication.DisabledException e) {
            // 正常路径已经提前拦掉了（见上面的 isDisabled 判断），
            // 这里是「查完状态和认证之间被人停用」这种并发窗口的兜底。
            audit.recordLogin(target == null ? null : target.id(), req.username(),
                    false, "账号已被停用", clientIp(request));
            throw new InvalidCredentialsException();
        } catch (org.springframework.security.authentication.BadCredentialsException e) {
            if (target != null) {
                users.recordFailure(target.id(),
                        props.auth().maxFailedAttempts(), props.auth().lockMinutes());
            } else {
                // 用户不存在。这里必须空转一次 bcrypt，
                // 否则「查无此人」会比「密码错误」快几十毫秒，
                // 光靠这个时间差就能把真实用户名挨个试出来。
                burnTime(req.password());
            }
            /*
             * 查无此人时 req.username() 就是对方试探的那个标识符——
             * 审计里要留它：「谁在试什么」比「有人失败了」有用得多。
             * userId 留空，因为这个人根本不存在。
             */
            audit.recordLogin(target == null ? null : target.id(), req.username(),
                    false, "用户名或密码不对", clientIp(request));
            /*
             * 统一措辞：不告诉对方是用户名不存在还是密码错了。
             * 前者会让撞库的人先筛出一批真实账号。
             */
            throw new InvalidCredentialsException();
        }
    }

    /** 用户不存在时也要花掉一次 bcrypt 的时间，见 {@link #dummyHash}。 */
    private void burnTime(String rawPassword) {
        encoder.matches(rawPassword == null ? "" : rawPassword, dummyHash);
    }

    /**
     * 取客户端 IP。
     *
     * <p><b>这里直接读 {@code getRemoteAddr()}，但那不再等于「TCP 对端地址」。</b>
     * 曾经它确实等于对端地址，那时「刻意不信 {@code X-Forwarded-For}」是对的：
     * 那个头由请求方自己写，可以随便伪造，限流按它做等于没做
     * （攻击者每换一个头就是一个新身份）。
     *
     * <p><b>但上线过了 nginx 之后，裸的 {@code getRemoteAddr()} 会返回代理自己的地址</b>
     * （{@code 127.0.0.1}），所有人共用一个限流桶——一个 IP 打满额度，全体用户被挡在
     * 门外，限流器直接变成拒绝服务工具。这个洞现在由
     * {@code server.forward-headers-strategy: native}（见 application.yml）补上：
     * Tomcat 的 {@code RemoteIpValve} 会在「直连方命中 {@code internalProxies}
     * （回环/私网段，nginx 从 {@code 127.0.0.1} 连过来正好命中）」时，把
     * {@code getRemoteAddr()} 重写成 {@code X-Forwarded-For} 里<b>从右往左第一个
     * 不可信</b>的地址。阀在下面一层工作，所以这里的方法体不用动。
     *
     * <p><b>必须是 {@code native} 不是 {@code framework}。</b>nginx 用的是
     * {@code $proxy_add_x_forwarded_for}（追加语义，头是「{@code <伪造值>, <真实 IP>}」），
     * {@code framework}（Spring 的 {@code ForwardedHeaderFilter}）取<b>最左</b>值 =
     * 拿到伪造值，加一个头就能把限流绕过去。
     */
    private static String clientIp(HttpServletRequest request) {
        return ClientIp.of(request);
    }

    /**
     * 邮箱规范化，<b>只用于限流的 key</b>。
     *
     * <p>不做这一步，改个大小写就能绕过邮箱维度的限流。
     *
     * <p>注意这里只影响限流计数：数据库里的邮箱目前仍是原样存储、精确匹配，
     * 所以 {@code A@x.com} 和 {@code a@x.com} 会被当成两个不同的账号。
     * 那是个独立的问题，要连数据迁移一起做（还得先决定已存在的冲突行怎么合并），
     * 不在这次范围内。
     */
    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /* ────────────── 改密码 ────────────── */

    /**
     * 改自己的密码。
     *
     * <p><b>已知缺口：改完密码，别的设备上的会话不会立刻失效。</b>
     * 要做到「一处改密码，处处下线」，得维护一份会话登记表
     * （Spring 的 {@code SessionRegistry}），而它只在走框架自带登录过滤器时才被填充——
     * 这里是手动调 {@code authenticate}，那条链路不经过登记。
     * 补齐要改登录方式或自己维护登记表，记在 REQUIREMENTS.md（阶段 5）。
     * 现在的缓解是会话本身有过期时间，而且本机自用场景下设备就一两个。
     *
     * <p>这里换 sessionId 是为了防会话固定：改密码是一次敏感操作，
     * 攻击者预置的 sessionId 不该在这之后继续有效。
     */
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest req,
                               HttpServletRequest request) {
        User me = users.findById(current.id())
                .orElseThrow(() -> new AuthenticationRequiredException("登录状态已失效，请重新登录"));

        if (!encoder.matches(req.oldPassword(), me.passwordHash())) {
            throw new IllegalArgumentException("当前密码不对");
        }
        if (encoder.matches(req.newPassword(), me.passwordHash())) {
            throw new IllegalArgumentException("新密码和当前的一样，换一个吧");
        }

        users.updatePasswordHash(me.id(), encoder.encode(req.newPassword()));
        // 同样要先确保会话存在，理由见 login 里的注释
        request.getSession(true);
        request.changeSessionId();
        /*
         * target 填自己的 id，和「删链接」那条的 target 填链接 id 是同一个意思——
         * 都是「这次动作落在哪个对象上」。改密码落在自己身上，所以就是自己。
         * 不填 null：审计表翻起来时「对象」那一列空着，看不出这条记的是什么。
         */
        audit.record(me.id(), me.username(), AuditService.PASSWORD_CHANGE, me.id(),
                AuditService.RESULT_SUCCESS, null, clientIp(request));
        log.info("用户改了密码：{}", me.username());
    }

    /* ────────────── 当前用户 ────────────── */

    @GetMapping("/me")
    public AuthResponse me(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new AuthenticationRequiredException();
        }
        return users.findByUsername(auth.getName())
                .map(AuthResponse::of)
                .orElseThrow(AuthenticationRequiredException::new);
    }

    /* ────────────── 异常 ────────────── */

    /** 用户名或邮箱已被占用。映射到 409。 */
    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    /**
     * 用户名或密码不对。
     *
     * <p>刻意不区分「用户不存在」和「密码错误」——
     * 区分了就等于帮攻击者先筛一遍哪些账号是真的。
     */
    public static class InvalidCredentialsException extends RuntimeException {
        public InvalidCredentialsException() {
            super("用户名或密码不对");
        }
    }

    /**
     * 手速太快。和「账号被锁」分开：这个是暂时的，等几秒就能继续。
     *
     * <p>带上 {@code retryAfterSeconds} 是为了能回一个标准的 {@code Retry-After} 头。
     * 光给一句「请 42 秒后再试」，前端只能自己猜倒计时该从几开始；
     * 把秒数放进头里，前端照着渲染就行，两边不会算出不同的数。
     */
    public static class TooManyRequestsException extends RuntimeException {
        private final long retryAfterSeconds;

        public TooManyRequestsException(String message, long retryAfterSeconds) {
            super(message);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    public static class AccountLockedException extends RuntimeException {
        public AccountLockedException(String message) {
            super(message);
        }
    }
}

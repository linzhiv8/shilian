package com.shilian.web;

import com.shilian.config.ShilianProperties;
import com.shilian.domain.AuthenticationRequiredException;
import com.shilian.domain.port.Clock;
import com.shilian.domain.port.CurrentUser;
import com.shilian.domain.user.User;
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
                          RateLimiter limiter) {
        this.users = users;
        this.encoder = encoder;
        this.authManager = authManager;
        this.props = props;
        this.clock = clock;
        this.links = links;
        this.current = current;
        this.limiter = limiter;
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
            throw new AccountLockedException(
                    "尝试次数太多了，请 " + props.auth().lockMinutes() + " 分钟后再试");
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
            }
            log.info("用户登录：{}", req.username());
            return AuthResponse.of(users.findByUsernameOrEmail(req.username()).orElseThrow());

        } catch (LockedException e) {
            throw new AccountLockedException(
                    "尝试次数太多了，请 " + props.auth().lockMinutes() + " 分钟后再试");
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
     * <p><b>刻意不信 {@code X-Forwarded-For}。</b>
     * 那个头是请求方自己写的，可以随便伪造——限流按它来做等于没做
     * （攻击者每换一个头就是一个新的身份）。
     * 所以直接用 TCP 连接的地址。
     *
     * <p><b>但这条在部署到反向代理后面时会失效，而且失效得很安静。</b>
     * 产品方向是公网多用户，上线必然要过 nginx。到那时 {@code getRemoteAddr()}
     * 返回的是代理自己的地址，所有人共用一个限流桶——
     * 一个 IP 打满额度，全体用户被挡在门外，限流器直接变成拒绝服务工具。
     * 正确做法是在代理层配可信代理列表，由代理把真实地址写进一个受信任的头，
     * 服务端只读那个头（而不是无脑读客户端传来的值）。
     * 记在 REQUIREMENTS.md（阶段 5），上代理之前必须先做掉。
     */
    private static String clientIp(HttpServletRequest request) {
        return request == null ? "unknown" : request.getRemoteAddr();
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

package com.shilian.web;

import com.shilian.domain.AuthenticationRequiredException;
import com.shilian.domain.port.CurrentUser;
import com.shilian.domain.user.User;
import com.shilian.infrastructure.audit.AuditService;
import com.shilian.infrastructure.mail.EmailTokenService;
import com.shilian.infrastructure.mail.MailService;
import com.shilian.infrastructure.security.RateLimiter;
import com.shilian.repo.UserRepository;
import com.shilian.repo.entity.EmailTokenEntity;
import com.shilian.web.dto.ConfirmEmailRequest;
import com.shilian.web.dto.ForgotPasswordRequest;
import com.shilian.web.dto.OkResponse;
import com.shilian.web.dto.ResetPasswordRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Optional;

/**
 * 邮箱相关的两个流程：找回密码（R-08）和验证邮箱（R-18）。
 *
 * <p><b>为什么单独一个 Controller，而不是塞进 {@code AuthController}。</b>
 * AuthController 已经在管注册、登录、改密码、当前用户四件事，
 * 再往里加会让它变成一个「什么都管」的类——而这两件事有一个它那里没有的
 * 共同点：<b>要发信、要跨请求保存状态（令牌）</b>。把它们放在一起，
 * 是为了让「发信失败怎么办」「令牌多久过期」这类判断只有一处。
 */
@RestController
@RequestMapping("/api/auth")
public class EmailController {

    private static final Logger log = LoggerFactory.getLogger(EmailController.class);

    /*
     * 「忘记密码」每个 IP 每小时的次数。
     *
     * 用常量而不是做成配置项，是因为它只有一个合理的取值区间：
     * 真人一小时里点超过 5 次「忘记密码」基本不可能，而攻击者刷这个接口
     * 的成本极低（一个请求就能触发一次发信）。做成可调的，
     * 唯一的用途就是给自己留一个「调大一点就不限了」的口子。
     */
    private static final int FORGOT_MAX_PER_HOUR = 5;

    /** 令牌有效时长。和 {@link EmailTokenService} 里的定义保持一致。 */
    private static final String VALID_HINT = "30 分钟";

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final MailService mail;
    private final EmailTokenService tokens;
    private final RateLimiter limiter;
    private final CurrentUser current;
    private final AuditService audit;

    public EmailController(UserRepository users, PasswordEncoder encoder, MailService mail,
                           EmailTokenService tokens, RateLimiter limiter,
                           CurrentUser current, AuditService audit) {
        this.users = users;
        this.encoder = encoder;
        this.mail = mail;
        this.tokens = tokens;
        this.limiter = limiter;
        this.current = current;
        this.audit = audit;
    }

    /**
     * 申请一封重置密码的邮件。
     *
     * <p><b>无论这个邮箱存不存在、验证没验证过，响应都一样。</b>
     * 这一条是这里最重要的设计，理由有两层：
     *   ① 否则这个接口就是一台「这个邮箱注册过拾链没有」的查询机（账号枚举）；
     *   ② 否则它还是一台免费发信机——谁都能拿它往任意地址发我们的邮件，
     *      而那会很快把发信域名搞进黑名单。
     * 所以邮箱不存在时<b>不建令牌、不发信</b>，但照样走完整个流程、返回同一句话。
     *
     * <p><b>一个已知的残留，不假装它不存在：</b>
     * 响应虽然一样，耗时不一样——找到了要走一次发信（几百毫秒），
     * 没找到是毫秒级。严格来说这仍是一个可被测量的侧信道。
     * 彻底堵住要引入「排队 + 恒定返回」，对这个规模不值当，
     * 所以这里选择堵住最容易利用的那一条（响应内容），并把这一点写清楚。
     */
    @PostMapping("/forgot")
    public OkResponse forgot(@Valid @RequestBody ForgotPasswordRequest req,
                             HttpServletRequest request) {
        /*
         * 发信没配好就**在查邮箱之前**统一报错。
         *
         * 这个顺序不是随手放的。如果先查邮箱、再发信、让发信的异常冒出去，
         * 那么「邮箱存在且已验证」的请求会拿到 500，
         * 而「不存在」和「未验证」的拿到 200——
         * 于是这个接口变成了一台精确的探测器：能区分出谁注册过、还验证过。
         * 「配没配好」是全局状态，和请求内容无关，所以放在最前面不会泄露任何东西。
         */
        if (!mail.configured()) {
            throw new IllegalStateException("发信还没配好，找回密码暂时用不了");
        }

        String ip = ClientIp.of(request);
        RateLimiter.Decision d =
                limiter.check("forgot:" + ip, FORGOT_MAX_PER_HOUR, Duration.ofHours(1));
        if (!d.allowed()) {
            throw new AuthController.TooManyRequestsException(
                    "试得太频繁了，请 " + d.retryAfterSeconds() + " 秒后再试",
                    d.retryAfterSeconds());
        }

        Optional<User> found = users.findByEmail(req.email());
        if (found.isPresent()) {
            User u = found.get();
            /*
             * 没验证过的邮箱不发。
             *
             * 这是 R-18 里那条约束的另一半：如果往一个我们没确认过的地址发重置链接，
             * 等于把账号的控制权交给任何填了这个邮箱的人——他只要能收到信就能改密码。
             * 代价是这个账号没法自助找回，只能人工处理，而我们本来也只有个位数用户。
             */
            if (u.emailVerified()) {
                String token = tokens.issue(u.id(), u.email(), EmailTokenService.PURPOSE_RESET);
                try {
                    mail.send(u.email(), "重设你在拾链的密码",
                            "有人用这个邮箱申请重设拾链的密码。\n\n"
                                    + "点这个链接设置新密码（" + VALID_HINT + "内有效，用一次就作废）：\n"
                                    + mail.resetLink(token) + "\n\n"
                                    + "如果不是你申请的，不用管这封信，密码不会变。");
                } catch (MailService.MailSendFailedException e) {
                    /*
                     * 配好的情况下偶发失败（SMTP 抽风、被限流）就吞掉，只记日志。
                     *
                     * 抛出去的话，「已注册且已验证」的请求会拿到 500，
                     * 其余拿到 200 —— 又变成一个探测器，只是噪声大一点。
                     * 用户这一次的代价是等不到信，只能再点一次；
                     * 这个代价小于把一个枚举口子长期留在公网上。
                     */
                    log.error("重置邮件没发出去（userId={}）：{}", u.id(), e.getMessage());
                }
            }
        }
        return OkResponse.OK;
    }

    /**
     * 用邮件里的令牌设置新密码。
     *
     * <p>不收旧密码：能走到这里已经证明他点开了那封信。令牌本身就是凭据。
     */
    @PostMapping("/reset")
    public OkResponse reset(@Valid @RequestBody ResetPasswordRequest req,
                            HttpServletRequest request) {
        EmailTokenEntity t = tokens.consume(req.token(), EmailTokenService.PURPOSE_RESET);
        if (t == null) {
            // 过期、用过、或者根本不存在，一律同一句话——不给「这个链接是真的但过期了」这种信息
            throw new IllegalArgumentException("链接已经失效了，重新申请一封吧");
        }
        User u = users.findById(t.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("链接已经失效了，重新申请一封吧"));
        if (!u.emailVerified()) {
            throw new IllegalArgumentException("这个邮箱还没验证过，没法自助重置");
        }

        users.updatePasswordHash(u.id(), encoder.encode(req.newPassword()));
        /*
         * 把这个账号名下所有还没用过的重置令牌一起作废。
         * 他可能点过好几次「忘记密码」，收件箱里躺着好几封；
         * 只作废刚用的这一条，剩下的链接在接下来半小时里还能改他的密码。
         */
        tokens.invalidateAll(u.id(), EmailTokenService.PURPOSE_RESET);

        audit.record(u.id(), u.username(), AuditService.PASSWORD_CHANGE, u.id(),
                AuditService.RESULT_SUCCESS, "通过邮件重置", ClientIp.of(request));

        /*
         * 已知的缺口：没法把他的其他会话踢掉。
         * 会话只存在 Spring 的会话存储里，没有「按用户查会话」的索引，
         * 要做到就得自己维护一张表。改完密码之后旧的登录状态仍然有效，
         * 这一点如实写在这里，不在注释里假装解决了。
         */
        return OkResponse.OK;
    }

    /**
     * 给当前账号发一封验证邮件。
     *
     * <p>已经验证过就什么也不做、照样返回成功——幂等。
     * 否则用户每点一次就收到一封信，而界面上没有任何变化能解释为什么。
     */
    @PostMapping("/verify/send")
    public OkResponse sendVerify(HttpServletRequest request) {
        User me = users.findById(current.id())
                .orElseThrow(() -> new AuthenticationRequiredException("登录状态已失效，请重新登录"));
        if (me.email() == null || me.email().isBlank()) {
            throw new IllegalArgumentException("这个账号没填邮箱，先去补一个");
        }
        if (me.emailVerified()) {
            return OkResponse.OK;
        }

        RateLimiter.Decision d = limiter.check("verify-mail:" + me.id(), 5, Duration.ofHours(1));
        if (!d.allowed()) {
            throw new AuthController.TooManyRequestsException(
                    "发了不少了，请 " + d.retryAfterSeconds() + " 秒后再试",
                    d.retryAfterSeconds());
        }

        String token = tokens.issue(me.id(), me.email(), EmailTokenService.PURPOSE_VERIFY);
        mail.send(me.email(), "验证一下你在拾链的邮箱",
                "点这个链接确认这个邮箱是你的（" + VALID_HINT + "内有效）：\n"
                        + mail.verifyLink(token) + "\n\n"
                        + "验证之后才能用它找回密码。不验证也能正常用拾链。");
        return OkResponse.OK;
    }

    /**
     * 确认验证链接。
     *
     * <p><b>为什么要求令牌属于当前登录的那个人。</b>
     * 令牌是从邮件里来的，谁点开谁就能提交。不校验归属的话，
     * 一个捡到别人链接的人（转发、邮件被看到）可以顺手把它确认掉，
     * 让那个邮箱变成「已验证」——而验证状态正是找回密码的门槛。
     * 要求登录并且是本人，多一道不麻烦的确认。
     */
    @PostMapping("/verify/confirm")
    public OkResponse confirm(@Valid @RequestBody ConfirmEmailRequest req,
                              HttpServletRequest request) {
        User me = users.findById(current.id())
                .orElseThrow(() -> new AuthenticationRequiredException("登录状态已失效，请重新登录"));
        EmailTokenEntity t = tokens.consume(req.token(), EmailTokenService.PURPOSE_VERIFY);
        if (t == null || !t.getUserId().equals(me.id())) {
            throw new IllegalArgumentException("链接已经失效了，重新发一封吧");
        }
        users.updateEmailVerified(me.id(), true);
        tokens.invalidateAll(me.id(), EmailTokenService.PURPOSE_VERIFY);
        return OkResponse.OK;
    }
}

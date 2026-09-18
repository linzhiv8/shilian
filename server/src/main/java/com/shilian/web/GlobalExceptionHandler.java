package com.shilian.web;

import com.shilian.analyze.AnalyzeService;
import com.shilian.domain.AuthenticationRequiredException;
import com.shilian.infrastructure.resilience.AnalysisGate;
import com.shilian.infrastructure.resilience.CircuitBreaker;
import com.shilian.repo.LinkRepository;
import com.shilian.search.SemanticSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一错误出口。
 *
 * <p>每种失败都给出「用户能看懂 + 知道下一步做什么」的话。
 * 「500 Internal Server Error」对个人自用的产品是纯粹的失败——
 * 他需要知道是网址打不开、还是 Key 没配、还是 AI 抽风了。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(LinkController.NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(LinkController.NotFoundException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage(), null);
    }

    @ExceptionHandler(DraftExpiredException.class)
    public ResponseEntity<Map<String, Object>> draftExpired(DraftExpiredException e) {
        return body(HttpStatus.GONE, e.getMessage(), null);
    }

    /**
     * 用户名或邮箱已被占用。
     *
     * <p>这里如实说明是哪一个被占了——注册时不说清楚，
     * 用户会对着一个永远提交不上去的表单发呆。
     * 真正的防线是注册限流，不是把提示藏起来。
     */
    @ExceptionHandler(AuthController.ConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(AuthController.ConflictException e) {
        return body(HttpStatus.CONFLICT, e.getMessage(), null);
    }

    /**
     * 用户名或密码不对。
     *
     * <p>刻意不区分「查无此人」和「密码错误」，消息就一句话。
     */
    @ExceptionHandler(AuthController.InvalidCredentialsException.class)
    public ResponseEntity<Map<String, Object>> invalidCredentials(
            AuthController.InvalidCredentialsException e) {
        return body(HttpStatus.UNAUTHORIZED, e.getMessage(), null);
    }

    /** 连续失败太多被锁定。用 429 表示「你太快了」，而不是 403 那种「你没资格」。 */
    @ExceptionHandler(AuthController.AccountLockedException.class)
    public ResponseEntity<Map<String, Object>> accountLocked(AuthController.AccountLockedException e) {
        return body(HttpStatus.TOO_MANY_REQUESTS, e.getMessage(), null);
    }

    /**
     * 注册或登录的请求频率超了。
     *
     * <p>和上面的「账号被锁」同样是 429，但这两件事要分开说：
     * 被锁是「这个账号暂时别试了」，要等十几分钟；
     * 频率超了是「你手速太快」，等几秒就好。同一个状态码下，
     * 用户看到的下一句话完全不同，所以消息不能复用。
     *
     * <p>除了把秒数写进消息，还回一个标准的 {@code Retry-After} 头：
     * 前端可以直接拿它渲染倒计时，不用去解析那句中文里夹着的数字。
     * 两边各算各的，迟早会算出不一样的结果。
     */
    @ExceptionHandler(AuthController.TooManyRequestsException.class)
    public ResponseEntity<Map<String, Object>> tooManyRequests(
            AuthController.TooManyRequestsException e) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("retryAfterSeconds", e.retryAfterSeconds());
        return withRetryAfter(e.retryAfterSeconds(),
                body(HttpStatus.TOO_MANY_REQUESTS, e.getMessage(), extra));
    }

    /**
     * 需要登录但没登录。
     *
     * <p>除了 {@code /api/auth/me} 之外，这条更常见的来源是数据层：
     * 所有查询都按当前用户过滤，没有会话就拿不到用户 id。
     * 正常情况下 Spring Security 会先在过滤器链上挡住返回 401，
     * 这里是兜底——比如将来某个接口被加进了 permitAll 却仍然读了用户数据。
     */
    @ExceptionHandler(AuthenticationRequiredException.class)
    public ResponseEntity<Map<String, Object>> notAuthenticated(AuthenticationRequiredException e) {
        return body(HttpStatus.UNAUTHORIZED, e.getMessage(), null);
    }

    @ExceptionHandler(LinkRepository.DuplicateUrlException.class)
    public ResponseEntity<Map<String, Object>> duplicate(LinkRepository.DuplicateUrlException e) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("existingId", e.existingId());
        return body(HttpStatus.CONFLICT, e.getMessage(), extra);
    }

    /** AI 调用或解析彻底失败。502 表示「上游不听话」，不是我们的 bug。 */
    @ExceptionHandler(AnalyzeService.AnalysisFailedException.class)
    public ResponseEntity<Map<String, Object>> analyzeFailed(AnalyzeService.AnalysisFailedException e) {
        log.warn("分析失败：{}", e.getMessage());
        return body(HttpStatus.BAD_GATEWAY, e.getMessage(), null);
    }

    /**
     * 向量服务连不上或返回了没法用的东西。
     *
     * <p>和「没配 Key」（503，见下面的 IllegalStateException）刻意分开：
     * 这个是上游的临时问题，等一下重搜可能就好了；那个是环境缺东西，
     * 重试一万次也一样。两句话指向的动作完全不同，不能合并成一个错误码。
     */
    @ExceptionHandler(SemanticSearchService.SearchFailedException.class)
    public ResponseEntity<Map<String, Object>> searchFailed(SemanticSearchService.SearchFailedException e) {
        log.warn("语义搜索失败：{}", e.getMessage());
        return body(HttpStatus.BAD_GATEWAY, e.getMessage(), null);
    }

    /**
     * 熔断打开：是我们主动决定暂时不调用上游，不是上游这次答错了。
     *
     * <p>所以用 503（暂时不可用）而不是 502（上游不听话）——两者的含义不同，
     * 前端给的提示也该不同：502 可以说「重试一次可能就好了」，
     * 503 得说「别急，冷却期过了才行」。
     *
     * <p>提示语里必须交代「网址没丢」。用户刚点完分析、等了半天，
     * 最担心的就是白干了，不把这句说出来他会反复重试。
     */
    @ExceptionHandler(CircuitBreaker.OpenException.class)
    public ResponseEntity<Map<String, Object>> circuitOpen(CircuitBreaker.OpenException e) {
        log.warn("熔断中，拒绝调用：{}", e.getMessage());
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("retryAfterSeconds", e.retryAfterSeconds());
        return body(HttpStatus.SERVICE_UNAVAILABLE,
                e.getMessage() + "这次没分析成功，网址也还没存进去——等一下重试即可，不会丢东西。",
                extra);
    }

    /** 同时进行的分析太多。排队到超时不如直接说忙，让他自己决定等还是先干别的。 */
    @ExceptionHandler(AnalysisGate.TooBusyException.class)
    public ResponseEntity<Map<String, Object>> tooBusy(AnalysisGate.TooBusyException e) {
        // warn 而不是 info：这是一次被拒的请求，用户那边是「点了没反应」的体感。
        // 生产环境通常只留 info 以上，用 info 记的话这类拒绝会被过滤掉，
        // 于是「最近是不是老在忙」这个问题在日志里查不到。
        log.warn("分析并发已满，拒绝请求");
        return body(HttpStatus.TOO_MANY_REQUESTS, e.getMessage(), null);
    }

    /** 没配 API Key 之类。属于「环境没准备好」，不是请求的问题。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> illegalState(IllegalStateException e) {
        log.warn("环境未就绪：{}", e.getMessage());
        return body(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), null);
    }

    /** 参数校验失败，取第一条具体信息返回，不要只丢一个「参数错误」。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getDefaultMessage())
                .orElse("请求参数不合法");
        return body(HttpStatus.BAD_REQUEST, msg, null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage(), null);
    }

    /**
     * 请求参数的类型对不上，比如 ?days=abc。
     *
     * <p>不处理的话会掉进下面的兜底分支变成 500，而 500 会让人以为是自己代码写错了。
     * 这里把出问题的参数名直接说出来。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> typeMismatch(MethodArgumentTypeMismatchException e) {
        String name = e.getName();
        String wanted = e.getRequiredType() == null ? "合法值" : e.getRequiredType().getSimpleName();
        return body(HttpStatus.BAD_REQUEST, "参数 " + name + " 的值不对，应该是一个 " + wanted, null);
    }

    /**
     * 路径不存在。
     *
     * <p>以前这条会落到兜底的 Exception 分支：返回 500 之外，还会打一整条堆栈到日志里。
     * 前端把路径拼错时，看到的是一句「服务出错了」——完全指向错误的方向。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> noResource(NoResourceFoundException e) {
        return body(HttpStatus.NOT_FOUND, "没有这个接口：" + e.getResourcePath(), null);
    }

    /** 方法不对，比如对着只读接口发 POST。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return body(HttpStatus.METHOD_NOT_ALLOWED,
                "这个接口不支持 " + e.getMethod() + " 方法", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        log.error("未预期的错误", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "服务出错了：" + e.getClass().getSimpleName(), null);
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String message,
                                                            Map<String, Object> extra) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", message == null ? status.getReasonPhrase() : message);
        if (extra != null) {
            out.putAll(extra);
        }
        return ResponseEntity.status(status).body(out);
    }

    /**
     * 给响应补一个 {@code Retry-After} 头，值是秒数。
     *
     * <p>下限取 1：算出 0 会让前端立刻重试，而立刻重试必然又被拒一次。
     */
    private static ResponseEntity<Map<String, Object>> withRetryAfter(
            long seconds, ResponseEntity<Map<String, Object>> base) {
        return ResponseEntity.status(base.getStatusCode())
                .header("Retry-After", Long.toString(Math.max(1, seconds)))
                .body(base.getBody());
    }
}

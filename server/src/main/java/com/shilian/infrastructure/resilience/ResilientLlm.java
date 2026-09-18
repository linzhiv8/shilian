package com.shilian.infrastructure.resilience;

import com.fasterxml.jackson.databind.JsonNode;
import com.shilian.analyze.DeepSeekClient;
import com.shilian.config.ShilianProperties;
import com.shilian.domain.port.ChatMessage;
import com.shilian.domain.port.LlmException;
import com.shilian.domain.port.LlmPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 给对话模型调用套上韧性：退避重试、熔断、时间预算。
 *
 * <p><b>为什么用装饰器而不是改 {@code DeepSeekClient}。</b>
 * 这三件事和「怎么发 HTTP 请求」毫无关系，塞进客户端里会让它同时承担两个职责。
 * 装饰器让 {@code DeepSeekClient} 只管发请求，而业务侧注入 {@code LlmPort}
 * 拿到的就是这个增强版——调用方一个字都不用知道。
 *
 * <p><b>为什么不用 Resilience4j。</b>这里只用到重试和熔断两个模式，
 * 加起来不到一百行。为一个库拉进一串传递依赖，和这个项目「用 JDK 的 HttpClient
 * 而不是 WebClient」的选择是矛盾的。真需要 RateLimiter、舱壁、指标导出那天再换不迟。
 *
 * <p><b>三类重试要分清</b>，只有第一类在这里处理：
 * <ol>
 *   <li><b>网络层失败</b>（连不上、超时、429、5xx）——本类负责，退避后重试。</li>
 *   <li><b>模型输出不合法</b>——那是业务重试，在 {@code AnalyzeService} 里，
 *       要把具体错误回灌给模型让它自己改，在这里重试毫无意义。</li>
 *   <li><b>参数错误、Key 无效</b>（4xx）——不重试，重试一万次结果一样。</li>
 * </ol>
 */
@Service
@Primary
public class ResilientLlm implements LlmPort {

    private static final Logger log = LoggerFactory.getLogger(ResilientLlm.class);

    private final LlmPort delegate;
    private final CircuitBreaker breaker;
    private final int maxAttempts;
    private final long backoffBaseMs;
    private final long budgetMs;

    /*
     * 必须显式标注 @Autowired。
     *
     * 这个类有两个构造器（下面那个是给测试用的），Spring 在「有多个构造器
     * 且没有一个被标注」时，会退回去找无参构造——找不到就启动失败，
     * 报的是 "No default constructor found"，完全看不出真正原因。
     * 标了它，选择就是唯一的。
     */
    @Autowired
    public ResilientLlm(DeepSeekClient delegate, ShilianProperties props) {
        ShilianProperties.Resilience cfg = props.resilience();
        this.delegate = delegate;
        this.breaker = new CircuitBreaker(cfg.failureThreshold(), cfg.openMs());
        this.maxAttempts = Math.max(1, cfg.maxAttempts());
        this.backoffBaseMs = Math.max(0, cfg.backoffBaseMs());
        this.budgetMs = cfg.budgetMs();
    }

    /** 测试用：可以注入假的 delegate 和假的时间源。 */
    ResilientLlm(LlmPort delegate, int maxAttempts, long backoffBaseMs, long budgetMs,
                 int failureThreshold, long openMs, java.util.function.LongSupplier nanoTime) {
        this.delegate = delegate;
        this.breaker = new CircuitBreaker(failureThreshold, openMs, nanoTime);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoffBaseMs = Math.max(0, backoffBaseMs);
        this.budgetMs = budgetMs;
    }

    @Override
    public ChatResult chat(List<ChatMessage> messages) throws IOException, InterruptedException {
        // 熔断打开时直接抛，不发请求——这就是「快速失败」的意义
        breaker.ensureClosed();

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        LlmException last = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ChatResult r = delegate.chat(messages);
                breaker.recordSuccess();
                return r;
            } catch (LlmException e) {
                breaker.recordFailure();
                last = e;

                if (!e.retryable()) {
                    log.debug("失败不可重试（status={}），直接放弃：{}", e.statusCode(), e.getMessage());
                    break;
                }
                if (attempt >= maxAttempts) {
                    break;
                }
                long waitMs = backoffFor(attempt);
                long remainingNanos = deadline - System.nanoTime();
                // 预算不够再走一轮了，就不等了——等完再发请求只会让用户等更久
                if (remainingNanos <= 0 || TimeUnit.MILLISECONDS.toNanos(waitMs) >= remainingNanos) {
                    log.warn("时间预算耗尽，放弃第 {} 次重试", attempt + 1);
                    break;
                }
                log.info("第 {} 次调用失败（{}），{} ms 后重试", attempt, e.getMessage(), waitMs);
                Thread.sleep(waitMs);
            }
        }
        throw last;
    }

    /**
     * 指数退避 + 抖动。
     *
     * <p>加抖动是必须的：不加的话，一批同时失败的请求会在同一毫秒集体重试，
     * 刚缓过来的上游立刻又被打垮——这叫重试风暴。抖动把重试时间摊开。
     */
    private long backoffFor(int attempt) {
        long exponential = backoffBaseMs * (1L << (attempt - 1));
        long jitter = backoffBaseMs > 0 ? ThreadLocalRandom.current().nextLong(backoffBaseMs / 2 + 1) : 0;
        return exponential + jitter;
    }

    @Override
    public JsonNode parseJson(String raw) {
        return delegate.parseJson(raw);
    }

    /** 给 /api/health 用：熔断器当前是不是开着的。 */
    public boolean isCircuitOpen() {
        return breaker.state() == CircuitBreaker.State.OPEN;
    }
}

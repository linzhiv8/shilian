package com.shilian.infrastructure.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 熔断器。
 *
 * <p><b>它解决的是什么问题。</b>上游挂掉时，如果每个请求都照样发出去等超时，
 * 用户每次都要干等十几秒才看到失败——而这段时间里他多半会再点一次，
 * 于是并发更高、上游更难恢复。熔断的做法是：连续失败够多次之后，
 * 一段时间内<b>直接快速失败</b>，不再消耗等待时间和用户额度。
 *
 * <p><b>三个状态。</b>
 * <pre>
 *   CLOSED ──连续失败达阈值──▶ OPEN ──冷却时间到──▶ HALF_OPEN
 *     ▲                                              │
 *     └────────────成功──────────────────────────────┘
 *                          （失败则立刻回 OPEN）
 * </pre>
 * HALF_OPEN 只放一个请求进去试探。试探成功就认为上游恢复了，
 * 失败则立刻回到 OPEN 继续冷却——避免「刚恢复就被瞬间打垮」。
 *
 * <p><b>为什么时间源是可注入的。</b>用 {@code System.nanoTime()} 直接读的话，
 * 「冷却 30 秒」这条规则就没法测，除非测试真等 30 秒。
 * 注入 {@link LongSupplier} 之后，测试里推一下时间就能验状态迁移。
 *
 * <p>状态变更全程 synchronized：多用户场景下并发是常态，
 * 而熔断器的状态如果算错了，保护就形同虚设。
 */
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    public enum State { CLOSED, OPEN, HALF_OPEN }

    /** 熔断打开时抛这个。它不是 IOException——这是我们自己决定不调用，不是调用失败。 */
    public static class OpenException extends RuntimeException {
        private final long retryAfterSeconds;

        public OpenException(String message, long retryAfterSeconds) {
            super(message);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    private final int failureThreshold;
    private final long openNanos;
    private final LongSupplier nanoTime;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private long openedAtNanos;

    public CircuitBreaker(int failureThreshold, long openMillis) {
        this(failureThreshold, openMillis, System::nanoTime);
    }

    public CircuitBreaker(int failureThreshold, long openMillis, LongSupplier nanoTime) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("失败阈值至少是 1");
        }
        this.failureThreshold = failureThreshold;
        this.openNanos = TimeUnit.MILLISECONDS.toNanos(openMillis);
        this.nanoTime = nanoTime;
    }

    /**
     * 允许调用就正常返回，否则抛 {@link OpenException}。
     *
     * <p>注意 HALF_OPEN 是放行的——它存在的意义就是放一个请求进去试探。
     */
    public synchronized void ensureClosed() {
        if (state == State.OPEN) {
            long elapsed = nanoTime.getAsLong() - openedAtNanos;
            if (elapsed < openNanos) {
                long remainingSec = Math.max(1,
                        TimeUnit.NANOSECONDS.toSeconds(openNanos - elapsed));
                throw new OpenException("AI 服务连续失败，已暂停调用 " + remainingSec + " 秒后再试",
                        remainingSec);
            }
            // 冷却结束，放一个请求进去试探
            state = State.HALF_OPEN;
            log.info("熔断器进入半开状态，放一个请求试探上游是否恢复");
        }
    }

    public synchronized void recordSuccess() {
        if (state != State.CLOSED) {
            log.info("熔断器关闭，上游已恢复");
        }
        consecutiveFailures = 0;
        state = State.CLOSED;
    }

    public synchronized void recordFailure() {
        consecutiveFailures++;
        // 半开状态下只要再失败一次，说明上游还没好，立刻回到打开并重新计时
        if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
            state = State.OPEN;
            openedAtNanos = nanoTime.getAsLong();
            log.warn("熔断器打开：连续失败 {} 次，{} ms 内不再调用上游",
                    consecutiveFailures, TimeUnit.NANOSECONDS.toMillis(openNanos));
        }
    }

    public synchronized State state() {
        return state;
    }

    public synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }
}

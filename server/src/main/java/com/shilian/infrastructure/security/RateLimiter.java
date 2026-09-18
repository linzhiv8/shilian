package com.shilian.infrastructure.security;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 滑动窗口限流。
 *
 * <p><b>为什么用滑动窗口而不是固定窗口。</b>
 * 固定窗口在窗口切换的那一刻会失效：1 分钟的窗口，
 * 在第 59 秒打满、第 61 秒又能打满，等于两秒内放过了两倍的量。
 * 对「限制注册频率」这种用途，攻击者只要卡着边界打就行。
 *
 * <p><b>计数放在内存里，重启即清零。</b>
 * 这不是偷懒：限流保护的是「刚刚有人刷了」这个短时状态，
 * 重启之后从零开始完全合理。而持久化它反而会引入
 * 「服务重启后仍拒绝正常用户」这种更难解释的问题。
 *
 * <p><b>时间源从外面传进来，是为了能测。</b>
 * 限流的正确性全在「窗口什么时候滑动」上，
 * 不注入一个可控的时钟，就只能靠 sleep 去赌，那测了等于没测。
 */
@Component
public class RateLimiter {

    /** 一次判定的结果。 */
    public record Decision(boolean allowed, long retryAfterSeconds) {
        public static final Decision OK = new Decision(true, 0);
    }

    private final LongSupplier nowMillis;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    /** 条目数超过这个量就顺手清一遍，避免长期运行下无限增长。 */
    private static final int CLEANUP_THRESHOLD = 500;

    public RateLimiter() {
        this(System::currentTimeMillis);
    }

    public RateLimiter(LongSupplier nowMillis) {
        this.nowMillis = nowMillis;
    }

    /**
     * 记一次尝试。
     *
     * @param key    限流维度，比如 {@code "register:127.0.0.1"}
     * @param limit  窗口内允许的次数
     * @param window 窗口长度
     */
    public synchronized Decision check(String key, int limit, Duration window) {
        long now = nowMillis.getAsLong();
        long windowMs = window.toMillis();

        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());

        // 滑出窗口的记录先扔掉，剩下的才是「当前窗口内」的。
        // 用 >= 而不是 >：窗口是半开区间 [t, t+W)，
        // 恰好走到 W 的那一刻就已经出界了。
        // 写成 > 会让边界多占 1 毫秒，而下面返回的秒数是整秒——
        // 客户端按提示等满整秒回来，正好卡在这 1 毫秒上又被拒一次。
        while (!q.isEmpty() && now - q.peekFirst() >= windowMs) {
            q.pollFirst();
        }

        if (q.size() >= limit) {
            long waitMs = windowMs - (now - q.peekFirst());
            // 向上取整：宁可多报 1 秒，也不能让「按你说的等了，还是进不来」发生。
            long seconds = (waitMs + 999) / 1000;
            return new Decision(false, Math.max(1, seconds));
        }

        q.addLast(now);
        if (hits.size() > CLEANUP_THRESHOLD) {
            evictExpired(now, windowMs);
        }
        return Decision.OK;
    }

    /** 清掉所有已经滑出窗口的 key。只在条目变多时才做，正常路径不付这个代价。 */
    private void evictExpired(long now, long windowMs) {
        List<String> dead = new ArrayList<>();
        for (Map.Entry<String, Deque<Long>> e : hits.entrySet()) {
            Deque<Long> q = e.getValue();
            while (!q.isEmpty() && now - q.peekFirst() >= windowMs) {
                q.pollFirst();
            }
            if (q.isEmpty()) {
                dead.add(e.getKey());
            }
        }
        dead.forEach(hits::remove);
    }
}

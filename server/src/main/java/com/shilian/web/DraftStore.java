package com.shilian.web;

import com.shilian.domain.AnalyzeOutcome;
import com.shilian.domain.port.CurrentUser;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * 分析草稿的短期缓存。
 *
 * <p>为什么需要它：分析接口和保存接口是分开的两步（先让用户看一眼再入库），
 * 中间要传递「正文快照」和「AI 原始输出」。这两样都不该绕一圈浏览器——
 * 快照可能 8000 字，让前端原样回传既浪费带宽，又给了前端篡改的机会。
 *
 * <p>换成在服务端存一份，前端只需要带一个 {@code draftId} 回来。
 *
 * <p>用有界 LRU 而不是 {@code ConcurrentHashMap}：用户分析完不保存就关掉页面的情况很常见，
 * 无上限的 map 会一直涨。上限 50 足够覆盖「一次分析几条、挑一条存」的正常用法。
 * 进程重启后草稿丢失是可接受的——它本来就是几秒钟的中间状态。
 */
@Component
public class DraftStore {

    private static final int MAX_ENTRIES = 50;

    /** {@code ownerId} 为 null 表示这份草稿是在没有登录会话的情况下产生的。 */
    public record Cached(AnalyzeOutcome outcome, Instant createdAt, String ownerId) {}

    private final Map<String, Cached> store = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private final CurrentUser current;

    public DraftStore(CurrentUser current) {
        this.current = current;
    }

    public synchronized String put(AnalyzeOutcome outcome) {
        String id = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        store.put(id, new Cached(outcome, Instant.now(), current.maybeId().orElse(null)));
        return id;
    }

    /**
     * 取草稿，只给主人。
     *
     * <p><b>为什么要绑定归属。</b>draftId 是 12 位随机字符，但它是**可猜测的**：
     * 多用户之后，A 只要试到 B 的 draftId，就能把 B 分析过的正文快照取出来，
     * 甚至用它去保存一条属于 A 的记录。这个漏洞不在数据库里，
     * 所以按 user_id 过滤的 SQL 拦不住它——只能在这里拦。
     *
     * <p>别人的草稿一律当作不存在，理由和 {@code LinkRepository.findById} 一样：
     * 「没有」比「有但不是你的」安全。
     */
    public synchronized Optional<Cached> get(String id) {
        if (id == null) {
            return Optional.empty();
        }
        Cached c = store.get(id);
        if (c == null || !isMine(c)) {
            return Optional.empty();
        }
        return Optional.of(c);
    }

    public synchronized void remove(String id) {
        if (id == null) {
            return;
        }
        Cached c = store.get(id);
        if (c != null && isMine(c)) {
            store.remove(id);
        }
    }

    private boolean isMine(Cached c) {
        String me = current.maybeId().orElse(null);
        return me != null && me.equals(c.ownerId());
    }
}

package com.shilian.domain.policy;

import com.shilian.domain.LinkItem;
import com.shilian.util.RelativeTime;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 回顾队列的准入判据。
 *
 * <p>原来这条规则是 SQL 里的一段字符串常量（{@code LinkRepository.REVIEW_WHERE}），
 * 靠 {@code julianday('now','localtime') - julianday(...)} 算天数差。三个问题：
 *
 * <ol>
 *   <li><b>不可测</b>——要验「放了 7 天的会不会进队列」，得真的往库里塞一条
 *       7 天前的记录再查库。</li>
 *   <li><b>依赖 SQLite 的 localtime</b>——规则的正确性绑死在某个数据库的函数上，
 *       换库或换时区就可能错，而且错得不声不响。</li>
 *   <li><b>「取一批」和「数总数」靠「共用同一个字符串常量」保证一致。</b>
 *       这种约定很脆弱：谁改了一处引用就会静默不一致，
 *       表现为侧栏说有 5 条、点进去只有 3 条。</li>
 * </ol>
 *
 * <p>抽成纯函数之后，两个查询调同一个方法，一致性由编译器保证，
 * 而 SQL 只剩下「按参数比大小」这一件它擅长的事。
 *
 * <p><b>为什么是 record。</b>它只有一个参数（闲置天数），没有身份，
 * 两个 {@code ReviewPolicy(7)} 就该相等。
 */
public record ReviewPolicy(int idleDays) {

    /*
     * 只有「看过了、别再推」这一种表态排除在队列外。
     *
     * V4 之前这里还有一个 'used'——那时候「已用」是 status 的取值。
     * 现在 used 是独立列，在下面的 isDue() 里单独判。
     * 分两处判不是啰嗦：这两件事的撤销方式不一样。
     * 标错了「已读」要手动改回来，而「已用」是随手来回拨的开关，
     * 取消之后它必须能立刻回到队列。
     */
    private static final Set<String> EXCLUDED_STATUS = Set.of("read");

    /**
     * 这条记录现在该不该进回顾队列。
     *
     * @param now 当前时间，由调用方注入，不在这里读系统时钟
     */
    public boolean isDue(LinkItem item, LocalDateTime now) {
        if (item == null) {
            return false;
        }
        if (item.starred()) {
            return false;
        }
        if (item.status() != null && EXCLUDED_STATUS.contains(item.status())) {
            return false;
        }
        /*
         * 用上了的不再提醒。判的是 used 这一列，不是 status——
         * 见本类顶部 EXCLUDED_STATUS 的注释：这样取消已用之后
         * 它会立刻回到队列，而靠 status 排除的话取消等于什么都没发生。
         */
        if (item.used()) {
            return false;
        }
        LocalDateTime reference = referenceTimeOf(item);
        if (reference == null) {
            return false;
        }
        return !reference.isAfter(cutoff(now));
    }

    /**
     * 时间分界线：参考时间早于或等于它的都算到期。
     *
     * <p>SQL 侧用 {@code <= cutoff} 直接比字符串。这个比较是安全的，
     * 因为存储格式定长（{@code yyyy-MM-dd'T'HH:mm:ss}）且字典序等于时间序。
     */
    public LocalDateTime cutoff(LocalDateTime now) {
        return now.minusDays(idleDays);
    }

    /**
     * 计时的起点：打开过就从「上次打开」算，没打开过就从「存下来」算。
     *
     * <p>这两者是两回事——打开原站只是重置计时（过 N 天还会再出现），
     * 而标成已读是明确表态「别再推给我」。所以 {@code last_opened_at}
     * 为空时必须退到 {@code created_at}，而不是当作「永不到期」。
     */
    public static LocalDateTime referenceTimeOf(LinkItem item) {
        LocalDateTime lastOpened = RelativeTime.parse(item.lastOpenedAt());
        return lastOpened != null ? lastOpened : RelativeTime.parse(item.createdAt());
    }
}

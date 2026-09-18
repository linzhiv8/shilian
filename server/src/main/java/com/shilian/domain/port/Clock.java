package com.shilian.domain.port;

import java.time.LocalDateTime;

/**
 * 时间源。
 *
 * <p><b>为什么要有它。</b>原来「现在几点」散在两个地方：Java 侧直接调
 * {@code LocalDateTime.now()}，SQL 侧用时间函数算。两处都隐式依赖系统时钟，
 * 导致「闲置 N 天该进回顾队列」这条规则没法测——想验它得真的往库里塞一条
 * N 天前的记录。注入之后，测试可以给定任意「现在」，判据退化成纯函数。
 *
 * <p>换到 MySQL 之后 SQL 侧那半已经不需要了：{@code LinkRepository.REVIEW_WHERE}
 * 改成「拿 Java 算好的时间点字符串比大小」，时区修正不再依赖数据库函数。
 * 判据本身在 {@code ReviewPolicy} 里，是纯 Java 纯函数，能直接单测。
 *
 * <p><b>⚠ 还有几处绕过它的地方。</b>{@code RelativeTime} 是静态工具类，
 * 里面的 {@code now()} 直接读系统时钟；{@code Migrations} 写 {@code applied_at}
 * 时也是。前者影响面大（很多地方在调），后者只用于审计字段，都还没改。
 * 新写的业务代码请注入这个接口，不要再直接调 {@code LocalDateTime.now()}。
 *
 * <p><b>用 LocalDateTime 而不是 Instant。</b>
 * 库里 {@code created_at} / {@code last_opened_at} 存的是本地时间字符串
 * （{@code yyyy-MM-dd'T'HH:mm:ss}，见 {@code RelativeTime.STORE}），
 * 换成 Instant 要动存储格式，那是独立的破坏性变更。
 */
public interface Clock {

    LocalDateTime now();
}

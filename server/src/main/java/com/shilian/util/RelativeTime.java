package com.shilian.util;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 时间展示与计算。
 *
 * <p>放在服务端算的理由：「2 天前」这种相对时间如果散在前端每个组件里，
 * 很容易出现卡片写「2 天前」、侧栏写「3 天前」的不一致。
 * 而且「多少天没打开」是本产品回顾机制的核心指标，值得有唯一实现。
 */
public final class RelativeTime {

    /** 存库统一用这个格式，定长且字典序等于时间序，可以直接 SQL 排序。 */
    public static final DateTimeFormatter STORE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private RelativeTime() {
    }

    /**
     * @deprecated 新代码请用注入的 {@code Clock.now()}，再交给 {@link #format} 落库。
     *     保留它是因为存量调用点还很多，一次性替换会超出阶段 0 的改动范围。
     */
    @Deprecated
    public static String now() {
        return LocalDateTime.now().format(STORE);
    }

    /** {@link #parse} 的反向操作，把时间写回库里用的格式。 */
    public static String format(LocalDateTime t) {
        return t == null ? null : t.format(STORE);
    }

    public static LocalDateTime parse(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(s, STORE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 「刚刚 / 12 分钟前 / 3 小时前 / 2 天前 / 2026-08-03」。 */
    public static String label(String storedTime) {
        LocalDateTime t = parse(storedTime);
        if (t == null) {
            return "";
        }
        long minutes = Duration.between(t, LocalDateTime.now()).toMinutes();
        if (minutes < 1) {
            return "刚刚";
        }
        if (minutes < 60) {
            return minutes + " 分钟前";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + " 小时前";
        }
        long days = hours / 24;
        if (days < 30) {
            return days + " 天前";
        }
        return t.toLocalDate().toString();
    }

    /**
     * 距离上次打开过了多少天。没打开过就用创建时间。
     * 用于卡片上的「放了 N 天没动」提示。
     */
    public static Integer idleDays(String sinceTime) {
        LocalDateTime t = parse(sinceTime);
        if (t == null) {
            return null;
        }
        return (int) Duration.between(t, LocalDateTime.now()).toDays();
    }
}

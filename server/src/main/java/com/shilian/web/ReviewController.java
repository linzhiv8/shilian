package com.shilian.web;

import com.shilian.analyze.WeeklyDigestService;
import com.shilian.analyze.WeeklyDigestService.Digest;
import com.shilian.repo.LinkRepository;
import com.shilian.web.dto.ReviewQueueResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 回顾。
 *
 * <p>两个端点是一对，所以放在同一个 Controller 里而不是塞进 LinkController：
 * <ul>
 *   <li>{@code /api/review} —— 「该回头看了」，抽几张卡</li>
 *   <li>{@code /api/review/weekly} —— 周报，一段 AI 写的摘要 + 统计</li>
 * </ul>
 *
 * <p>这组功能针对的是这个产品最大的隐性风险：<b>收藏了却再也不看</b>。
 * 绝大多数书签工具最后都变成数字坟场，因为用户永远在存、从不回顾。
 * 这不是靠「提醒你去看」能解决的，必须有真正的回顾出口。
 */
@RestController
@RequestMapping("/api/review")
public class ReviewController {

    /** 一次给几张。只给 3 张是刻意的：一次给 30 条等于没给，3 条才可能真的点开。 */
    private static final int DEFAULT_BATCH = 3;

    /**
     * 一次最多给几张。
     *
     * <p>比 {@code DEFAULT_BATCH} 大，是留给「今天想一口气清一批」的场景；
     * 再大就违背了这个功能的初衷——回顾要的是少量多次，不是把队列当列表刷。
     */
    private static final int MAX_BATCH = 20;

    /**
     * 「闲置多少天算该回顾」的上限，约十年。
     *
     * <p>设这个上限只是为了挡住 {@code ?days=999999999} 这种输入，
     * 不是为了业务正确性——十年这个数字本身没有含义。
     * 下限是 1 而不是 0：0 会让「今天刚存的」也进队列，队列就失去意义了。
     */
    private static final int MAX_DAYS = 3650;

    /** 天数的下限。理由见 {@link #MAX_DAYS}。 */
    private static final int MIN_DAYS = 1;

    /** 条数的下限。不能是 0：那等于让调用方自己决定「不返回」，不如统一按 1 条处理。 */
    private static final int MIN_BATCH = 1;

    private final LinkRepository repo;
    private final WeeklyDigestService weekly;

    public ReviewController(LinkRepository repo, WeeklyDigestService weekly) {
        this.repo = repo;
        this.weekly = weekly;
    }

    /**
     * 「该回头看了」。默认挑 7 天以上没动过、没星标、没标已用也没标已读的，
     * 随机抽 3 条。
     *
     * <p>随机而不是「最久的排最前」的理由见 {@code LinkRepository.dueForReview}。
     */
    @GetMapping
    public ReviewQueueResponse queue(
            @RequestParam(required = false, defaultValue = "7") int days,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_BATCH) int limit) {
        // 两头都要夹，不能只写 Math.min。
        // 漏了下限的话 limit=-5 会原样落进 SQL：在 SQLite 上这表示「不限量」
        // （一个「最多 3 条」的接口会把整张表倒出来），在 MySQL 上是语法错误直接 500。
        // 两种都错得很难往回追，所以上下限都用常量写死，别在这里留裸数字。
        int safeDays = clamp(days, MIN_DAYS, MAX_DAYS);
        int safeLimit = clamp(limit, MIN_BATCH, MAX_BATCH);
        return new ReviewQueueResponse(
                repo.dueForReview(safeDays, safeLimit),
                repo.countDueForReview(safeDays),
                safeDays);
    }

    /**
     * 周报。
     *
     * @param days    看最近多少天，默认 7
     * @param refresh 强制重写 AI 摘要（默认读本周缓存，不花 token）
     */
    @GetMapping("/weekly")
    public Digest weeklyDigest(
            @RequestParam(required = false, defaultValue = "" + WeeklyDigestService.WINDOW_DAYS) int days,
            @RequestParam(required = false, defaultValue = "false") boolean refresh) {
        int safeDays = clamp(days, MIN_DAYS, MAX_DAYS);
        // reviewDays 用来算「库里还欠着多少条」，和队列用同一个阈值
        return weekly.digest(safeDays, 7, refresh);
    }

    /**
     * 队列里有多少条。
     *
     * <p>侧栏要显示这个数字，但它不需要卡片内容。单独开一个端点而不是让前端
     * 拉一次 {@code /api/review?limit=1} 去读 {@code dueTotal}——
     * 「抽一张卡只为了数数」是说不通的，而且白做一次排序。
     *
     * <p>更重要的是：这个数字<b>必须由 SQL 算</b>。前端自己拿全量列表过滤
     * （排除星标、已用、已读，还要按闲置天数比较）等于把筛选条件抄了第二遍，
     * 一旦漂移就会出现「侧栏说有 5 条、点进去只有 3 条」。
     */
    @GetMapping("/count")
    public CountResponse count(
            @RequestParam(required = false, defaultValue = "7") int days) {
        int safeDays = clamp(days, MIN_DAYS, MAX_DAYS);
        return new CountResponse(repo.countDueForReview(safeDays), safeDays);
    }

    public record CountResponse(int dueTotal, int days) {
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(v, max));
    }
}

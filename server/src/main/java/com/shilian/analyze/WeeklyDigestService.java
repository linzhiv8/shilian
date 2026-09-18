package com.shilian.analyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shilian.repo.LinkRepository;
import com.shilian.repo.LinkRepository.DomainCount;
import com.shilian.repo.LinkRepository.WeeklyStats;
import com.shilian.domain.LinkItem;
import com.shilian.domain.port.ChatMessage;
import com.shilian.domain.port.Clock;
import com.shilian.domain.port.LlmPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.List;
import java.util.Optional;

/**
 * 周报：统计 + 一段 AI 写的摘要。
 *
 * <p>需求里写的是「每周摘要（AI 写的，不是列表）」——所以这里不能只把数字摆出来，
 * 要替用户说出「我这周在关注什么」。他存网址时往往说不清为什么存，
 * 也就看不出自己的方向。
 *
 * <p><b>摘要按周缓存，但缓存必须能被数据作废。</b>
 * 统计是几条 COUNT，毫秒级，每次重算反而不会过期；AI 摘要贵，要缓存。
 * 但窗口是<b>滑动的最近 N 天</b>，不是固定的一段历史——用户这周每存一条，
 * 输入就变了。所以缓存里连同一份「数据指纹」一起存：读缓存时对一下指纹，
 * 对不上就重写。否则会出现「摘要说这周存了 3 条，下面的统计写着 6」这种
 * 自相矛盾的页面——对一个只给自己用的工具来说，这种不可信是致命的。
 *
 * <p><b>AI 挂了不能让整个周报页挂掉。</b> 统计才是主体，摘要只是加值。
 * 所以调用失败时降级返回统计 + 一句说明，而不是抛 502。
 */
@Service
public class WeeklyDigestService {

    private static final Logger log = LoggerFactory.getLogger(WeeklyDigestService.class);

    /** 周报看最近多少天。7 天是「一周」的直白解释，不做自然周对齐——那样会出现「今天才周三，本周只有 2 条」的困惑。 */
    public static final int WINDOW_DAYS = 7;

    /** 指纹存在缓存 JSON 的这个字段里。带下划线，和模型输出的字段区分开。 */
    private static final String FP_FIELD = "_fp";

    private final LinkRepository repo;
    private final LlmPort llm;
    private final Clock clock;
    private final String systemPrompt;

    public WeeklyDigestService(LinkRepository repo, LlmPort llm, Clock clock) {
        this.repo = repo;
        this.llm = llm;
        this.clock = clock;
        this.systemPrompt = Prompts.load("weekly-prompt.md");
    }

    /**
     * 周报结果。
     *
     * @param cached      true 表示摘要直接读了缓存，这次没花 token
     * @param aiWritten   false 表示摘要不是模型写的（空周本地生成，或调用失败降级）
     * @param degraded    AI 调用失败时的说明，正常时为 null
     */
    public record Digest(
            String weekKey,
            boolean cached,
            boolean aiWritten,
            String degraded,
            String headline,
            String body,
            String observation,
            Stats stats
    ) {}

    /** 给前端用的统计视图。刻意只挑界面会显示的字段，不要把整个 WeeklyStats 摊出去。 */
    public record Stats(
            int saved,
            int opened,
            int used,
            int neverOpenedTotal,
            int total,
            int dueTotal,
            List<DomainCount> domains,
            List<LinkItem> newLinks
    ) {}

    public Digest digest(int days, int reviewDays, boolean refresh) {
        String weekKey = currentWeekKey();
        WeeklyStats s = repo.weeklyStats(days);
        Stats stats = new Stats(
                s.saved(), s.opened(), s.used(), s.neverOpenedTotal(), s.total(),
                repo.countDueForReview(reviewDays),
                s.topDomains(), s.newLinks());

        // 指纹用「喂给模型的那段用户消息」算。它正好是决定输出的全部输入：
        // 输入没变，输出必然一样，缓存才敢复用。
        String fp = fingerprint(stats);

        if (refresh) {
            int cleared = repo.clearDigestSummary(weekKey);
            log.info("强制重写周报 {}，清掉旧缓存 {} 条", weekKey, cleared);
        } else {
            Optional<String> hit = repo.findDigestSummary(weekKey);
            if (hit.isPresent()) {
                try {
                    JsonNode o = llm.parseJson(hit.get());
                    if (fp.equals(text(o, FP_FIELD))) {
                        return new Digest(weekKey, true, true, null,
                                text(o, "headline"), text(o, "body"), text(o, "observation"), stats);
                    }
                    // 指纹对不上：这段摘要说的是旧数字。继续往下重写，
                    // 而不是将就着用——摘要和统计互相打脸比没有摘要更糟。
                    log.info("周报缓存已过期（数据变了），重写 {}", weekKey);
                } catch (RuntimeException e) {
                    // 缓存坏了（手改过库、或上次写了个半截）。删掉重写，别让页面卡在这。
                    log.warn("周报缓存解析失败，删掉重写：{}", e.toString());
                    repo.clearDigestSummary(weekKey);
                }
            }
        }

        // 空周不调模型：结果可预测，没必要花一次调用换一句通用话术
        if (s.saved() == 0) {
            return emptyWeek(weekKey, stats);
        }

        try {
            LlmPort.ChatResult r = llm.chat(List.of(
                    ChatMessage.system(systemPrompt),
                    ChatMessage.user(buildUserMessage(stats))));
            repo.saveDigestSummary(weekKey, withFingerprint(r.content(), fp),
                    null, r.promptTokens(), r.completionTokens());
            log.info("周报已生成 {} · 输入 {} token 输出 {} token",
                    weekKey, r.promptTokens(), r.completionTokens());
            return parse(weekKey, r.content(), stats, false, null);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("周报 AI 调用失败，降级只给统计：{}", e.toString());
            return new Digest(weekKey, false, false,
                    "摘要这次没生成出来（" + e.getMessage() + "），下面的统计是准的。",
                    null, null, null, stats);
        }
    }

    /**
     * 数据指纹：把喂给模型的那段用户消息取 SHA-256 的前 8 字节。
     *
     * <p>不直接用「存了几条」当指纹——那太粗。用户改了一条备注、
     * 把某条标成已用，输入都变了，摘要也该跟着变。
     * 拿完整的模型输入做哈希，语义正好对上「输入相同才复用」。
     */
    private String fingerprint(Stats s) {
        String msg = buildUserMessage(s);
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest(msg.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", h[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须支持的算法，走不到这里。
            // 真走到了也不能崩——退化成「长度 + 内容哈希」，顶多多花一次调用。
            return "len" + msg.length() + "x" + Integer.toHexString(msg.hashCode());
        }
    }

    /**
     * 把指纹写进要缓存的那段 JSON 里。
     *
     * <p>为什么塞进 summary 字段而不是单开一列：单开列就要动表结构，
     * 而这个库已经跑起来了（schema.sql 是 CREATE TABLE IF NOT EXISTS，
     * 加不了列）。塞进 JSON 是自洽的——读的时候多取一个字段，
     * 渲染只认 headline/body/observation，多出来的 {@code _fp} 不影响显示。
     */
    private String withFingerprint(String raw, String fp) {
        try {
            ObjectNode o = (ObjectNode) llm.parseJson(raw);
            o.put(FP_FIELD, fp);
            return o.toString();
        } catch (RuntimeException e) {
            // 模型这次给的不是 JSON 对象（解析失败，或顶层是数组）。
            // 原样存下去，下次会当缓存未命中重写。
            log.warn("周报输出不是 JSON 对象，指纹没能写进缓存：{}", e.toString());
            return raw;
        }
    }

    /** 本周没存东西。本地生成，不花调用。 */
    private Digest emptyWeek(String weekKey, Stats stats) {
        String observation = stats.neverOpenedTotal() > 0
                ? "库里还有 " + stats.neverOpenedTotal() + " 条从存下来就没打开过，回头可以翻翻。"
                : "库里的东西基本都翻过了。";
        return new Digest(weekKey, false, false, null,
                "这周没存东西",
                "最近 " + WINDOW_DAYS + " 天一条都没存。",
                observation, stats);
    }

    /** 把模型输出（或缓存里的同一份 JSON）解析成 Digest。 */
    private Digest parse(String weekKey, String raw, Stats stats, boolean cached, String degraded) {
        JsonNode o = llm.parseJson(raw);
        return new Digest(weekKey, cached, true, degraded,
                text(o, "headline"), text(o, "body"), text(o, "observation"), stats);
    }

    private String currentWeekKey() {
        return weekKeyOf(clock.now().toLocalDate());
    }

    /**
     * ISO 周键，例如 {@code 2026-W38}。
     *
     * <p>用 ISO 周而不是「日期区间」：跨年那几天的周号归属很容易算错
     * （2027-01-01 可能属于 2026 的第 53 周），交给 {@code WeekFields.ISO} 处理。
     *
     * <p><b>为什么参数是 LocalDate 而不是直接读系统时钟。</b>
     * 上面那句「跨年归属很容易算错」正是最该被测的分支，而读系统时钟的话
     * 只有每年 12 月底那几天才跑得到——等于测不了。抽成纯函数之后
     * 给定任意日期都能验，调用方负责从 {@link Clock} 取「今天」。
     */
    static String weekKeyOf(LocalDate today) {
        WeekFields iso = WeekFields.ISO;
        int week = today.get(iso.weekOfWeekBasedYear());
        int year = today.get(iso.weekBasedYear());
        return String.format("%d-W%02d", year, week);
    }

    private String buildUserMessage(Stats s) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 最近 ").append(WINDOW_DAYS).append(" 天的统计\n\n");
        sb.append("- 新增：").append(s.saved()).append(" 条\n");
        sb.append("- 打开过：").append(s.opened()).append(" 条\n");
        sb.append("- 标为已用：").append(s.used()).append(" 条\n");
        sb.append("- 从存下来就没打开过的（全库）：").append(s.neverOpenedTotal()).append(" 条\n");
        sb.append("- 库里总共：").append(s.total()).append(" 条\n");

        if (!s.domains().isEmpty()) {
            sb.append("\n## 这周的领域分布\n\n");
            for (DomainCount d : s.domains()) {
                sb.append("- ").append(d.domain()).append("：").append(d.count()).append(" 条\n");
            }
        }

        sb.append("\n## 这周新增的网址\n\n");
        if (s.newLinks().isEmpty()) {
            sb.append("（无）\n");
        } else {
            int i = 1;
            for (LinkItem l : s.newLinks()) {
                sb.append(i++).append(". ").append(l.title()).append('\n');
                sb.append("   - 域名：").append(l.site()).append('\n');
                sb.append("   - 领域：").append(l.domainKey()).append('\n');
                if (l.summary() != null && !l.summary().isBlank()) {
                    sb.append("   - 一句话：").append(l.summary()).append('\n');
                }
                if (l.note() != null && !l.note().isBlank()) {
                    sb.append("   - 备注：").append(l.note()).append('\n');
                }
                if (l.starred()) {
                    sb.append("   - 已加星\n");
                }
            }
        }

        sb.append("\n请输出 JSON。");
        return sb.toString();
    }

    private static String text(JsonNode o, String field) {
        JsonNode n = o.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        String s = n.asText("").trim();
        return s.isEmpty() ? null : s;
    }
}

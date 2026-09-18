package com.shilian.analyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shilian.config.ShilianProperties;
import com.shilian.domain.AnalyzeDraft;
import com.shilian.domain.AnalyzeOutcome;
import com.shilian.domain.ContentType;
import com.shilian.domain.Domain;
import com.shilian.domain.FetchedPage;
import com.shilian.domain.Purpose;
import com.shilian.domain.port.ChatMessage;
import com.shilian.domain.port.FetcherPort;
import com.shilian.domain.port.LlmPort;
import com.shilian.util.Urls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 分析管线的编排：抓取 → 抽取 → 拼提示词 → 调模型 → 校验 → 不过就重试。
 *
 * <p><b>为什么要自动重试，而不是把提示词调到完美。</b>
 * 实验室里试过：把 note_options 的字数约束写得更死、加更多反例，
 * 但模型遇到「需要列举好几个文件名」这类内容时仍然会写超。
 * 继续调提示词的边际收益已经很低了，而且每次调都可能把别的场景调坏。
 * 换成「输出校验 + 把具体错误回灌给模型让它自己改」之后，
 * 一次重试就能把超长的那句压回区间内——因为它拿到了明确的失败原因。
 *
 * <p>重试上限默认 1 次。再多就是浪费 token：第一次改不好的，
 * 第三次大概率也改不好，而每次重试都要重发完整上下文。
 */
@Service
public class AnalyzeService {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeService.class);

    /**
     * 依赖全部是 {@code domain.port} 里的接口，不是具体实现类。
     *
     * <p>这样测试里可以注入一个假的模型：不联网、不花 token，
     * 还能精确控制它返回什么（比如「第三次才返回合法 JSON」），
     * 把重试路径这条平时很难触发的分支变成可穷举的普通用例。
     */
    private final ShilianProperties props;
    private final FetcherPort fetcher;
    private final PromptService promptService;
    private final LlmPort llm;
    private final ValidationService validation;
    private final CorrectionsFeed correctionsFeed;

    public AnalyzeService(ShilianProperties props,
                          FetcherPort fetcher,
                          PromptService promptService,
                          LlmPort llm,
                          ValidationService validation,
                          CorrectionsFeed correctionsFeed) {
        this.props = props;
        this.fetcher = fetcher;
        this.promptService = promptService;
        this.llm = llm;
        this.validation = validation;
        this.correctionsFeed = correctionsFeed;
    }

    /** 模型输出彻底不可用（连 JSON 都解析不出来）。 */
    public static class AnalysisFailedException extends RuntimeException {
        public AnalysisFailedException(String message) {
            super(message);
        }
    }

    /**
     * 贴进来的正文至少要这么长。
     *
     * <p>不是技术限制，是效果限制：模型靠正文判断这是什么站，
     * 二三十个字连一句话都没说完，判出来的分类还不如「只剩域名」那一档准，
     * 却会让用户以为「补了正文就有好结果」。拦住比放过去好。
     */
    private static final int MIN_PASTED_CHARS = 50;

    /** 各阶段耗时，都是毫秒。用 record 传是为了不把四个 long 摊在方法签名里。 */
    private record Timing(long fetchMs, long promptMs, long llmMs) {}

    private static long ms(long nanos) {
        return nanos / 1_000_000;
    }

    public AnalyzeOutcome analyze(String rawUrl) {
        return analyze(rawUrl, null);
    }

    /**
     * @param pastedText 用户粘贴的正文，用于抓取失败时补救。为空则照常抓取。
     */
    public AnalyzeOutcome analyze(String rawUrl, String pastedText) {
        // 先挡住明显不是网址的输入。放到抓取之后再判断就晚了——
        // 抓取失败会走「降级判断」路径去调 AI，等于拿 token 换一张废卡。
        String normalized = Urls.normalize(rawUrl);
        if (normalized == null) {
            throw new IllegalArgumentException("这不是一个有效的网址，检查一下再试");
        }

        long started = System.nanoTime();

        /*
         * 分段计时。目的不是做性能监控，而是让「这次为什么慢」有直接答案：
         * 这条链路上抓取和调模型都可能慢，而两者的对策完全不同
         * （换网络 vs 换模型/调提示词）。混成一个总时间就没法判断该动哪一头。
         */
        long fetchNanos = 0;
        long promptNanos = 0;
        long llmNanos = 0;

        boolean pasted = pastedText != null && !pastedText.isBlank();
        FetchedPage page;
        if (pasted) {
            String trimmed = pastedText.trim();
            if (trimmed.length() < MIN_PASTED_CHARS) {
                throw new IllegalArgumentException(
                        "贴的正文太短了（至少 " + MIN_PASTED_CHARS + " 字），把页面主要内容复制进来才判得准");
            }
            // 贴了正文就跳过抓取：他之所以会贴，正是因为抓取失败了，
            // 再等 20 秒去撞同一堵墙没有意义。
            page = FetchedPage.pasted(normalized, Urls.hostOf(normalized), trimmed,
                    props.fetch().maxChars());
            log.info("使用用户粘贴的正文分析 {} · 收到 {} 字，实际送模型 {} 字",
                    normalized, page.fullChars(), page.text().length());
        } else {
            long tFetch = System.nanoTime();
            page = fetcher.fetch(rawUrl);
            fetchNanos = System.nanoTime() - tFetch;
            if (!page.ok()) {
                log.info("抓取失败 {} → {}，仍继续分析（走降级判断）", rawUrl, page.error());
            }
        }

        long tPrompt = System.nanoTime();
        String corrections = correctionsFeed.block(10);
        String userMsg = promptService.buildUserMessage(page, page.url(), corrections);
        promptNanos = System.nanoTime() - tPrompt;

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(promptService.systemPrompt()));
        messages.add(ChatMessage.user(userMsg));

        int maxRepair = props.deepseek().maxRepair();
        int promptTokens = 0;
        int completionTokens = 0;
        List<String> lastErrors = List.of();
        List<String> repairLog = new ArrayList<>();
        String lastRaw = null;

        for (int attempt = 0; attempt <= maxRepair; attempt++) {
            LlmPort.ChatResult r;
            try {
                long tLlm = System.nanoTime();
                r = llm.chat(messages);
                // 累加：重试时是多次调用，总时间才是用户实际等待的时间
                llmNanos += System.nanoTime() - tLlm;
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new AnalysisFailedException("调用 AI 失败：" + e.getMessage());
            }

            promptTokens += r.promptTokens();
            completionTokens += r.completionTokens();
            lastRaw = r.content();

            JsonNode obj;
            try {
                obj = llm.parseJson(r.content());
            } catch (IllegalArgumentException e) {
                lastErrors = List.of("JSON 解析失败");
                repairLog.add("第 " + (attempt + 1) + " 次未过：返回的不是合法 JSON");
                if (attempt == maxRepair) {
                    throw new AnalysisFailedException("AI 连续 " + (attempt + 1) + " 次返回的不是合法 JSON");
                }
                log.warn("第 {} 次输出不是合法 JSON，追问一次", attempt + 1);
                messages.add(ChatMessage.assistant(r.content()));
                messages.add(ChatMessage.user(promptService.notJsonMessage()));
                continue;
            }

            List<String> errors = validation.validate(obj);
            boolean lastChance = attempt == maxRepair;

            if (errors.isEmpty()) {
                log.info("分析通过 · 用了 {} 次调用 · 输入 {} token 输出 {} token",
                        attempt + 1, promptTokens, completionTokens);
                return build(obj, List.of(), repairLog, page, attempt + 1,
                        promptTokens, completionTokens, started, lastRaw,
                        new Timing(ms(fetchNanos), ms(promptNanos), ms(llmNanos)));
            }

            lastErrors = errors;
            repairLog.add("第 " + (attempt + 1) + " 次未过：" + String.join("；", errors));

            if (lastChance) {
                // 重试次数用尽但内容还能用：不丢弃，标成需复核让用户自己看一眼。
                // 丢掉的话用户白等一次调用，而他其实只需要改一个下拉框。
                log.warn("重试次数用尽，仍存在 {} 处问题，降级为「需复核」保存：{}", errors.size(), errors);
                return build(obj, errors, repairLog, page, attempt + 1,
                        promptTokens, completionTokens, started, lastRaw,
                        new Timing(ms(fetchNanos), ms(promptNanos), ms(llmNanos)));
            }

            log.info("第 {} 次校验未过（{} 处），回灌错误后重试", attempt + 1, errors.size());
            messages.add(ChatMessage.assistant(r.content()));
            messages.add(ChatMessage.user(promptService.repairMessage(errors)));
        }

        // 循环理论上不会走到这（最后一次必定 return 或 throw）
        throw new AnalysisFailedException("分析未能产出结果：" + String.join("；", lastErrors));
    }

    private AnalyzeOutcome build(JsonNode o,
                                 List<String> errors,
                                 List<String> repairLog,
                                 FetchedPage page,
                                 int attempts,
                                 int promptTokens,
                                 int completionTokens,
                                 long startedNanos,
                                 String aiRaw,
                                 Timing timing) {
        long totalMs = (System.nanoTime() - startedNanos) / 1_000_000;
        log.info("分析完成 {} · {} ms（抓取 {} · 提示词 {} · 模型 {}）· {} 次调用 · {} token",
                page.url(), totalMs, timing.fetchMs(), timing.promptMs(), timing.llmMs(),
                attempts, promptTokens + completionTokens);

        boolean needsReview = o.path("needs_review").asBoolean(false);
        // 校验没过的输出，质量存疑，强制标记复核
        if (!errors.isEmpty()) {
            needsReview = true;
        }

        AnalyzeDraft draft = new AnalyzeDraft(
                page.url(),
                page.host(),
                Urls.monogram(page.url()),
                fallback(text(o, "title"), page.host()),
                text(o, "summary_short"),
                text(o, "summary_long"),
                stringList(o, "note_options"),
                sanitizeDomain(text(o, "domain")),
                sanitizePurposes(stringList(o, "purposes")),
                stringList(o, "tags"),
                sanitizeContentType(text(o, "content_type")),
                o.path("confidence").asDouble(0),
                needsReview,

                page.ok(),
                page.error(),
                page.fullChars(),
                page.description(),
                page.pasted() ? "pasted" : "fetched",

                attempts,
                errors,
                repairLog,
                promptTokens,
                completionTokens,
                (System.nanoTime() - startedNanos) / 1_000_000);

        return new AnalyzeOutcome(draft, aiRaw, page.text());
    }

    /*
     * 下面这几个 sanitize 是「最后一道闸」。
     *
     * 校验失败不代表就不返回了——重试用尽时我们会把结果降级返回给用户，
     * 那时候对象里可能带着越界的枚举值。如果不在这里兜住，前端拿到的就是
     * 一个 domainKey 不存在的卡片，轻则配色错乱，重则组件直接崩。
     *
     * 静默兜底是安全的：错误本身已经记在 validationErrors 里，
     * needsReview 也被强制置为 true，用户看得到「这条要复核」。
     */

    private static String fallback(String value, String or) {
        return value != null && !value.isBlank() ? value : or;
    }

    private static String sanitizeDomain(String domain) {
        return Domain.isValid(domain) ? domain : Domain.OTHER.code();
    }

    private static List<String> sanitizePurposes(List<String> purposes) {
        List<String> out = purposes.stream()
                .filter(Purpose::isValid)
                .distinct()
                .limit(3)
                .toList();
        // 一个都没有时给个最保守的默认值，别让卡片上的用途栏空着
        return out.isEmpty() ? List.of(Purpose.TOREAD.code()) : out;
    }

    private static String sanitizeContentType(String contentType) {
        return ContentType.isValid(contentType) ? contentType : ContentType.OTHER.label();
    }

    private static String text(JsonNode o, String field) {
        JsonNode n = o.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        String s = n.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    private static List<String> stringList(JsonNode o, String field) {
        JsonNode n = o.get(field);
        if (n == null || !n.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(n.size());
        for (JsonNode item : n) {
            String s = item.asText("").trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }
}

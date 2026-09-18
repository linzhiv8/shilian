package com.shilian.web;

import com.shilian.analyze.AnalyzeService;
import com.shilian.config.ShilianProperties;
import com.shilian.domain.AnalyzeDraft;
import com.shilian.domain.AnalyzeOutcome;
import com.shilian.domain.Domain;
import com.shilian.domain.LinkItem;
import com.shilian.domain.Purpose;
import com.shilian.repo.LinkRepository;
import com.shilian.util.RelativeTime;
import com.shilian.util.Urls;
import com.shilian.web.dto.AnalyzeResponse;
import com.shilian.web.dto.PatchLinkRequest;
import com.shilian.web.dto.QuickSaveRequest;
import com.shilian.web.dto.ReanalyzeRequest;
import com.shilian.web.dto.SaveLinkRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 链接的增删改查，以及给已存记录补正文。 */
@RestController
@RequestMapping("/api/links")
public class LinkController {

    private final LinkRepository repo;
    private final DraftStore drafts;
    private final AnalyzeService analyzeService;
    private final ShilianProperties props;

    public LinkController(LinkRepository repo, DraftStore drafts,
                          AnalyzeService analyzeService, ShilianProperties props) {
        this.repo = repo;
        this.drafts = drafts;
        this.analyzeService = analyzeService;
        this.props = props;
    }

    /* ────────────── 保存 ────────────── */

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LinkItem save(@Valid @RequestBody SaveLinkRequest req) {
        AnalyzeOutcome outcome = requireDraft(req.draftId());
        AnalyzeDraft d = outcome.draft();
        Resolved r = resolve(req, d);

        LinkRepository.NewLink toSave = new LinkRepository.NewLink(
                d.url(),
                d.site(),
                null,
                r.title(),
                r.summary(),
                r.summaryLong(),
                r.note(),
                r.noteOptions(),
                r.domainKey(),
                r.purposes(),
                r.tags(),
                r.contentType(),
                r.confidence(),
                r.needsReview(),
                outcome.snapshotText(),
                outcome.aiRaw(),
                d.attempts(),
                d.promptTokens(),
                d.completionTokens());

        LinkItem saved = repo.insert(toSave);

        if (r.markedUsed()) {
            saved = repo.patch(saved.id(), Map.of("status", "used")).orElse(saved);
        }

        logAiCall(saved.id(), d);
        drafts.remove(req.draftId());
        return saved;
    }

    /**
     * 跳过 AI，直接存一条网址。
     *
     * <p><b>这是 AI 不可用时的出路。</b>熔断打开、Key 没配、上游抽风的时候，
     * 用户点完分析等半天却什么都没存下来——那是整个产品最让人泄气的时刻。
     * 有了这个接口，他至少能把网址先记下来。
     *
     * <p><b>为什么不做成「{@code POST /api/links} 的可选分支」。</b>
     * 主接口强依赖 draftId，因为它的语义就是「保存一次分析的结果」。
     * 往里塞一个「也可以不要草稿」的分支，会让那个接口的语义变成
     * 「要么带草稿要么不带，行为完全不同」——调用方得多判断一层，
     * 校验也得分叉。分开成两个接口，各自的契约都是一句话能说清的。
     *
     * <p>存下来的记录带 {@code needs_review}，之后可以用现有的
     * {@code /api/links/{id}/reanalyze} 补分析，闭环是通的。
     */
    @PostMapping("/quick")
    @ResponseStatus(HttpStatus.CREATED)
    public LinkItem quickSave(@Valid @RequestBody QuickSaveRequest req) {
        String url = Urls.normalize(req.url());
        if (url == null) {
            throw new IllegalArgumentException("这不是一个有效的网址，检查一下再试");
        }
        return repo.insertQuick(new LinkRepository.QuickSave(url, req.title()));
    }

    /* ────────────── 给已存的记录补正文 ────────────── */

    /**
     * 给一条已存的记录重跑分析。
     *
     * <p>两个用途共用一个入口：
     * <ul>
     *   <li><b>不带 {@code text}</b> —— 重新抓一次。抓取失败常常是暂时的，
     *       过几天再试可能就好了。</li>
     *   <li><b>带 {@code text}</b> —— 用户把正文贴进来了。这才是主要用途：
     *       一张「待补」的卡片，事后终于能补上内容。</li>
     * </ul>
     *
     * <p><b>刻意不改库。</b> 重分析会把标题、摘要、分类整套重写一遍，
     * 直接落库等于把用户之前手动改过的东西无声覆盖掉。所以仍然走
     * 「先出草稿、用户看一眼、再应用」这条老路——只是这次的应用是覆盖而不是插入。
     *
     * <p>这也补上了全应用最后一个「用户遇到问题却无解」的场景：
     * 之前只有<b>新收藏时</b>能贴正文，一张卡片存下来之后就没有补的入口了。
     */
    @PostMapping("/{id}/reanalyze")
    public AnalyzeResponse reanalyze(@PathVariable String id,
                                     @RequestBody(required = false) ReanalyzeRequest req) {
        LinkItem link = repo.findById(id)
                .orElseThrow(() -> new NotFoundException("找不到这条记录"));
        // 网址从记录里取，不用前端传——避免「重分析」变成「偷偷把这条记录指向别的网址」
        AnalyzeOutcome outcome = analyzeService.analyze(link.url(), req == null ? null : req.text());
        return new AnalyzeResponse(drafts.put(outcome), outcome.draft());
    }

    /**
     * 把一次重分析的结果写到已存记录上。
     *
     * <p>请求体和 {@link SaveLinkRequest} 完全一样，所以直接复用——
     * 用户在面板里能改的东西，新建和补正文时是一样的，没必要多一套 DTO 去漂移。
     */
    @PostMapping("/{id}/apply")
    public LinkItem applyReanalysis(@PathVariable String id, @Valid @RequestBody SaveLinkRequest req) {
        AnalyzeOutcome outcome = requireDraft(req.draftId());
        AnalyzeDraft d = outcome.draft();
        Resolved r = resolve(req, d);

        LinkItem updated = repo.replaceAnalysis(id, new LinkRepository.Reanalysis(
                        r.title(),
                        r.summary(),
                        r.summaryLong(),
                        r.note(),
                        r.noteOptions(),
                        r.domainKey(),
                        r.purposes(),
                        r.tags(),
                        r.contentType(),
                        r.confidence(),
                        r.needsReview(),
                        outcome.snapshotText(),
                        outcome.aiRaw(),
                        d.attempts(),
                        d.promptTokens(),
                        d.completionTokens()))
                .orElseThrow(() -> new NotFoundException("找不到这条记录"));

        if (r.markedUsed()) {
            updated = repo.patch(id, Map.of("status", "used")).orElse(updated);
        }

        logAiCall(id, d);
        drafts.remove(req.draftId());
        return updated;
    }

    /* ────────────── 查询 ────────────── */

    @GetMapping
    public List<LinkItem> list(@RequestParam(required = false) String domain,
                               @RequestParam(required = false) String purposes,
                               @RequestParam(required = false) String q,
                               @RequestParam(required = false, defaultValue = "recent") String sort) {
        List<String> purposeList = (purposes == null || purposes.isBlank())
                ? List.of()
                : Arrays.stream(purposes.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return repo.search(domain, purposeList, q, sort);
    }

    /*
     * 回顾队列不在这里，在 ReviewController（/api/review）。
     * 它不是「链接列表的一个变体」——返回结构不同（带队列总数），
     * 而且和 /api/review/weekly 是一对。放在一起更好找。
     */

    @GetMapping("/{id}")
    public LinkItem get(@PathVariable String id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("找不到这条记录"));
    }

    /* ────────────── 修改与删除 ────────────── */

    @PatchMapping("/{id}")
    public LinkItem patch(@PathVariable String id, @RequestBody PatchLinkRequest req) {
        Map<String, Object> changes = new LinkedHashMap<>();
        /*
         * 标题和摘要留了非空校验，不像别的字段那样直接透传。
         *
         * 理由：这两个是卡片上唯一必须存在的文字。清空之后卡片会变成一行没有标题的
         * 方块，用户只会觉得「我的记录坏了」。想改就改，但不能改成空的。
         */
        if (req.title() != null && !req.title().isBlank()) {
            changes.put("title", req.title().trim());
        }
        if (req.summaryShort() != null) {
            changes.put("summary_short", req.summaryShort().trim());
        }
        if (req.note() != null) {
            changes.put("note", req.note());
        }
        if (req.domainKey() != null && Domain.isValid(req.domainKey())) {
            changes.put("domain_category", req.domainKey());
        }
        if (req.purposes() != null) {
            changes.put("purpose_categories", sanitizePurposes(req.purposes()));
        }
        if (req.tags() != null) {
            changes.put("tags", req.tags());
        }
        if (req.starred() != null) {
            changes.put("starred", req.starred());
        }
        if (req.status() != null && List.of("unread", "read", "used").contains(req.status())) {
            changes.put("status", req.status());
        }
        if (Boolean.TRUE.equals(req.markOpened())) {
            changes.put("last_opened_at", RelativeTime.now());
        }

        return repo.patch(id, changes)
                .orElseThrow(() -> new NotFoundException("找不到这条记录"));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        if (!repo.delete(id)) {
            throw new NotFoundException("找不到这条记录");
        }
    }

    /* ────────────── 小工具 ────────────── */

    /**
     * 「用户确认后的字段」和「AI 草稿」合并的最终结果。
     *
     * <p>抽出来是为了让「新建保存」和「补正文替换」走同一套规则。两处各写一遍的话，
     * 迟早出现「新建时默认挑第二句备注、补正文时挑了第一句」这种没人会发现的不一致。
     */
    private record Resolved(
            String title, String summary, String summaryLong,
            String note, List<String> noteOptions,
            String domainKey, List<String> purposes, List<String> tags,
            String contentType, double confidence, boolean needsReview,
            boolean markedUsed) {}

    private static Resolved resolve(SaveLinkRequest req, AnalyzeDraft d) {
        // 用户在面板里可能把「已用」勾进用途里。它本质是状态不是用途，这里统一归位：
        // 从 purposes 里摘掉，改成 status='used'。否则同一件事会有两个地方表达，
        // 迟早出现「用途显示已用但状态还是未读」这种自相矛盾的数据。
        List<String> requestedPurposes = req.purposes() != null ? req.purposes() : d.purposes();

        return new Resolved(
                pick(req.title(), d.title()),
                pick(req.summary(), d.summary()),
                pick(req.summaryLong(), d.summaryLong()),
                pick(req.note(), firstNote(req.noteOptions(), d.noteOptions())),
                req.noteOptions() != null ? req.noteOptions() : d.noteOptions(),
                Domain.isValid(req.domainKey()) ? req.domainKey() : d.domainKey(),
                sanitizePurposes(requestedPurposes),
                req.tags() != null ? req.tags() : d.tags(),
                pick(req.contentType(), d.contentType()),
                req.confidence() != null ? req.confidence() : d.confidence(),
                req.needsReview() != null ? req.needsReview() : d.needsReview(),
                requestedPurposes.contains("used"));
    }

    private AnalyzeOutcome requireDraft(String draftId) {
        return drafts.get(draftId)
                .orElseThrow(() -> new DraftExpiredException("这次分析的草稿已过期，请重新分析一次"))
                .outcome();
    }

    /**
     * 记一条 AI 调用日志。
     *
     * <p>errors 记 repairLog 而不是 validationErrors：后者在「第一次错、重试后对了」
     * 这种最常见的情况下是空的，而那恰恰是最值得留痕的情况。
     */
    private void logAiCall(String linkId, AnalyzeDraft d) {
        repo.insertAiLog(linkId, props.deepseek().model(), d.attempts(),
                d.promptTokens(), d.completionTokens(), d.validationErrors().isEmpty(),
                d.repairLog().isEmpty() ? null : String.join(" | ", d.repairLog()));
    }

    /** 过滤掉非法枚举值。用户可能提交脏数据，不能让它进库。 */
    private static List<String> sanitizePurposes(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String p : raw) {
            if (Purpose.isValid(p) && !out.contains(p)) {
                out.add(p);
            }
        }
        return out.size() > 3 ? out.subList(0, 3) : out;
    }

    private static String pick(String override, String fallback) {
        return override != null && !override.isBlank() ? override : fallback;
    }

    /** 用户没挑备注时，默认用三句里的第二句——「什么时候会用到」通常最有提醒价值。 */
    private static String firstNote(List<String> fromRequest, List<String> fromDraft) {
        List<String> list = fromRequest != null && !fromRequest.isEmpty() ? fromRequest : fromDraft;
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.size() >= 2 ? list.get(1) : list.get(0);
    }

    /** 资源不存在。 */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }
}

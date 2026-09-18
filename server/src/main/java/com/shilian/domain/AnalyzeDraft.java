package com.shilian.domain;

import java.util.List;

/**
 * 分析结果草稿。<b>还没落库</b>——先给前端看，用户点「就这样存」才写进数据库。
 *
 * <p>这个「先草稿后保存」的两段式是产品设计的关键：
 * AI 的输出天然会有偏差，直接入库等于把不确定性推给用户事后修正；
 * 而让用户存之前扫一眼，修正成本几乎为零。
 *
 * <p>{@code fetchOk} / {@code bodyChars} / {@code attempts} / token 用量
 * 都是给用户看的透明度信息：他要能知道「这次为什么判得不准」。
 */
public record AnalyzeDraft(
        String url,
        /** 主机名（去 www），卡片上显示的那行小字。 */
        String site,
        /**
         * 卡片左上角的字母块。
         *
         * <p>放在草稿里而不是让前端自己算：保存后的卡片上这个值由服务端生成，
         * 如果预览时前端算一套、保存后服务端算另一套，用户会看到字母块在点「存」的瞬间跳变。
         */
        String monogram,
        String title,
        String summary,
        String summaryLong,
        List<String> noteOptions,
        String domainKey,
        List<String> purposes,
        List<String> tags,
        String contentType,
        double confidence,
        boolean needsReview,

        /* ── 抓取层实况，用于前端提示与排查 ── */
        /**
         * 是否拿到了可用的正文。
         *
         * <p>注意它不等于「抓取成功」：用户贴正文时这里也是 {@code true}，
         * 因为抓取根本没发生，但正文是有的。前端那句话说得很准——
         * 「正文没抓到」在贴了正文的情况下本来就不成立。
         * 要区分「自己抓的」和「用户贴的」，看下面的 {@code bodySource}。
         */
        boolean fetchOk,
        String fetchError,
        int bodyChars,
        String metaDescription,
        /**
         * 正文来源：{@code "fetched"} 或 {@code "pasted"}。
         *
         * <p>前端要靠它决定显示哪一种提示，因为两种情况下「缺什么」是相反的：
         * 抓取失败是缺正文，用户贴的则是缺标题和描述。
         * 只看 {@code fetchOk} 分不出来——贴正文时抓取根本没发生。
         */
        String bodySource,

        /* ── AI 层实况 ── */
        int attempts,
        /** 最终仍未解决的问题。为空表示通过。 */
        List<String> validationErrors,
        /**
         * 每次重试的原因。
         *
         * <p>为什么必须留着：重试是要花钱的，而用户只看到「这次慢了一点」。
         * 更关键的是调提示词时，「哪条规则最常被违反」是唯一的指路信号——
         * 如果只在最终失败时记录，那么「第一次错、重试后对了」这类最典型的
         * 情况反而什么都不会留下，等于把最有价值的数据丢了。
         */
        List<String> repairLog,
        int promptTokens,
        int completionTokens,
        long elapsedMs
) {
}

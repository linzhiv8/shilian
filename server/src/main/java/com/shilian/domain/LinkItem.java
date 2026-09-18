package com.shilian.domain;

import java.util.List;

/**
 * 前端卡片的数据形状。
 *
 * <p>字段名刻意与前端 {@code src/types.ts} 的 {@code LinkItem} 一一对应（camelCase），
 * 这样前端接后端只需把 {@code src/data/mock.ts} 换成一个 fetch，不用写映射层。
 *
 * <p>{@code savedLabel} 和 {@code idleDays} 是展示字段，由服务端算好：
 * 「2 天前」这种相对时间的计算散在前端每个组件里很容易写歪，
 * 而且它依赖创建时间，服务端本来就有。
 */
public record LinkItem(
        String id,
        String url,
        String site,
        String title,
        String summary,
        String summaryLong,
        String note,
        List<String> noteOptions,
        String domainKey,
        List<String> purposes,
        List<String> tags,
        String contentType,
        String savedLabel,
        Integer idleDays,
        boolean starred,
        String status,
        /**
         * 用没用上。和 {@code status} 是两件事，见 {@code V4__review.sql} 的说明。
         *
         * <p>{@code status} 回答「看过了没有 / 还要不要再推给我」，
         * 这一列回答「我有没有真的用上」。分开是因为后者要能来回拨：
         * 标错了得能撤，而混在 status 里时撤回去不知道该回 unread 还是 read。
         */
        boolean used,
        double confidence,
        boolean needsReview,
        /**
         * 分析进行到哪一步：{@code pending} 还没分析过，{@code done} 分析过了。
         *
         * <p>这一列以前写了但没人读，等于不存在。让它可读是为了区分两种「待补」：
         * <ul>
         *   <li>{@code pending} —— 从没分析过（跳过 AI 直接存的那种），
         *       它缺的是「整次分析」，不只是正文。</li>
         *   <li>{@code done} + {@code needsReview} —— 分析跑过了但没抓到正文。</li>
         * </ul>
         * 两者的补救动作是同一个（贴正文重跑），但<b>原因不一样</b>，
         * 界面上说错原因，用户会以为系统在瞎判断。
         */
        String analyzeStatus,
        String monogram,
        String createdAt,
        String lastOpenedAt
) {
}

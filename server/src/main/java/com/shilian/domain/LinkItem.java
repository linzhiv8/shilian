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
        double confidence,
        boolean needsReview,
        String monogram,
        String createdAt,
        String lastOpenedAt
) {
}

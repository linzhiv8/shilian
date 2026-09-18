package com.shilian.web.dto;

import com.shilian.domain.LinkItem;

import java.util.List;

/**
 * 回顾队列。
 *
 * <p>比单纯返回一个数组多了 {@code dueTotal}。因为界面要显示两件事：
 * 这一批有几张（{@code items}），以及库里还欠着多少（{@code dueTotal}）。
 * 只给数组的话，前端得自己算总数，而它的算法和 SQL 里那套筛选条件
 * （排除星标、已用、已读，还要算闲置天数）很难保持一致——
 * 一旦漂移，就会出现「侧栏说有 5 条、点进去只有 3 条」。
 */
public record ReviewQueueResponse(
        List<LinkItem> items,
        /** 队列里一共多少条（不只是这一批） */
        int dueTotal,
        /** 闲置多少天才算「该回头看了」 */
        int days
) {
}

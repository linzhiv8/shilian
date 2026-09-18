package com.shilian.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 保存请求：用户在保存面板里确认或改过之后的字段。
 *
 * <p>注意这里<b>没有</b> url / snapshotText / aiRaw / token 用量。
 * 这些都在服务端草稿里，前端不需要（也不应该）经手。
 * 前端只提交它真正能让用户改的东西。
 */
public record SaveLinkRequest(
        @NotBlank(message = "缺少 draftId，请重新分析一次")
        String draftId,

        String title,
        String summary,
        String summaryLong,
        List<String> noteOptions,

        /** 用户从三句备注里挑中的那句（或自己改写的）。可以为空，表示还没想好。 */
        String note,

        String domainKey,
        List<String> purposes,
        List<String> tags,
        String contentType,
        Double confidence,
        Boolean needsReview
) {
}

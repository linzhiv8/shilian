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
        Boolean needsReview,

        /**
         * 存下来的时候就已经用上了。V4 起「已用」是独立列，不再靠
         * 「往 purposes 里塞一个 used」这种旁门表达——
         * 那条路的副作用是：purposes 是 AI 判的、会被引擎当分类用，
         * 塞一个状态值进去等于往分类里混了一个不是分类的东西。
         */
        Boolean used
) {
}

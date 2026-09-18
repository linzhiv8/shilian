package com.shilian.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 分析请求。
 *
 * <p>{@code text} 是可选的补救入口：抓不到正文的站点（SPA、需登录、Cloudflare）
 * 在日常里占比不低，那时用户在自己浏览器里明明能读到内容。
 * 让他把正文贴进来，我们就能照常分析，而不是只留一张低置信度的占位卡。
 *
 * <p>贴了正文就<b>不再去抓取</b>。理由：他之所以会贴，正是因为抓取失败了，
 * 再等 20 秒去撞同一堵墙没有意义；而且他贴的正文比抓取结果更完整。
 */
public record AnalyzeRequest(
        @NotBlank(message = "请填一个网址")
        String url,

        /** 可选。用户粘贴的页面正文，用于抓取失败时补救。 */
        String text
) {
}

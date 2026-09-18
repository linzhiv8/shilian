package com.shilian.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 跳过 AI、直接存一条网址。
 *
 * <p>为什么需要这条路径：AI 挂掉的时候，用户最想要的不是一句「失败了」，
 * 而是「至少别让我白跑一趟」。有了这条路径，熔断期间他照样能把网址存下来，
 * 等 AI 恢复了再用现成的「补分析」入口补上——
 * 那条路本来就有（{@code POST /api/links/{id}/reanalyze}），只是以前没东西可补。
 *
 * @param url   必须。没有网址就没有这条记录
 * @param title 可以没有。没给就用域名顶上，用户事后能改
 */
public record QuickSaveRequest(
        @NotBlank(message = "网址不能为空") String url,
        String title
) {}

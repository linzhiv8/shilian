package com.shilian.web.dto;

import com.shilian.domain.AnalyzeDraft;

/**
 * 分析响应。带上 {@code draftId}，保存时把它带回来即可，
 * 不用把正文快照和 AI 原始输出回传。
 */
public record AnalyzeResponse(String draftId, AnalyzeDraft draft) {
}

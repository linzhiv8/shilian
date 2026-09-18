package com.shilian.domain;

/**
 * 分析产出：给前端看的 {@link AnalyzeDraft}、模型的原始输出、以及正文快照。
 *
 * <p>为什么要留 {@code aiRaw}：用户之后把 AI 的判断改掉时，
 * 要能算准「AI 原本判的是什么 → 用户改成了什么」，这个差值就是最真实的偏好信号。
 * 只存「当前值」的话，改过一次就再也还原不出原始判断了。
 *
 * <p>为什么要留 {@code snapshotText}：网页会死、会改版、会变成付费墙。
 * 存一份正文快照，两年后打开还能知道当初为什么收藏它。
 * 它同时是「AI 判断错了」时唯一能重新分析的材料。
 */
public record AnalyzeOutcome(AnalyzeDraft draft, String aiRaw, String snapshotText) {
}

package com.shilian.web.dto;

import java.util.List;

/**
 * 枚举字典。让前端不用把领域/用途的中文名再抄一份，避免两边改不同步
 * （之前 {@code biz} 的标签就是这么漂掉的）。
 *
 * <p>不返回颜色：颜色是前端的设计令牌，属于表现层，服务端不该管。
 */
public record MetaResponse(
        List<Option> domains,
        List<Option> purposes,
        List<String> contentTypes,
        boolean aiConfigured,
        String model,
        Semantic semantic
) {
    public record Option(String code, String label) {
    }

    /**
     * 语义搜索可不可用。
     *
     * <p>为什么要专门告诉前端，而不是让它点一下试试：语义搜索需要的是一个
     * <b>独立的向量服务</b>（DeepSeek 没有 embeddings 接口），所以「AI 能用」
     * 和「语义搜索能用」是两件事。不告诉的话，前端只能把按钮点亮，
     * 用户点了拿到一个 503——他会以为是搜索坏了，而不是「少配了一个 Key」。
     *
     * @param reason 不可用时的原因，一句能照着做的话；可用时为 null
     */
    public record Semantic(boolean available, String reason, String model, double minScore) {
    }
}

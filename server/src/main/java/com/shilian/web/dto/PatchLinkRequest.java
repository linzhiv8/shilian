package com.shilian.web.dto;

import java.util.List;

/**
 * 局部更新请求。所有字段可为 null，表示这一项不改。
 *
 * <p>这个「null 即不改」的约定让前端可以只提交用户真正动过的那一个字段，
 * 不会因为表单里其它字段没填而把已有数据清空。
 */
public record PatchLinkRequest(
        /**
         * 标题。用户手动改过之后会往 correction 表留一条痕——
         * 「他把 AI 起的标题改成了什么」是调提示词时最有用的证据。
         */
        String title,
        String summaryShort,
        String note,
        String domainKey,
        List<String> purposes,
        List<String> tags,
        Boolean starred,
        String status,
        /**
         * 用没用上。V4 起它是独立列，不再是 status 的取值。
         *
         * <p>和 {@code status} 分开收，是因为两者的撤销方式不同：
         * {@code status} 是「还要不要再推给我」这种一次性的表态，
         * 而这个是随手来回拨的开关，前端会频繁地只提交这一个字段。
         */
        Boolean used,
        /** 前端打开链接时置 true，用于「多久没看了」的判断。 */
        Boolean markOpened
) {
}

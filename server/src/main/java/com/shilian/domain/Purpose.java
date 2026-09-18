package com.shilian.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 用途分类。多选，1–3 个。
 *
 * <p>刻意<b>不包含</b> {@code used}（已用）：那是用户自己的使用状态，
 * 模型无从判断，让它去猜只会污染数据。用户在前端手动切换。
 * 校验层对 {@code used} 有专门的越界检查，见 {@link #USER_ONLY}。
 *
 * <p>和 {@link Domain} 的分工是本产品的核心概念区分：
 * domain 回答「这属于哪个领域」，purposes 回答「你打算拿它干嘛」。
 * 同一个站点可以既是 docs 又是 tutorial，但领域只有一个。
 */
public enum Purpose {
    TOOL("tool", "工具"),
    TUTORIAL("tutorial", "教程"),
    INSPIRATION("inspiration", "灵感"),
    NEWS("news", "资讯"),
    DOCS("docs", "文档"),
    ASSET("asset", "素材"),
    TOREAD("toread", "待读");

    /** 只属于用户的状态值，模型输出里出现即为违规。 */
    public static final List<String> USER_ONLY = List.of("used");

    private final String code;
    private final String label;

    Purpose(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static Optional<Purpose> of(String code) {
        return Arrays.stream(values()).filter(p -> p.code.equals(code)).findFirst();
    }

    public static boolean isValid(String code) {
        return of(code).isPresent();
    }

    public static String labelOf(String code) {
        return of(code).map(Purpose::label).orElse("未知");
    }
}

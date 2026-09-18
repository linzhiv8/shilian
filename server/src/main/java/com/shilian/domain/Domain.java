package com.shilian.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * 领域分类。单选。
 *
 * <p>注意 biz 的含义是「商业 · 行业」，<b>不含资讯</b>。
 * 早期版本叫「商业 · 资讯」，导致模型看到有时效性的内容就往这里塞，
 * 把技术博客也判成了 biz。资讯性由 {@link Purpose#NEWS} 表达，与领域无关。
 */
public enum Domain {
    AI("ai", "AI · 机器学习"),
    DEV("dev", "开发工具"),
    DESIGN("design", "设计"),
    PRODUCT("product", "产品"),
    BIZ("biz", "商业 · 行业"),
    LEARN("learn", "学习教程"),
    DATA("data", "数据"),
    EFFICIENCY("efficiency", "效率"),
    LIFE("life", "生活"),
    OTHER("other", "其他");

    private final String code;
    private final String label;

    Domain(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static Optional<Domain> of(String code) {
        return Arrays.stream(values()).filter(d -> d.code.equals(code)).findFirst();
    }

    public static boolean isValid(String code) {
        return of(code).isPresent();
    }

    public static String labelOf(String code) {
        return of(code).map(Domain::label).orElse("未知");
    }
}

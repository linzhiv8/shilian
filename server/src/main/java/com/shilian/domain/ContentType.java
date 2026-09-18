package com.shilian.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * 内容形态。单选。和 {@link Domain} 正交——同一领域下各种形态都有。
 *
 * <p>注意这里的 {@code NEWS} 指的是「这一页本身是一篇新闻稿」，
 * 是形态判断；而 {@link Purpose#NEWS} 是「我打算拿它当资讯看」，是意图判断。
 * 两者可以同时成立，也可以只成立一个（比如一篇常青教程，形态是博客文章，但用户打算当资讯速览）。
 */
public enum ContentType {
    TOOL_SITE("工具站"),
    OPEN_SOURCE("开源仓库"),
    DOCS("文档"),
    TUTORIAL("教程"),
    BLOG_POST("博客文章"),
    PORTFOLIO("作品集"),
    FORUM("论坛社区"),
    VIDEO("视频"),
    AGGREGATOR("资源聚合"),
    NEWS("新闻资讯"),
    OTHER("其他");

    private final String label;

    ContentType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Optional<ContentType> of(String label) {
        return Arrays.stream(values()).filter(c -> c.label.equals(label)).findFirst();
    }

    public static boolean isValid(String label) {
        return of(label).isPresent();
    }
}

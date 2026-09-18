package com.shilian.util;

/**
 * 标签名的规范化。
 *
 * <p><b>为什么要有它。</b>
 * 标签来自两个地方：AI 给的和用户自己敲的。两边都可能带空格、
 * 也可能敲出一个全是空格的「标签」。而标签管理（改名 / 合并 / 删除）
 * 是<b>精确匹配</b>的：{"a "} 和 {"a"} 不匹配的话，
 * 用户点「删掉 a」会发现那条记录上的标签还在——表现是「删了没删掉」。
 *
 * <p>所以进入比较之前一律过一遍 {@link #normalize}，
 * 写回库里时也是规范化之后的值，库里的标签因此永远不带首尾空格。
 */
public final class TagNames {

    /** 单个标签的最大长度。超过就截断，避免一条记录上挂一个整段正文当标签。 */
    public static final int MAX_LENGTH = 64;

    private TagNames() {
    }

    /**
     * @return 去首尾空格后的名字；null / 空串一律返回 {@code null}，
     *         这样调用方用 {@code == null} 就能同时挡掉两种情况
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= MAX_LENGTH ? trimmed : trimmed.substring(0, MAX_LENGTH);
    }

    /** 这个名字能不能当标签用。 */
    public static boolean isValid(String raw) {
        return normalize(raw) != null;
    }
}

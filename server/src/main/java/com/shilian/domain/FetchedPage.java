package com.shilian.domain;

/**
 * 一次抓取 + 抽取的结果。
 *
 * <p>{@code ok=false} 时 {@code title}/{@code description}/{@code text} 均为空，
 * 但<b>管线不会因此中断</b>：提示词里专门写了「什么都没有，只剩域名」这一档，
 * 会产出低置信度、{@code needsReview=true} 的占位卡片。
 * 理由：用户存的是「这个网址有意思」，抓不到正文不代表这个网址没价值。
 * 存一个待补的占位卡，比什么都不存好——至少下次打开库还能看见它。
 */
public record FetchedPage(
        boolean ok,
        String error,
        String url,
        String host,
        String title,
        String description,
        String siteName,
        String text,
        int fullChars,
        BodySource source
) {

    /**
     * 正文是从哪来的。
     *
     * <p>这个区分必须留着，因为两种情况下「缺什么」是相反的：
     * 自动抓取失败时缺的是<b>正文</b>（有标题和描述）；
     * 用户贴正文时缺的是<b>元信息</b>（有正文，没有标题和描述）。
     * 提示词要说的话不一样，前端要显示的提示也不一样。
     */
    public enum BodySource {
        /** 服务端自己抓的 */
        FETCHED,
        /** 用户贴进来的——抓取失败时的补救入口 */
        PASTED
    }

    public static FetchedPage failed(String error, String url, String host) {
        return new FetchedPage(false, error, url, host, null, null, null, "", 0, BodySource.FETCHED);
    }

    /**
     * 用用户粘贴的正文造一个页面对象。
     *
     * <p>标题和描述留空是有意的：我们确实不知道，硬编一个反而是假信息。
     * 交给模型从正文里推断——它拿到的正文是完整的，推一个标题出来比我们瞎猜准。
     *
     * <p>{@code ok=true}：这里「ok」表示<b>我们有可用的正文</b>，不是「抓取成功了」。
     * 抓取这件事根本没发生。
     */
    public static FetchedPage pasted(String url, String host, String text, int maxChars) {
        String full = text == null ? "" : text.trim();
        String cut = full.length() > maxChars ? full.substring(0, maxChars) : full;
        return new FetchedPage(true, null, url, host, null, null, null,
                cut, full.length(), BodySource.PASTED);
    }

    /** 正文是否缺失到需要模型降档处理。 */
    public boolean bodyMissing() {
        return text == null || text.isBlank();
    }

    public boolean pasted() {
        return source == BodySource.PASTED;
    }
}

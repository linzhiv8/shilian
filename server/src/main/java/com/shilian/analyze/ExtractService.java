package com.shilian.analyze;

import com.shilian.domain.FetchedPage;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

/**
 * 从 HTML 里抽标题、描述和正文。
 *
 * <p>和提示词实验室里的正则版本相比，这里换成 Jsoup 解析：
 * 正则版遇到嵌套标签、属性里带 {@code >} 的畸形 HTML 会抽歪，
 * 而且正文提取的准确率直接决定分类质量，不值得在这省依赖。
 *
 * <p>正文提取策略（与实验室保持一致，按优先级）：
 * <ol>
 *   <li>{@code <article>} → {@code <main>} → 整个 body，取第一个够长的</li>
 *   <li>剔掉导航、页脚、侧栏、表单这类结构性噪音</li>
 *   <li>块级元素前插入换行，保留段落结构——模型读分段文本比读一大坨连续文字准</li>
 * </ol>
 */
@Service
public class ExtractService {

    /** 低于这个字数认为 article/main 抓到的不是正文，回退到整个 body。 */
    private static final int MIN_SCOPE_CHARS = 200;

    /** 结构性噪音，抽正文前先删掉。 */
    private static final String NOISE_SELECTOR =
            "script, style, noscript, svg, iframe, template, canvas, "
                    + "nav, header, footer, aside, form, button, select, dialog, menu";

    /** 在这些元素前断行。 */
    private static final String BLOCK_SELECTOR =
            "h1, h2, h3, h4, h5, h6, p, div, section, li, tr, br, article, blockquote, pre";

    public FetchedPage extract(Document doc, String url, String host, int maxChars) {
        String title = firstNonBlank(
                meta(doc, "meta[property=og:title]"),
                doc.title());
        String description = firstNonBlank(
                meta(doc, "meta[property=og:description]"),
                meta(doc, "meta[name=description]"),
                meta(doc, "meta[name=twitter:description]"));
        String siteName = firstNonBlank(
                meta(doc, "meta[property=og:site_name]"),
                meta(doc, "meta[name=application-name]"));

        String text = bodyText(doc);
        int fullChars = text.length();
        if (fullChars > maxChars) {
            text = text.substring(0, maxChars);
        }

        return new FetchedPage(
                true, null, url, host,
                clean(title), clean(description), clean(siteName),
                text, fullChars, FetchedPage.BodySource.FETCHED);
    }

    /** 正文抽取。会修改传入的 Document（复制一份再动）。 */
    String bodyText(Document original) {
        Document doc = original.clone();
        doc.select(NOISE_SELECTOR).remove();

        Element scope = doc.selectFirst("article");
        if (scope == null || scope.text().length() < MIN_SCOPE_CHARS) {
            Element main = doc.selectFirst("main");
            scope = (main != null && main.text().length() >= MIN_SCOPE_CHARS) ? main : doc.body();
        }
        if (scope == null) {
            return "";
        }

        // Jsoup 的 text() 会把所有空白折叠成空格，段落结构就没了。
        // 这里先往块级元素里塞一个字面量 "\n"（反斜杠 + n，不是真换行，
        // 所以不会被 text() 折叠掉），取完文本再替换成真换行。
        scope.select("br").append("\\n");
        scope.select(BLOCK_SELECTOR).prepend("\\n");

        String raw = scope.text().replace("\\n", "\n");

        StringBuilder sb = new StringBuilder(raw.length());
        for (String line : raw.split("\n")) {
            // 全角空格和零宽字符也要清掉，有些中文站点会拿它们排版
            String t = line.replaceAll("[ \t\u00a0\u200b]+", " ").trim();
            if (t.length() > 1) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(t);
            }
        }
        return sb.toString();
    }

    private static String meta(Document doc, String selector) {
        Element el = doc.selectFirst(selector);
        return el == null ? null : el.attr("content");
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String clean(String s) {
        return s == null ? null : s.replaceAll("\\s+", " ").trim();
    }
}

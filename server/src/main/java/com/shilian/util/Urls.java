package com.shilian.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * URL 处理。抽出来是因为抓取层和持久层都要用，
 * 放在 {@code FetchService} 里会让仓储层反向依赖分析包。
 */
public final class Urls {

    private Urls() {
    }

    /** 常见的营销追踪参数，去重时忽略掉。 */
    private static final Set<String> TRACKING = Set.of(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
            "fbclid", "gclid", "spm", "ref", "ref_src", "share_token");

    /**
     * 补协议、去 fragment。用户从地址栏粘过来的东西经常没有协议头。
     *
     * @return 规范化后的 URL；无法使用时返回 null
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (!s.contains("://")) {
            s = "https://" + s;
        }
        try {
            URI uri = new URI(s);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return null;
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return null;
            }
            // fragment 对服务端没有意义，去掉能提升去重命中率
            return new URI(scheme, uri.getUserInfo(), uri.getHost(), uri.getPort(),
                    uri.getPath(), uri.getQuery(), null).toString();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * 去重键。只做保守归一化：统一协议、去 www、去末尾斜杠、去追踪参数。
     *
     * <p>刻意<b>不</b>去掉整个 query——很多站点的内容 ID 就在 query 里
     * （比如 {@code ?id=123}），一刀切会把不同页面判成同一个。
     */
    public static String dedupKey(String url) {
        String s = normalize(url);
        if (s == null) {
            return null;
        }
        try {
            URI uri = new URI(s);
            String host = uri.getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (path.endsWith("/") && path.length() > 1) {
                path = path.substring(0, path.length() - 1);
            }
            int port = (uri.getPort() == 80 || uri.getPort() == 443) ? -1 : uri.getPort();
            return new URI("https", null, host, port, path, stripTracking(uri.getQuery()), null).toString();
        } catch (URISyntaxException e) {
            return s;
        }
    }

    public static String hostOf(String url) {
        if (url == null) {
            return null;
        }
        try {
            String host = new URI(url).getHost();
            return host == null ? url : host.replaceFirst("^www\\.", "");
        } catch (URISyntaxException e) {
            return url;
        }
    }

    /**
     * 这些站点是「平台」，真正的内容标识在路径里而不是域名里。
     * 对它们取域名前两个字母毫无意义——所有 GitHub 卡片都会显示同一个 GI，
     * 而字母块的作用正是让人一眼认出这张卡是哪一条。
     */
    private static final Set<String> PLATFORM_HOSTS = Set.of(
            "github.com", "gitee.com", "gitlab.com", "bitbucket.org", "sourceforge.net",
            "medium.com", "substack.com", "youtube.com", "youtu.be", "bilibili.com",
            "zhihu.com", "juejin.cn", "csdn.net", "cnblogs.com", "segmentfault.com",
            "notion.site", "notion.so", "yuque.com", "feishu.cn", "docs.qq.com");

    /** 路径里这些段是站点结构，不是内容标识，取字母块时要跳过。 */
    private static final Set<String> PATH_NOISE = Set.of(
            "blob", "tree", "issues", "pull", "pulls", "wiki", "src", "releases",
            "commits", "discussions", "actions", "watch", "question", "answer",
            "article", "articles", "post", "posts", "p", "v", "index.html", "index");

    /**
     * 卡片左上角那个方形字母块。
     *
     * <p>普通站点取域名第一段的前两个字符（{@code trafilatura.io} → {@code TR}）。
     * 平台类站点改用路径里最后一个有意义的段
     * （{@code github.com/anthropics/skills} → {@code SK}），
     * 否则所有 GitHub 仓库的卡片会长得一模一样。
     */
    public static String monogram(String url) {
        if (url == null || url.isBlank()) {
            return "??";
        }
        String host;
        String path;
        try {
            URI uri = new URI(url);
            host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
            path = uri.getPath() == null ? "" : uri.getPath();
        } catch (URISyntaxException e) {
            host = url;
            path = "";
        }

        if (PLATFORM_HOSTS.contains(host)) {
            String fromPath = lastMeaningfulSegment(path);
            if (fromPath != null) {
                return initials(fromPath);
            }
        }
        return initials(firstLabel(host));
    }

    private static String lastMeaningfulSegment(String path) {
        String[] parts = path.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            String seg = parts[i].replaceFirst("^@", "").trim();
            if (seg.isEmpty() || PATH_NOISE.contains(seg.toLowerCase(Locale.ROOT))) {
                continue;
            }
            // 纯数字的段（比如 zhihu.com/question/123456）没有辨识度，不如回退到域名
            if (seg.chars().allMatch(Character::isDigit)) {
                continue;
            }
            return seg;
        }
        return null;
    }

    private static String firstLabel(String host) {
        if (host == null || host.isBlank()) {
            return "";
        }
        return host.split("\\.")[0];
    }

    private static String initials(String s) {
        if (s == null || s.isEmpty()) {
            return "??";
        }
        String m = s.length() <= 2 ? s : s.substring(0, 2);
        return m.toUpperCase(Locale.ROOT);
    }

    private static String stripTracking(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String p : query.split("&")) {
            if (TRACKING.contains(p.split("=", 2)[0].toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(p);
        }
        return sb.isEmpty() ? null : sb.toString();
    }
}

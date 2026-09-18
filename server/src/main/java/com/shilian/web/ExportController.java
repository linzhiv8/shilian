package com.shilian.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shilian.domain.Domain;
import com.shilian.domain.LinkItem;
import com.shilian.domain.port.Clock;
import com.shilian.repo.LinkRepository;
import com.shilian.util.RelativeTime;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据出口（R-07）：把全部收藏导出成文件。
 *
 * <p><b>为什么必须有它。</b>
 * 对个人产品，「没有出口」不是效率问题，是<b>资产安全</b>问题：
 * 这些是他一条条攒下来的，而它们只存在于你的数据库里。
 * 现在还有别人在往里存东西，给出口就更是一项义务而不是一个功能。
 *
 * <p><b>两种格式，各有各的用途，不是同一个东西的两种皮肤：</b>
 * <ul>
 *   <li><b>JSON</b> —— 无损。字段和接口里的 {@link LinkItem} 一一对应，
 *       将来要做「导入」就是以它为准，所以它不能为了好看而省略字段。</li>
 *   <li><b>HTML</b> —— 能重新导入 Chrome / Edge / Safari 的书签管理器。
 *       它故意是<b>有损</b>的：书签格式只认网址、标题、目录，
 *       摘要和备注塞在 {@code <DD>} 里，导入后能不能看到取决于浏览器。
 *       它的用途是「把这些网址带到别的地方去继续用」，不是备份。</li>
 * </ul>
 *
 * <p><b>为什么不用流式（Streaming）写出。</b>
 * 几百条 × 每条几十 KB = 几 MB，一次拼完在内存里也就几 MB；
 * 而流式写出要处理「写了一半连接断了」这种状态，收益不成比例。
 * 真到了几千条、上百 MB 的时候再改，那时候要改的也不止这一处。
 */
@RestController
@RequestMapping("/api/export")
public class ExportController {

    /** 文件名里的时间格式。不含空格和冒号——那两种字符在 Content-Disposition 里要转义。 */
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private final LinkRepository repo;
    private final Clock clock;
    private final ObjectMapper mapper;

    public ExportController(LinkRepository repo, Clock clock, ObjectMapper mapper) {
        this.repo = repo;
        this.clock = clock;
        this.mapper = mapper;
    }

    /**
     * @param format {@code json}（默认）或 {@code html}
     */
    @GetMapping
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false, defaultValue = "json") String format) {
        List<LinkItem> items = repo.forExport();
        String stamp = FILE_STAMP.format(clock.now());

        if ("html".equalsIgnoreCase(format)) {
            byte[] body = toBookmarksHtml(items).getBytes(StandardCharsets.UTF_8);
            return file(body, "shilian-links-" + stamp + ".html",
                    MediaType.valueOf("text/html;charset=UTF-8"));
        }
        if (!"json".equalsIgnoreCase(format)) {
            // 明确拒绝而不是默默按 json 处理：用户以为自己要的是 HTML，
            // 拿到一个 JSON 时通常发现不了，等到导入时才炸。
            throw new IllegalArgumentException("导出的格式只支持 json 和 html");
        }
        return file(toJson(items), "shilian-links-" + stamp + ".json",
                MediaType.valueOf("application/json;charset=UTF-8"));
    }

    /* ────────────── JSON ────────────── */

    /**
     * 带一层信封而不是直接给数组。
     *
     * <p>纯数组看起来更简洁，但一个只有数组的下载文件没有任何自我描述：
     * 不知道什么时候导的、也不知道当时有多少条。
     * 而 {@code items} 这一层仍然是标准的 {@link LinkItem} 数组，
     * 将来做导入时取它即可。
     */
    private byte[] toJson(List<LinkItem> items) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("exportedAt", RelativeTime.format(clock.now()));
        payload.put("count", items.size());
        payload.put("items", items);
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload);
        } catch (IOException e) {
            // 序列化一个只有 String / 数字 / 数组的Map不会真的失败，
            // 但 writeValueAsBytes 声明了受检异常，必须处理掉。
            throw new IllegalStateException("导出 JSON 失败：" + e.getMessage(), e);
        }
    }

    /* ────────────── 浏览器书签 HTML ────────────── */

    /**
     * Netscape 书签文件格式。
     *
     * <p>这个格式从 1990 年代的 Netscape 沿用至今，Chrome / Edge / Firefox / Safari
     * 的书签导入都认它——不是因为它好，而是因为没有替代品：
     * 浏览器只提供「从 HTML 文件导入书签」这一条路。
     *
     * <p>几个容易写错、而写错了浏览器会<b>静默少导入几条</b>的点：
     * <ul>
     *   <li>{@code <DL><p>} 和 {@code </DL><p>} 是这套语法的固定骨架，
     *       只写 {@code <DL>} 会让某些版本只导入第一个目录。</li>
     *   <li>标题是 {@code <A>} 的<b>内容</b>而不是属性。写进属性里，
     *       导入出来的是一串空白书签。</li>
     *   <li>{@code ADD_DATE} 是<b>秒</b>级 Unix 时间戳。写成毫秒，
     *       导入出来的收藏日期会是 1970 年。</li>
     * </ul>
     *
     * <p>按领域分目录：几百条平铺在一个列表里，导入之后等于没整理过。
     * 领域已经是现成的一级分类，用它的中文名当目录名即可。
     */
    private String toBookmarksHtml(List<LinkItem> items) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
                .append("<!-- 由拾链导出 · ").append(RelativeTime.format(clock.now())).append(" -->\n")
                .append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
                .append("<TITLE>Bookmarks</TITLE>\n")
                .append("<H1>Bookmarks</H1>\n")
                .append("<DL><p>\n");

        for (Map.Entry<String, List<LinkItem>> group : groupByDomain(items).entrySet()) {
            sb.append("    <DT><H3>").append(escape(group.getKey())).append("</H3>\n");
            sb.append("    <DL><p>\n");
            for (LinkItem item : group.getValue()) {
                sb.append("        <DT><A HREF=\"").append(escape(item.url()))
                        .append("\" ADD_DATE=\"").append(epochSeconds(item.createdAt()))
                        .append("\">").append(escape(titleOf(item))).append("</A>\n");
                String desc = descriptionOf(item);
                if (!desc.isEmpty()) {
                    sb.append("        <DD>").append(escape(desc)).append("\n");
                }
            }
            sb.append("    </DL><p>\n");
        }
        sb.append("</DL><p>\n");
        return sb.toString();
    }

    private static Map<String, List<LinkItem>> groupByDomain(List<LinkItem> items) {
        Map<String, List<LinkItem>> groups = new LinkedHashMap<>();
        for (LinkItem item : items) {
            String folder = Domain.labelOf(item.domainKey());
            groups.computeIfAbsent(folder, k -> new ArrayList<>()).add(item);
        }
        return groups;
    }

    /** 没有标题就退回网址：一个空白的书签比一个写着网址的书签更没用。 */
    private static String titleOf(LinkItem item) {
        return item.title() == null || item.title().isBlank() ? item.url() : item.title();
    }

    /** 摘要 + 备注。书签格式只有这一处能放自由文本。 */
    private static String descriptionOf(LinkItem item) {
        StringBuilder sb = new StringBuilder();
        if (item.summary() != null && !item.summary().isBlank()) {
            sb.append(item.summary());
        }
        if (item.note() != null && !item.note().isBlank()) {
            if (sb.length() > 0) {
                sb.append(" ｜ ");
            }
            sb.append(item.note());
        }
        return sb.toString();
    }

    /**
     * 秒级 Unix 时间戳。
     *
     * <p>库里存的是本地时间字符串（见 {@code RelativeTime.STORE}），
     * 所以按系统默认时区换算回去——和「存进去的时候也是本地时间」对称。
     * 解析不出来就给 0：那比抛异常好，一个日期不对的书签仍然是书签。
     */
    private static long epochSeconds(String stored) {
        java.time.LocalDateTime t = RelativeTime.parse(stored);
        return t == null ? 0L : t.atZone(ZoneId.systemDefault()).toEpochSecond();
    }

    /**
     * HTML 转义。
     *
     * <p>不能省：标题和摘要是 AI 从网页上抄来的，里面出现 {@code &}、{@code <}
     * 是常事。不转义的话，轻则文字显示错，重则整段正文被当成标签解析，
     * 导入出来的书签缺一大半——而浏览器不会报错，只会少导入。
     */
    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static ResponseEntity<byte[]> file(byte[] body, String filename, MediaType type) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(type)
                .body(body);
    }
}

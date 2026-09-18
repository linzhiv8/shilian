package com.shilian.analyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.shilian.domain.ContentType;
import com.shilian.domain.Domain;
import com.shilian.domain.Purpose;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 校验模型输出。返回空列表表示通过。
 *
 * <p>这些规则和系统提示词里的硬约束一一对应。之所以要有一份代码版，
 * 是因为提示词是「建议」，模型会漏；校验才是「保证」。
 * 两边长度区间等参数必须手动保持同步——{@code 15–35} 这个数字改一处就要改两处。
 *
 * <p><b>踩过的坑：每条规则都要是独立的 if，绝不能串成 else。</b>
 * 早期版本写成「先查数量，数量对了再逐项查」，结果模型返回 2 条 note_options 时，
 * 逐项规则（禁用开头词、字数）整段被跳过，两条明显违规的备注被判成通过。
 * 数量错和内容错是两回事，必须都能报出来——修复时漏掉的那两条
 * 正是最该被拦住的。
 */
@Service
public class ValidationService {

    /** 备注里不能出现的开头词：一出现就变成客观介绍，不是「替用户说的话」。 */
    private static final Pattern BAD_OPENING =
            Pattern.compile("^(这个网站|它是一个|该平台|此工具|本站|用户)");

    /** 空话词：说了等于没说。 */
    private static final Pattern FILLER =
            Pattern.compile("值得收藏|很有用|不容错过|非常强大|神器");

    /** 比较相似度时忽略的标点空白。 */
    private static final Pattern NOISE_CHARS = Pattern.compile("[，。、\\s]");

    public static final int NOTE_MIN = 15;
    public static final int NOTE_MAX = 35;

    public List<String> validate(JsonNode o) {
        List<String> errors = new ArrayList<>();
        if (o == null || !o.isObject()) {
            return List.of("返回的不是对象");
        }

        // ── domain：越界检查与取值无关，永远执行 ──
        if (!Domain.isValid(o.path("domain").asText(null))) {
            errors.add("domain 越界 → " + repr(o.get("domain")));
        }

        // ── purposes：数量与逐项枚举分开检查，避免「数量不对就跳过逐项校验」的漏洞 ──
        JsonNode purposes = o.get("purposes");
        if (purposes == null || !purposes.isArray() || purposes.isEmpty()) {
            errors.add("purposes 为空");
        } else {
            if (purposes.size() > 3) {
                errors.add("purposes 超过 3 个（" + purposes.size() + "）");
            }
            for (JsonNode p : purposes) {
                if (!Purpose.isValid(p.asText(""))) {
                    errors.add("purpose 越界 → " + repr(p));
                }
            }
            for (String userOnly : Purpose.USER_ONLY) {
                if (containsText(purposes, userOnly)) {
                    errors.add("purposes 含 " + userOnly + "（不该由 AI 判断）");
                }
            }
        }

        // ── note_options：同理，逐条规则独立于数量检查 ──
        JsonNode notes = o.get("note_options");
        if (notes == null || !notes.isArray()) {
            errors.add("note_options 不是数组");
        } else {
            if (notes.size() != 3) {
                errors.add("note_options 必须 3 条，实际 " + notes.size());
            }
            for (int i = 0; i < notes.size(); i++) {
                String s = notes.get(i).asText("");
                int len = codePointLength(s);
                if (len < NOTE_MIN || len > NOTE_MAX) {
                    errors.add("note_options[" + i + "] " + len + " 字（应在 " + NOTE_MIN + "–" + NOTE_MAX + "）");
                }
                if (BAD_OPENING.matcher(s).find()) {
                    errors.add("note_options[" + i + "] 以禁用词开头");
                }
                if (FILLER.matcher(s).find()) {
                    errors.add("note_options[" + i + "] 含空话词");
                }
            }
            if (notes.size() == 3) {
                Set<String> uniq = new HashSet<>();
                for (JsonNode n : notes) {
                    String key = NOISE_CHARS.matcher(n.asText("")).replaceAll("");
                    uniq.add(key.length() <= 8 ? key : key.substring(0, 8));
                }
                if (uniq.size() < 3) {
                    errors.add("note_options 三句过于相似");
                }
            }
        }

        JsonNode tags = o.get("tags");
        int tagCount = (tags != null && tags.isArray()) ? tags.size() : 0;
        if (tagCount < 3 || tagCount > 6) {
            errors.add("tags 数量 " + tagCount + "（应 3–6）");
        }

        if (!ContentType.isValid(o.path("content_type").asText(null))) {
            errors.add("content_type 越界 → " + repr(o.get("content_type")));
        }

        JsonNode conf = o.get("confidence");
        if (conf == null || !conf.isNumber()) {
            errors.add("confidence 非法");
        } else {
            double c = conf.asDouble();
            if (c < 0 || c > 1) {
                errors.add("confidence 非法");
            } else if (c <= 0.4 && !o.path("needs_review").asBoolean(false)) {
                errors.add("低置信度但 needs_review 不为 true");
            }
        }

        return errors;
    }

    /**
     * 长度按码点算，和 JS 的 {@code [...s].length} 一致。
     * 用 {@code String.length()} 的话，含 emoji 的备注会被算长，误判为超长。
     */
    private static int codePointLength(String s) {
        return s.codePointCount(0, s.length());
    }

    private static boolean containsText(JsonNode array, String value) {
        for (JsonNode n : array) {
            if (value.equals(n.asText(""))) {
                return true;
            }
        }
        return false;
    }

    /** 把节点渲染成错误信息里可读的形式。 */
    private static String repr(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return "null（字段缺失）";
        }
        return n.toString();
    }
}

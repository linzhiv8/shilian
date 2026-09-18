package com.shilian.analyze;

import com.shilian.domain.FetchedPage;
import com.shilian.util.Urls;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 拼提示词。
 *
 * <p><b>提示词只有一份</b>，在 {@code server/tools/prompt-lab/system-prompt.md}，
 * 构建时被打进 classpath 的 {@code prompts/}（加载逻辑在 {@link Prompts}）。
 * 实验室里调好什么，线上就跑什么，不会出现「改了一处忘了另一处」。
 *
 * <p><b>上下文缓存</b>：系统提示词约 2,500 token，占了单次输入的一半。
 * DeepSeek 的上下文缓存按「前缀完全一致」命中，所以这个字符串在启动时读一次就固定住，
 * 中间不做任何拼接、替换、trim 之外的处理。任何动态内容
 * （比如「用户以往修正」）都必须放到用户消息里——
 * 一旦混进系统提示词，前缀就变了，缓存全废，成本直接翻倍。
 */
@Service
public class PromptService {

    private final String systemPrompt;

    public PromptService() {
        this.systemPrompt = Prompts.load("system-prompt.md");
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    /**
     * 拼用户消息。
     *
     * <p>结构固定，不要随意加字段：格式一变，模型对「正文在哪、有多长」的判断就会漂。
     * 唯一允许变化的是「用户以往修正」区块的内容。
     */
    public String buildUserMessage(FetchedPage page, String url, String correctionsBlock) {
        String host = page.host() != null ? page.host() : Urls.hostOf(url);

        String body;
        if (page.bodyMissing()) {
            // 把失败原因写进去，模型才知道正文为空是「抓取失败」而不是「这页本来就短」
            String reason = page.ok() ? "页面正文为空" : "抓取失败：" + page.error();
            body = "（正文抓取失败：" + reason + "，没有可用的页面内容）";
        } else {
            body = page.text();
        }

        List<String> lines = new ArrayList<>(List.of(
                "## 待分析网页",
                "",
                "- URL: " + url,
                "- 域名: " + host,
                "- 页面标题: " + orDash(page.title()),
                "- Meta 描述: " + orDash(page.description())));

        /*
         * 贴正文的情况要单独说明一句。
         *
         * 不说明的话，模型看到「标题（无）」配上一大段正文会困惑——
         * 它可能把「标题未知」当成结论写进 summary，或者干脆不肯下判断。
         * 说清楚「正文是用户贴的、元信息没取到」，它就知道该从正文里推断标题。
         */
        if (page.pasted()) {
            lines.add("- 正文来源: 用户手动粘贴（服务端抓取失败，标题和描述都没取到，"
                    + "请从正文内容推断标题）");
        }

        lines.add("");
        lines.add("## 正文");
        lines.add("");
        lines.add(body);
        lines.add("");
        lines.add("## 用户以往修正");
        lines.add("");
        lines.add(correctionsBlock == null || correctionsBlock.isBlank() ? "（无）" : correctionsBlock);
        lines.add("");
        lines.add("请输出 JSON。");
        return String.join("\n", lines);
    }

    /**
     * 校验失败时回灌的修正指令。
     *
     * <p>必须要求输出<b>完整</b> JSON，不能只输出改动字段——
     * 否则模型经常只回一个 diff，解析出来就是残缺对象。
     */
    public String repairMessage(List<String> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("你上一次的输出有以下问题，请修正后重新输出**完整**的 JSON 对象（不要只输出被改的字段）：\n\n");
        for (String e : errors) {
            sb.append("- ").append(e).append('\n');
        }
        sb.append('\n');
        sb.append("提醒：note_options 必须恰好 3 句，每句 15–35 字。\n");
        sb.append("超长时是**概括**问题不是标点问题——不要把「照着 template 和 SKILL.md 抄结构」这类列举硬塞进一句，\n");
        sb.append("改成「照着它的结构抄一个」这种概括说法。");
        return sb.toString();
    }

    /** 模型返回的不是合法 JSON 时的专用追问。 */
    public String notJsonMessage() {
        return "你返回的内容不是合法 JSON。请只输出一个 JSON 对象，不要任何其他文字、不要代码块围栏。";
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "（无）" : s;
    }
}

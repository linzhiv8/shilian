package com.shilian.analyze;

import com.shilian.config.ShilianProperties;
import com.shilian.domain.AnalyzeDraft;
import com.shilian.repo.LinkRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI 花销入账（R-15）。
 *
 * <p><b>入账点为什么在「调用」而不是在「保存」。</b>
 * 以前只有 {@code POST /api/links}（保存）和 {@code /{id}/apply}（应用补正文）
 * 会往 {@code ai_log} 写一条，于是最容易失控的浪费路径——
 * 反复点分析、最后没保存——在账上<b>完全隐形</b>。
 * 而钱是在「调用模型」那一刻花掉的，跟用户后来保没保存无关。
 * 所以入账点前移到 {@code /api/analyze} 和 {@code /{id}/reanalyze}。
 *
 * <p><b>一处调用记一条，含失败的。</b>
 * 失败的那次同样烧了 token，而且是更值得看的一截
 * （连试几次都没过，通常是提示词或模型的问题）。
 * 所以 {@link AnalyzeService.AnalysisFailedException} 会带上用量出来，这里照记。
 *
 * <p><b>没有 AI 参与的失败不记。</b>
 * 网址不合法、并发闸门拒绝、熔断打开这三类根本没调模型，
 * 记进去只会把「调用次数」这个数字稀释掉。
 *
 * <p><b>保存 / 应用那两处不再重复记。</b>
 * 一次分析只对应一条 {@code ai_log}：两处都记会让每个人的用量翻倍，
 * 而错的数字比没有数字更糟。代价是新链接的那条日志没有 {@code link_id}
 * （那时记录还没建出来）——{@code ai_log.link_id} 本来就允许为空，
 * 而管理端要看的「谁花了多少」是按 {@code user_id} 聚合的，用不到它。
 *
 * <p><b>写失败会往外抛，不静默吞掉。</b>
 * 这里和 {@code AuditService} 的取舍相反：审计少一条是「查不到那段历史」，
 * 而用量少一条是「账上的数字是错的」——后者会让人照着错的数字做判断。
 * 数据库写不进去的时候，整个请求本来也该失败。
 */
@Component
public class AiUsageRecorder {

    private final LinkRepository links;
    private final ShilianProperties props;

    public AiUsageRecorder(LinkRepository links, ShilianProperties props) {
        this.links = links;
        this.props = props;
    }

    /**
     * 记一次成功的（或降级成功的）分析。
     *
     * @param linkId 已存在记录的重分析带上 id；新链接的分析传 null
     */
    public void record(String linkId, AnalyzeDraft d) {
        links.insertAiLog(linkId,
                props.deepseek().model(),
                d.attempts(),
                d.promptTokens(),
                d.completionTokens(),
                d.validationErrors().isEmpty(),
                joinErrors(d.repairLog()));
    }

    /** 记一次彻底失败的分析。用量从异常里带出来，见 {@link AnalyzeService.AnalysisFailedException}。 */
    public void record(String linkId, AnalyzeService.AnalysisFailedException e) {
        links.insertAiLog(linkId,
                props.deepseek().model(),
                e.attempts(),
                e.promptTokens(),
                e.completionTokens(),
                false,
                e.getMessage());
    }

    /**
     * errors 记 repairLog 而不是 validationErrors：后者在「第一次错、重试后对了」
     * 这种最常见的情况下是空的，而那恰恰是最值得留痕的一截。
     */
    private static String joinErrors(List<String> repairLog) {
        return repairLog == null || repairLog.isEmpty() ? null : String.join(" | ", repairLog);
    }
}

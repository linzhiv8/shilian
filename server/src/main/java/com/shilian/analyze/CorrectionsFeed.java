package com.shilian.analyze;

import com.shilian.repo.LinkRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 把「用户以往纠正过什么」拼成提示词里的一块。
 *
 * <p><b>为什么要有这个类。</b>原来这段逻辑在 {@code LinkRepository.correctionsBlock()}
 * 里——仓储层在拼提示词。这让数据库代码知道了「AI 的提示词长什么样」，
 * 于是改一句提示词措辞要动仓储，改一个字段名也要担心影响提示词。
 * 拆开之后各管一段：仓储只负责把统计数据查出来，这里负责怎么说话。
 *
 * <p><b>为什么这段内容必须进「用户消息」而不是系统提示词。</b>
 * 系统提示词要逐字节固定才能命中 DeepSeek 的上下文缓存，
 * 而这块内容每次分析都可能不一样，混进去等于把缓存全废掉。
 *
 * <p><b>多用户改造时这里要跟着改</b>：必须按 {@code user_id} 过滤，
 * 否则 A 的偏好会喂进 B 的分析——不报错，只让结果悄悄变差。
 */
@Service
public class CorrectionsFeed {

    private final LinkRepository repo;

    public CorrectionsFeed(LinkRepository repo) {
        this.repo = repo;
    }

    public String block(int limit) {
        List<LinkRepository.CorrectionStat> rows = repo.correctionStats(limit);
        if (rows.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("以下是用户把 AI 的判断改成别的记录，请从中读出偏好倾向，同类内容往那个方向靠：\n");
        for (LinkRepository.CorrectionStat r : rows) {
            String label = "domain_category".equals(r.field()) ? "领域" : "用途";
            sb.append("- ").append(label)
                    .append("：AI 判 `").append(r.aiValue())
                    .append("`，用户改成 `").append(r.userValue())
                    .append("`（").append(r.count()).append(" 次）\n");
        }
        return sb.toString().trim();
    }
}

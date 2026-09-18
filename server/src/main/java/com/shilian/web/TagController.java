package com.shilian.web;

import com.shilian.repo.LinkRepository;
import com.shilian.util.TagNames;
import com.shilian.web.dto.TagDeleteRequest;
import com.shilian.web.dto.TagMergeRequest;
import com.shilian.web.dto.TagRenameRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 标签：列出 + 改名 / 合并 / 删除（R-05）。
 *
 * <p><b>筛选不在这里。</b>
 * 列表本来就要把当前用户的全部链接拉回前端做即时搜索，
 * 标签筛选在客户端做即可，为它再开一个接口等于多一次往返。
 * 但<b>管理动作必须走服务端</b>：改名、合并、删除都是「跨行读改写」——
 * 要读出所有带这个标签的行、改完再一行行写回。
 * 这种事放在前端做，等于把「改到一半失败」的状态留在客户端手里。
 *
 * <p><b>三个动作分开成三个接口，而不是一个带 {@code op} 字段的通用入口。</b>
 * 通用入口的请求体会变成「哪些字段有效取决于 op 是什么」，
 * 校验要分叉，而调用方得先读懂那张表才知道该传什么。
 * 三个接口各自的契约都是一句话能说清的。
 */
@RestController
@RequestMapping("/api/tags")
public class TagController {

    private final LinkRepository repo;

    public TagController(LinkRepository repo) {
        this.repo = repo;
    }

    /**
     * 当前用户的全部标签及条数。
     *
     * <p>条数是服务端算的：前端自己数的话，得先拿到全量列表、
     * 再按同样的规则去重，等于把「什么算同一个标签」这条规则抄了第二遍。
     */
    @GetMapping
    public List<TagItem> list() {
        return repo.tagCounts().stream()
                .map(c -> new TagItem(c.name(), c.count()))
                .toList();
    }

    /** 改名。返回受影响的行数，前端用它告诉用户「改了 N 条」。 */
    @PostMapping("/rename")
    public Affected rename(@Valid @RequestBody TagRenameRequest req) {
        String from = requireName(req.from(), "原来的标签名");
        String to = requireName(req.to(), "新的标签名");
        if (from.equals(to)) {
            return new Affected(0);
        }
        return new Affected(repo.renameTag(from, to));
    }

    /** 合并。目标标签会被加到每条受影响的记录上，来源标签被摘掉。 */
    @PostMapping("/merge")
    public Affected merge(@Valid @RequestBody TagMergeRequest req) {
        String target = requireName(req.target(), "要合并到哪个标签");
        List<String> sources = new ArrayList<>();
        for (String s : req.sources()) {
            String name = TagNames.normalize(s);
            if (name != null && !sources.contains(name)) {
                sources.add(name);
            }
        }
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("要合并的标签一个都不合法，检查一下");
        }
        return new Affected(repo.mergeTags(sources, target));
    }

    /** 删标签。只摘标签，链接本身不动。 */
    @PostMapping("/delete")
    public Affected delete(@Valid @RequestBody TagDeleteRequest req) {
        return new Affected(repo.deleteTag(requireName(req.name(), "要删的标签名")));
    }

    /** 一个标签及条数。 */
    public record TagItem(String name, int count) {}

    /**
     * 受影响的行数。
     *
     * <p>返回数字而不是一句「成功了」：标签管理动的是<b>多条</b>记录，
     * 用户需要知道动了多少条——「改了 0 条」和「改了 12 条」给他的反馈完全不同，
     * 前者说明他选错了名字，后者说明事情真的做成了。
     */
    public record Affected(int affected) {}

    /**
     * 校验并规范化一个标签名。
     *
     * <p>{@code @NotBlank} 只挡得住 null 和空串，挡不住 {@code "   "}——
     * 那种输入过得了校验，进库之后就是一个看不见的空白标签。
     */
    private static String requireName(String raw, String what) {
        String name = TagNames.normalize(raw);
        if (name == null) {
            throw new IllegalArgumentException(what + "不能为空");
        }
        return name;
    }
}

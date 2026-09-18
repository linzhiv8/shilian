package com.shilian.web;

import com.shilian.search.SemanticSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 搜索。
 *
 * <p>现在只有语义搜索一个端点。关键词搜索仍然在客户端做——侧栏的领域/用途计数
 * 本来就要全量数据，列表无论如何都得整个拉回来，数据在手就没必要再走一趟网络
 * （这条约定记在 README 里，几千条时再换服务端）。
 *
 * <p>语义搜索不一样，它<b>必须</b>在服务端：向量在库里，算余弦也在服务端。
 * 但注意它返回的是「id → 相似度」的排序，<b>不是过滤后的列表</b>。
 * 这样领域/用途筛选仍然由前端在本地做，两套逻辑正交、不会互相漂。
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SemanticSearchService semantic;

    public SearchController(SemanticSearchService semantic) {
        this.semantic = semantic;
    }

    /**
     * 语义搜索。
     *
     * @param q     查询文本
     * @param limit 最多回几条。0 表示不限——前端要拿全量顺序去和本地筛选求交集，
     *              截断了的话「筛选后应该有 5 条」会变成「只剩 2 条」，很难解释
     */
    @GetMapping("/semantic")
    public SemanticSearchService.Result semantic(
            @RequestParam(required = false, defaultValue = "") String q,
            @RequestParam(required = false, defaultValue = "0") int limit) {
        // 上限给得比较宽（500），因为它是「返回条数」而不是「返回内容」——
        // 一条命中只有一个 id 和一个浮点数，几百条也就几 KB。
        return semantic.search(q, limit <= 0 ? 0 : Math.min(limit, 500));
    }
}

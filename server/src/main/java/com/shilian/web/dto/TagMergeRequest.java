package com.shilian.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 标签合并：把 {@code sources} 里的几个标签并到 {@code target} 上。
 *
 * <p>{@code sources} 允许有多个，但至少得有一个——
 * 空数组的语义是「什么都不做」，那不该由一次 POST 来表达。
 *
 * <p>{@code target} 不必不在 {@code sources} 里：
 * 传进来也没关系，实现会把它当成「要保留的那个」而不是「要删掉的」。
 * 这条宽容是刻意的——前端的多选里很容易把目标也勾上，
 * 为此报一个错等于让用户先去理解实现细节。
 */
public record TagMergeRequest(
        @NotEmpty(message = "至少选一个要合并的标签") List<String> sources,
        @NotBlank(message = "要合并到哪个标签不能为空") String target
) {
}

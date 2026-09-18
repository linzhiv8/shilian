package com.shilian.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 删掉一个标签。
 *
 * <p>只摘标签，<b>不动链接</b>：标题、网址、正文都还在，
 * 只是这条记录上不再有这个标签。这点要在接口语义上写死——
 * 「删标签」如果顺手把链接也删了，用户就再也不敢点它。
 */
public record TagDeleteRequest(
        @NotBlank(message = "要删的标签名不能为空") String name
) {
}

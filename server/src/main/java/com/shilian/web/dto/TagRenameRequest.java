package com.shilian.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 标签改名。
 *
 * <p>{@code from} 和 {@code to} 都要有值：改名是「把 A 换成 B」，
 * 缺任何一个都说不清要做什么，早一点 400 好过在库里写出一个空标签。
 */
public record TagRenameRequest(
        @NotBlank(message = "原来的标签名不能为空") String from,
        @NotBlank(message = "新的标签名不能为空") String to
) {
}

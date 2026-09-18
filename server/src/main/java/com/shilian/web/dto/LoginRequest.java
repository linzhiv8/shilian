package com.shilian.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求。
 *
 * <p>{@code username} 这一栏既能填用户名也能填邮箱——
 * 用户记不住自己当初用的是哪个，让他试两次不如一个框都收下。
 */
public record LoginRequest(
        @NotBlank(message = "请填用户名或邮箱")
        String username,

        @NotBlank(message = "请填密码")
        String password
) {}

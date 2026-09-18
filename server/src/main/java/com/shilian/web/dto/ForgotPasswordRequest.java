package com.shilian.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 申请一封重置密码的邮件。
 *
 * <p>只有邮箱一个字段，没有用户名——「忘记密码」的人记得自己的邮箱，
 * 不记得的恰恰是那些和账号绑定的标识（用户名往往就是他想不起来的东西之一）。
 */
public record ForgotPasswordRequest(
        @NotBlank(message = "请填邮箱")
        @Email(message = "邮箱格式不对")
        String email
) {}

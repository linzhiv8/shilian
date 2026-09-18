package com.shilian.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 用邮件里的令牌设置新密码。
 *
 * <p>刻意<b>不收旧密码</b>：能走到这一步就已经证明他点开了那封邮件，
 * 再要一次旧密码只是把「忘了密码的人」又挡在门外一次。
 * 令牌本身（30 分钟过期、用后即废）才是这里的凭据。
 */
public record ResetPasswordRequest(
        @NotBlank(message = "链接不对")
        String token,

        @NotBlank(message = "请填新密码")
        @Size(min = 8, max = 72, message = "密码长度要在 8 到 72 位之间")
        String newPassword
) {}

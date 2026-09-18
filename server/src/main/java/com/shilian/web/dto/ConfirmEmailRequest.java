package com.shilian.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 点开邮件里的验证链接后，把令牌送回来确认。 */
public record ConfirmEmailRequest(
        @NotBlank(message = "链接不对")
        String token
) {}

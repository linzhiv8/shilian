package com.shilian.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改密码请求。
 *
 * <p><b>必须验旧密码。</b>
 * 「登录着就能直接改」听起来省事，但它意味着任何人坐到你电脑前、
 * 或者拿到一个还没过期的会话 cookie，就能把账号彻底抢走——
 * 改完你再也登不进去。旧密码是最后一道「这个操作确实是本人」的证明。
 *
 * <p>新密码的长度限制和注册时一致，理由见 {@link RegisterRequest}。
 */
public record ChangePasswordRequest(
        @NotBlank(message = "请填当前密码")
        String oldPassword,

        @NotBlank(message = "请填新密码")
        @Size(min = 8, max = 72, message = "新密码长度要在 8 到 72 位之间")
        String newPassword
) {}

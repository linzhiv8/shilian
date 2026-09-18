package com.shilian.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。
 *
 * <p><b>密码上限 72 位不是随便定的。</b>
 * bcrypt 只取输入的前 72 字节，超出的部分<b>会被静默丢弃</b>——
 * 也就是「密码第 73 位之后随便改都能登录」。
 * Spring Security 6 起会直接拒绝超长密码，
 * 但与其让它抛一个看不懂的错，不如在校验层就把话说清楚。
 *
 * <p><b>用户名只允许字母数字下划线。</b>
 * 登录接口支持「用户名或邮箱」两种方式，
 * 如果用户名里允许 @ 和点，就分不清传进来的是哪个——
 * 那会变成一个可以被利用的歧义。
 */
public record RegisterRequest(
        @NotBlank(message = "用户名不能为空")
        @Pattern(regexp = "^[a-zA-Z0-9_]{3,32}$",
                message = "用户名只能是字母、数字或下划线，长度 3 到 32 位")
        String username,

        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不对")
        String email,

        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 72, message = "密码长度要在 8 到 72 位之间")
        String password,

        @Size(max = 32, message = "昵称最多 32 个字")
        String nickname
) {}

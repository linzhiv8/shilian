package com.shilian.web.dto;

/**
 * 一个只表示「收到了」的响应。
 *
 * <p><b>为什么用它而不是什么都不返回。</b>
 * 「忘记密码」这一类接口如果返回 204 空响应，前端拿到的 body 是空的，
 * 于是每个调用点都要自己判「空 body 算成功还是算失败」。
 * 给一个恒为真的 {@code ok} 字段，让成功这件事在协议层就有形状。
 *
 * <p><b>更重要的是它刻意不含任何信息。</b>
 * 这个响应会被返回给「邮箱存在」和「邮箱不存在」两种情况，一字不差。
 * 只要有人往这里加一个字段（比如 {@code sent: true}），
 * 账号枚举的口子就重新开在响应体里了——注释写在这里就是为了防止那一天。
 */
public record OkResponse(boolean ok) {
    public static final OkResponse OK = new OkResponse(true);
}

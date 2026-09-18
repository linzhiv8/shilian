package com.shilian.domain;

/**
 * 这个操作需要登录，但当前没有登录用户。
 *
 * <p>放在 domain 而不是 web：{@code CurrentUser} 是 domain 的端口，
 * 它抛的异常不能反过来依赖 web 层。全局异常处理器负责把它映射成 401。
 */
public class AuthenticationRequiredException extends RuntimeException {

    public AuthenticationRequiredException() {
        super("请先登录");
    }

    public AuthenticationRequiredException(String message) {
        super(message);
    }
}

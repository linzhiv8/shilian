package com.shilian.web;

/** 草稿过期。用户分析完隔太久才点保存时会遇到，提示重新分析即可。 */
public class DraftExpiredException extends RuntimeException {
    public DraftExpiredException(String message) {
        super(message);
    }
}

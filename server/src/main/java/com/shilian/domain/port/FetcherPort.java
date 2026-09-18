package com.shilian.domain.port;

import com.shilian.domain.FetchedPage;

/**
 * 抓网页的能力端口。
 *
 * <p><b>刻意不抛异常。</b>抓取失败是常态而不是异常——SPA、需要登录、开了
 * Cloudflare 的站点都抓不到正文。失败本身是有效数据（还有 meta description
 * 可以用来判断这是什么站），所以永远返回 {@link FetchedPage}，
 * 由下游的降级逻辑处理。
 */
public interface FetcherPort {

    FetchedPage fetch(String rawUrl);
}

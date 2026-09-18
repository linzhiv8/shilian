package com.shilian.analyze;

import com.shilian.config.ShilianProperties;
import com.shilian.domain.FetchedPage;
import com.shilian.domain.port.FetcherPort;
import com.shilian.infrastructure.security.SsrfGuard;
import com.shilian.util.Urls;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Locale;

/**
 * 抓网页。
 *
 * <p><b>核心立场：抓取失败是常态，不是异常。</b>
 * 前端渲染的 SPA、需要登录的页面、开了 Cloudflare 的站点，这三类加起来
 * 在日常会遇到的不小比例。实验室实测 12 个 URL 里就有 2 个抓不到正文。
 * 所以这个方法永远返回 {@link FetchedPage}，不抛异常——失败本身是有效数据，
 * 交给下游的降级逻辑处理。
 *
 * <p>抓不到正文时，{@code <meta name="description">} 往往还在，
 * 而它通常足以判断这个站是干什么的。这就是提示词里「第二档置信度」的来源。
 */
@Service
public class FetchService implements FetcherPort {

    private static final Logger log = LoggerFactory.getLogger(FetchService.class);

    /** 超大页面直接截断下载，正文价值不会随长度线性增长。 */
    private static final int MAX_BODY_BYTES = 3 * 1024 * 1024;

    /** 最多跟几次重定向。链太长基本都是跳转陷阱，而不是正常的短链。 */
    private static final int MAX_REDIRECTS = 5;

    private final ShilianProperties props;
    private final ExtractService extractService;

    public FetchService(ShilianProperties props, ExtractService extractService) {
        this.props = props;
        this.extractService = extractService;
    }

    @Override
    public FetchedPage fetch(String rawUrl) {
        String url = Urls.normalize(rawUrl);
        if (url == null) {
            return FetchedPage.failed("不是合法的 http/https 地址", rawUrl, null);
        }

        /*
         * 重定向在这里手动跟，不用 Jsoup 的 followRedirects。
         *
         * 自动跟随的话，只会拿到最终那一页，中间跳去哪儿了我们完全不知道——
         * 于是「先校验再抓」形同虚设：攻击者给一个正常的公网网址，
         * 让它 302 跳到 http://127.0.0.1/ 就绕过去了。
         * 手动跟每一跳，才能对每一跳都做一次检查。
         */
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            String denied = SsrfGuard.check(current);
            if (denied != null) {
                return FetchedPage.failed("这个地址不允许访问：" + denied,
                        current, Urls.hostOf(current));
            }

            String host = Urls.hostOf(current);
            Connection.Response resp;
            try {
                resp = newConnection(current).execute();
            } catch (org.jsoup.HttpStatusException e) {
                // ignoreHttpErrors 之后理论上不会走到这，留作兜底
                return FetchedPage.failed("HTTP " + e.getStatusCode(), current, host);
            } catch (java.net.http.HttpConnectTimeoutException e) {
                /*
                 * 连接阶段就超时 = 根本没握上手，和「网站慢」是两回事。
                 *
                 * 这句提示必须指向代理。用户遇到这个错的第一反应是「网站挂了」，
                 * 但他浏览器明明打得开——差别就在浏览器走系统代理，
                 * 而 Java 不读系统代理。不把这层说出来，
                 * 他只会反复重试，或者去怀疑一个根本没问题的网站。
                 */
                return FetchedPage.failed(connectFailureHint(), current, host);
            } catch (java.net.ConnectException e) {
                return FetchedPage.failed(connectFailureHint(), current, host);
            } catch (java.net.SocketTimeoutException e) {
                return FetchedPage.failed("超时 " + (props.fetch().timeoutMs() / 1000) + "s", current, host);
            } catch (javax.net.ssl.SSLException e) {
                return FetchedPage.failed("TLS 握手失败（证书问题）", current, host);
            } catch (java.net.UnknownHostException e) {
                return FetchedPage.failed("域名解析失败", current, host);
            } catch (IOException e) {
                return FetchedPage.failed("网络错误：" + e.getClass().getSimpleName(), current, host);
            } catch (RuntimeException e) {
                return FetchedPage.failed("解析失败：" + e.getClass().getSimpleName(), current, host);
            }

            if (isRedirect(resp.statusCode())) {
                String next = resolve(current, resp.header("Location"));
                if (next == null) {
                    // 重定向地址写在 Location 里但没有值，等于链断了
                    return FetchedPage.failed("重定向地址无效", current, host);
                }
                current = next;
                continue;
            }

            return toPage(resp, host);
        }

        return FetchedPage.failed("重定向次数太多（超过 " + MAX_REDIRECTS + " 次）",
                current, Urls.hostOf(current));
    }

    /**
     * 建一个连接，需要的话挂上代理。
     *
     * <p><b>代理必须在连接对象上挂，不能靠 JVM 系统属性。</b>
     * 实测过：{@code -Dhttps.proxyHost=...} 对 jsoup 1.21 完全无效
     * （它默认走 {@code java.net.http.HttpClient}，代理在 client 建好时就定死了）。
     * 靠系统属性的写法会「看起来配了但一点用没有」——比不配更坏，
     * 因为排查时会先怀疑目标网站。
     *
     * <p>每一跳都重新建连接，所以每一跳都会带上代理。
     *
     * <p>包级可见是为了能测：这个方法不发请求，只组装连接对象，
     * 所以可以断言「配了代理之后代理真的挂上去了」——
     * 而「配了却没生效」正是这个功能最容易出的错，光靠肉眼看代码发现不了。
     */
    Connection newConnection(String url) {
        Connection conn = Jsoup.connect(url)
                .userAgent(props.fetch().userAgent())
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .timeout(props.fetch().timeoutMs())
                .followRedirects(false)
                .maxBodySize(MAX_BODY_BYTES)
                // 下面两个都要打开，否则 Jsoup 会在 HTTP 4xx/5xx 和非 HTML 类型上抛异常，
                // 我们就拿不到状态码和 content-type 去拼出有意义的错误信息了。
                .ignoreHttpErrors(true)
                .ignoreContentType(true);

        ShilianProperties.Fetch.Proxy proxy = props.fetch().proxy();
        if (proxy != null && proxy.configured()) {
            conn.proxy(proxy.host(), proxy.port());
        }
        return conn;
    }

    /**
     * 连不上时给用户的话。
     *
     * <p>分两种情况说，因为下一步动作完全不同：配了代理就让他去看代理开没开，
     * 没配代理就告诉他可以配一个。笼统一句「网络错误」等于什么都没说。
     */
    private String connectFailureHint() {
        ShilianProperties.Fetch.Proxy proxy = props.fetch().proxy();
        if (proxy != null && proxy.configured()) {
            return "连不上（已走代理 " + proxy.host() + ":" + proxy.port()
                    + "，确认代理在运行）";
        }
        return "连不上这个站点（本机直连不通，可在 .env.properties 里配抓取代理）";
    }

    /** 把一次成功拿到内容的响应变成 {@link FetchedPage}。 */
    private FetchedPage toPage(Connection.Response resp, String fallbackHost) {
        String finalUrl = resp.url().toString();
        int status = resp.statusCode();
        if (status >= 400) {
            return FetchedPage.failed("HTTP " + status, finalUrl, Urls.hostOf(finalUrl));
        }

        String contentType = resp.contentType();
        if (contentType == null || !isHtml(contentType)) {
            String shortType = contentType == null ? "未知" : contentType.split(";")[0].trim();
            return FetchedPage.failed("非 HTML 内容（" + shortType + "）", finalUrl, fallbackHost);
        }

        Document doc;
        try {
            doc = resp.parse();
        } catch (IOException e) {
            return FetchedPage.failed("读不到页面内容", finalUrl, fallbackHost);
        }

        FetchedPage page = extractService.extract(doc, finalUrl, Urls.hostOf(finalUrl), props.fetch().maxChars());
        log.debug("抓取成功 {} · 正文 {} 字{}", finalUrl, page.fullChars(),
                page.bodyMissing() ? "（正文为空，将走降级判断）" : "");
        return page;
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /** 把 Location 头解析成绝对地址。它可能是相对路径，得基于当前地址拼。 */
    private static String resolve(String base, String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        try {
            return new java.net.URL(new java.net.URL(base), location.trim()).toString();
        } catch (java.net.MalformedURLException e) {
            return null;
        }
    }

    private static boolean isHtml(String contentType) {
        String ct = contentType.toLowerCase(Locale.ROOT);
        return ct.contains("text/html") || ct.contains("application/xhtml");
    }
}

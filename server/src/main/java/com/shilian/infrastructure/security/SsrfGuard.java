package com.shilian.infrastructure.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * SSRF 防护：判断一个网址「能不能让服务器去访问」。
 *
 * <p><b>为什么必须有它。</b>
 * 这个产品的核心动作就是「用户给一个网址，服务器去抓」。
 * 不加限制的话，服务器就成了攻击者的跳板：
 * <ul>
 *   <li>{@code http://127.0.0.1:8080/} —— 探测本机的其他服务</li>
 *   <li>{@code http://192.168.x.x/} —— 扫内网</li>
 *   <li>{@code http://169.254.169.254/} —— 云主机元数据，能拿到临时凭证</li>
 * </ul>
 * 这些请求是从<b>服务器</b>发出的，所以带着服务器的身份和网络位置——
 * 这是「别人能借用你的权限」级别的漏洞，不是「功能有点问题」。
 *
 * <p><b>三条容易漏掉的规则，逐一说明：</b>
 * <ol>
 *   <li><b>只校验域名、不校验解析出来的 IP 是没用的。</b>
 *       攻击者把一个域名 A 记录指向 {@code 127.0.0.1}，域名本身完全正常。</li>
 *   <li><b>只校验第一跳是没用的。</b>
 *       他给一个正常的公网网址，让它 302 跳到 {@code http://127.0.0.1/}——
 *       所以<b>每一跳重定向都要重新校验</b>，这件事由调用方保证（见 FetchService）。</li>
 *   <li><b>IP 有各种等价写法。</b>
 *       {@code http://2130706433/}、{@code http://0x7f.0.0.1/}、{@code http://[::1]/}
 *       都会解析到本机。所以这里不自己解析字符串，一律交给
 *       {@code InetAddress}——它认识所有这些写法。</li>
 * </ol>
 *
 * <p><b>已知限制：挡不住 DNS rebinding。</b>
 * 那是「校验时解析到公网 IP、真正连接时解析到内网 IP」，
 * 彻底解决要在 socket 层把连接绑定到校验过的那个 IP（同时还要处理
 * HTTPS 的 SNI 和证书校验），代价远超这个阶段该承担的。
 * 现在的缓解是缩短了校验与连接之间的时间窗，并且只用于本机自用场景。
 */
public final class SsrfGuard {

    private static final Logger log = LoggerFactory.getLogger(SsrfGuard.class);

    private SsrfGuard() {
    }

    /**
     * 明确禁止的端口。
     *
     * <p>不是「只放行 80/443」，因为不少正常站点跑在 8080、8443 上，
     * 一刀切会误伤。这里禁的是两样：
     * 低于 1024 的系统端口（除 80/443 外没有网页服务）
     * 和那些「一旦能访问就是灾难」的服务端口——Redis、MySQL、
     * Memcached、Elasticsearch 这些通常根本不需要认证。
     */
    private static final Set<Integer> FORBIDDEN_PORTS = Set.of(
            22,    // ssh
            23,    // telnet
            25, 465, 587,  // smtp
            110, 143, 993, 995,  // pop3 / imap
            445,   // smb
            1433, 1521, 3306, 5432,  // 数据库
            3389, 5900,  // 远程桌面
            6379,  // redis
            9200, 9300,  // elasticsearch
            11211, // memcached
            27017  // mongodb
    );

    /**
     * 检查一个网址。
     *
     * @return null 表示允许；非空是给用户看的原因，会被拼进失败提示里
     */
    public static String check(String url) {
        if (url == null || url.isBlank()) {
            return "地址是空的";
        }

        URL u;
        try {
            u = new URL(url);
        } catch (MalformedURLException e) {
            return "网址格式不对";
        }

        String scheme = u.getProtocol() == null ? "" : u.getProtocol().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return "只支持 http 和 https";
        }

        /*
         * 带用户名密码的地址一律拒绝。
         *
         * 正常网页不会这么写。而 http://expected.com@127.0.0.1/ 这种形式
         * 在不同解析器里可能得出不同的 host——有的看 @ 前面，有的看后面。
         * 与其赌解析器一致，不如直接不放行。
         */
        if (u.getUserInfo() != null && !u.getUserInfo().isEmpty()) {
            return "网址里带了用户名密码";
        }

        String host = u.getHost();
        if (host == null || host.isBlank()) {
            return "取不到主机名";
        }
        // IPv6 字面量在 URL 里带方括号，InetAddress 不认
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }

        int port = u.getPort() != -1 ? u.getPort() : (scheme.equals("https") ? 443 : 80);
        if (port != 80 && port != 443 && port < 1024) {
            return "这个端口不让访问（" + port + "）";
        }
        if (FORBIDDEN_PORTS.contains(port)) {
            return "这个端口不让访问（" + port + "）";
        }

        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            // 解析不了就抓不了，不算安全检查的失败，交给上游按「抓取失败」处理
            return null;
        }

        /*
         * 每一个解析结果都要通过。
         *
         * 只看第一个是不够的：一个域名可能有多个 A 记录，
         * 只要其中一个指向内网，轮到那条记录时请求就打到内网去了。
         */
        for (InetAddress a : addrs) {
            String reason = checkAddress(a);
            if (reason != null) {
                log.warn("SSRF 拦截：{} 解析到 {}（{}）", host, a.getHostAddress(), reason);
                return reason;
            }
        }
        return null;
    }

    private static String checkAddress(InetAddress addr) {
        if (addr.isAnyLocalAddress()) {
            return "通配地址";
        }
        if (addr.isLoopbackAddress()) {
            return "本机回环地址";
        }
        if (addr.isLinkLocalAddress()) {
            return "链路本地地址";
        }
        if (addr.isMulticastAddress()) {
            return "组播地址";
        }
        if (addr.isSiteLocalAddress()) {
            return "内网地址";
        }

        if (addr instanceof Inet6Address) {
            byte[] b = addr.getAddress();
            // ::ffff:a.b.c.d —— IPv4 映射地址，按 IPv4 规则再判一次
            if (isIpv4Mapped(b)) {
                return checkIpv4(new byte[] {b[12], b[13], b[14], b[15]});
            }
            /*
             * fc00::/7 唯一本地地址（fc00:: 和 fd00:: 都在这段里）。
             *
             * 必须先 & 0xff 再掩码：b[0] 是 byte，0xfc 当 byte 看是 -4，
             * 直接 (b[0] & 0xfe) 得到的是 252，而 (byte)0xfc 是 -4，
             * 两者永远不相等——整个 fc00::/7 就悄悄漏过去了。
             * 这个坑是测试跑出来才发现的。
             */
            if (((b[0] & 0xff) & 0xfe) == 0xfc) {
                return "IPv6 内网地址";
            }
            // 2001:db8::/32 文档地址
            if (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == (byte) 0xb8) {
                return "IPv6 文档地址";
            }
            return null;
        }

        if (addr instanceof Inet4Address) {
            return checkIpv4(addr.getAddress());
        }
        return null;
    }

    private static boolean isIpv4Mapped(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return b[10] == (byte) 0xff && b[11] == (byte) 0xff;
    }

    private static String checkIpv4(byte[] b) {
        int a = b[0] & 0xff;
        int c = b[1] & 0xff;
        int d = b[2] & 0xff;

        if (a == 0) return "保留地址段（0/8）";
        if (a == 10) return "内网地址（10/8）";
        if (a == 127) return "本机回环地址（127/8）";
        // 这个段里有云主机的元数据服务，是最该拦的一个
        if (a == 169 && c == 254) return "链路本地地址（169.254/16）";
        if (a == 172 && c >= 16 && c <= 31) return "内网地址（172.16/12）";
        if (a == 192 && c == 168) return "内网地址（192.168/16）";
        if (a == 100 && c >= 64 && c <= 127) return "运营商级 NAT 地址（100.64/10）";
        if (a == 192 && c == 0 && d == 0) return "保留地址段（192.0.0/24）";
        if (a == 198 && (c == 18 || c == 19)) return "基准测试地址段（198.18/15）";
        if (a >= 224) return "组播或保留地址段（224/4 及以后）";
        return null;
    }
}

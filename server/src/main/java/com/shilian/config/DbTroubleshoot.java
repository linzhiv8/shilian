package com.shilian.config;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「连不上数据库」时的排查文案，全工程只此一份。
 *
 * <p><b>为什么要单独抽一个类。</b>同一段话有两个出口：
 * <ul>
 *   <li>{@link StartupSelfCheck} —— 服务已经起来了，之后才发现连不上（少见）</li>
 *   <li>{@link DatabaseUnreachableFailureAnalyzer} —— 启动阶段就连不上，
 *       应用直接起不来（<b>这才是常见路径</b>）</li>
 * </ul>
 * 两处各写一份必然会漂，而漂掉的偏偏是「配置文件在哪儿」这种最容易过时、
 * 又最影响能不能自己解决问题的一句。
 *
 * <p><b>判定一律按异常类型，不按消息文本。</b>踩过：域名解析失败时 JDK 会把消息
 * 本地化成「不知道这样的主机。」，按 {@code "Unknown host"} 去匹配会漏掉，
 * 然后掉进「连接超时」那个分支——给出完全错误的排查方向，比不给提示更费时间。
 * 只有 {@code Connection refused} 和连接超时这两种底层类型相同
 * （都是 {@link ConnectException}）的情况，才退回看文本。
 *
 * <p>拿不准时{@link #byRootCause}返回空列表，由调用方退回通用对照表。
 * 宁可给一份泛泛的清单，也不要给一个方向错的结论。
 */
final class DbTroubleshoot {

    private DbTroubleshoot() {
    }

    /** 异常链里最深的那一层，压成一行。 */
    static String rootCause(Throwable e) {
        if (e == null) {
            return "(没有异常信息)";
        }
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return (msg == null || msg.isBlank())
                ? cur.getClass().getName()
                : cur.getClass().getName() + ": " + oneLine(msg);
    }

    /**
     * 多行消息压成一行。JDBC 的异常消息自带换行（{@code Communications link failure}
     * 后面就跟一段），原样拼进 FailureAnalysis 会把排版冲散。
     */
    static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    /** 配置从哪来。按「谁覆盖谁」的顺序列——「改了文件怎么没生效」多半是被上一级盖掉了。 */
    static List<String> whereIsConfig() {
        List<String> out = new ArrayList<>();
        out.add("配置从哪来（后者覆盖前者）：");
        out.add("    server/.env.properties                                    本地开发填这里");
        out.add("    环境变量 SPRING_DATASOURCE_URL / _USERNAME / _PASSWORD     容器里走这个");
        return out;
    }

    /**
     * 按根因给出针对性建议。认不出来就返回空列表。
     *
     * @param root 原始异常（一般是 Spring 包好的 {@code CannotGetJdbcConnectionException}）。
     *             本方法自己沿链找类型、也自己沿链找消息。
     */
    static List<String> byRootCause(Throwable root) {
        if (root == null) {
            return List.of();
        }
        // ⚠ 必须拼**整条链**，不能只看 root.getMessage()。
        //   这里踩过一次：
        //   外层的消息永远是 Spring 那句没信息量的 "Failed to obtain JDBC Connection"，
        //   真正能区分原因的 "Connection refused" / "Access denied" / "Unknown database"
        //   全在最底下那一层。只看外层的话每个分支都命中不了，
        //   然后全部掉进最后那个「防火墙丢包」的兜底分支 ——
        //   提示看着很具体，方向是错的。
        String raw = fullText(root);
        String msg = raw.toLowerCase(Locale.ROOT);

        if (hasCause(root, UnknownHostException.class)) {
            return List.of(
                    "主机名解析不了。",
                    "  要么主机名拼错了，要么这台机器的 DNS 出不去。换成 IP 直连试一下就能区分。");
        }

        if (hasCause(root, ConnectException.class) || hasCause(root, SocketTimeoutException.class)) {
            // 只有这一处必须看文本：连接被拒和连接超时底层都是 ConnectException，
            // 类型上没有区别。中文那条是给消息被本地化的系统兜底的。
            if (msg.contains("refused") || raw.contains("拒绝")) {
                return List.of(
                        "主机解析得了、TCP 也被明确拒了 —— 那个端口上没有 MySQL 在听。",
                        "  检查端口写对没有（默认 3306），以及 MySQL 的 bind-address",
                        "  是不是只监听了 127.0.0.1（那样只有本机连得上）。");
            }
            return List.of(
                    "主机通了但握手一直没完成 —— 典型是防火墙丢包。",
                    "  云服务器要去安全组放行 3306；本机装的 MySQL 看系统防火墙规则。",
                    "  （「拒绝」是立刻返回的，超时必然耗满十几秒，可以按耗时区分。）");
        }

        if (msg.contains("access denied")) {
            return List.of(
                    "账号或密码不对。",
                    "  注意 MySQL 的账号是「用户名 @ 来源主机」两段一起匹配的：",
                    "  只建了 'shilian'@'localhost' 的话，从别的机器连是连不上的，要 'shilian'@'%'。");
        }

        if (msg.contains("unknown database")) {
            return List.of(
                    "库还没建出来。",
                    "  先在 MySQL 里执行：CREATE DATABASE shilian DEFAULT CHARACTER SET utf8mb4;",
                    "  字符集必须是 utf8mb4 —— MySQL 的 utf8 是假的 UTF-8（只存 3 字节），",
                    "  中文会变成 ??? 而且整个过程不报错。");
        }

        if (msg.contains("public key retrieval is not allowed")) {
            return List.of(
                    "MySQL 8 默认的 caching_sha2_password 认证，在不开 SSL 时要先取服务器公钥。",
                    "  连接串加上 allowPublicKeyRetrieval=true（默认值里本来就有，说明被覆盖掉了）。");
        }

        return List.of();
    }

    /** 把整条异常链的消息拼成一段文本，供关键词匹配用。 */
    static String fullText(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable cur = e; cur != null; cur = cur.getCause()) {
            if (cur.getMessage() != null) {
                sb.append(cur.getMessage()).append(' ');
            }
            if (cur.getCause() == cur) {
                break;
            }
        }
        return sb.toString();
    }

    /**
     * 认不出根因时的通用对照表。
     *
     * <p>刻意保留「域名 / 端口 / 防火墙 / 账号 / 库 / 认证插件」这六项，
     * 因为 JDBC 会把它们全都报成同一句 {@code Communications link failure}，
     * 不给对照表就只能靠猜。
     */
    static List<String> bySymptom() {
        return List.of(
                "常见原因对照：",
                "    不知道这样的主机 / UnknownHostException   主机名写错，或 DNS 不通",
                "    Connection refused                       主机对了，但那个端口没有 MySQL",
                "    连接超时                                  防火墙丢包（云服务器看安全组）",
                "    Access denied for user                    账号密码错，或来源主机没授权",
                "    Unknown database                          库还没建",
                "    Public Key Retrieval is not allowed       连接串少了 allowPublicKeyRetrieval=true");
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable cur = e; cur != null; cur = cur.getCause()) {
            if (type.isInstance(cur)) {
                return true;
            }
            if (cur.getCause() == cur) {
                break;
            }
        }
        return false;
    }
}

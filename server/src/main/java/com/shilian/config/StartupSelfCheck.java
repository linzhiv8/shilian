package com.shilian.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 启动自检：把「实际连的是哪个库、里面有多少东西」打到日志里。
 *
 * <p><b>为什么需要它。</b>
 * 「连错了库」这件事在任何数据库上都是静默故障：应用照常启动、接口照常 200，
 * 只是数据全空。成因是连接串指向了另一个库、或者那个库刚建出来是空的。
 * 这类问题没法靠报错发现——因为**什么都没错**，所以只能主动把「实际连到哪、
 * 里面有多少东西」打出来。
 *
 * <p>注意它<b>管不了「连不上」</b>：库连不上时容器刷新就中断了，
 * 这个类作为 {@link ApplicationRunner} 根本不会执行。那条路径由
 * {@link DatabaseUnreachableFailureAnalyzer} 负责，见那个类的注释。
 *
 * <p>报的是四样东西：
 * <ul>
 *   <li><b>连到哪</b>——JDBC URL（不含密码）+ 实际的库名，由数据库自己回答</li>
 *   <li><b>有没有连上</b>——真去拿一次连接，顺带报数据库版本</li>
 *   <li><b>里面有什么</b>——schema 版本 + 链接数 + 账号数。
 *       这一行是「我的数据呢」这个问题的直接答案：
 *       数字不对就说明连错库了，不用再猜</li>
 *   <li><b>配置齐不齐</b>——AI Key 和抓取代理，两个都是「没配也不报错」的东西</li>
 * </ul>
 *
 * <p>整体 try-catch 兜底：它是 {@link ApplicationRunner}，抛异常会让应用起不来。
 * 一个诊断组件绝不该成为服务挂掉的原因——自检失灵最多少一条提示。
 */
@Component
public class StartupSelfCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupSelfCheck.class);

    private final ShilianProperties props;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final String datasourceUrl;

    public StartupSelfCheck(ShilianProperties props, DataSource dataSource, JdbcTemplate jdbc,
                            @Value("${spring.datasource.url}") String datasourceUrl) {
        this.props = props;
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            log.info("工作目录 {}", Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize());
            log.info("数据库 {}", maskPassword(datasourceUrl));

            checkConnection();
            reportContents();
            checkApiKey();
            reportProxy();
        } catch (RuntimeException e) {
            log.warn("启动自检没能跑完（不影响使用）：{}", e.toString());
        }
    }

    /**
     * URL 里通常不含密码（密码是单独的配置项），但有人会把它拼进 URL
     * ——比如 {@code ?password=xxx}。这种时候不能原样打进日志。
     */
    private String maskPassword(String url) {
        if (url == null) {
            return "(未配置)";
        }
        return url.replaceAll("(?i)(password|pwd)=[^&]*", "$1=***");
    }

    /**
     * 真去拿一次连接。
     *
     * <p>比「打印配置」强的地方在于：配置写了不等于连得上。
     * 密码错、库不存在、网络不通，都会在这里露出来——
     * 而不是等到用户点第一次保存才发现。
     *
     * <p>顺带报数据库自己的名字（{@code SELECT DATABASE()}）：
     * 它和 URL 里写的库名不一致时，说明连接参数或服务端的默认库起了作用，
     * 那正是「我以为连的是 A，实际连的是 B」这类问题的关键线索。
     */
    private void checkConnection() {
        try (Connection con = dataSource.getConnection()) {
            DatabaseMetaData md = con.getMetaData();
            log.info("数据库连接正常 {} {}",
                    md.getDatabaseProductName(), md.getDatabaseProductVersion());
            try (Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery("SELECT DATABASE()")) {
                if (rs.next()) {
                    log.info("当前库 {}", rs.getString(1));
                }
            }
        } catch (Exception e) {
            // ⚠ 这一段其实很少跑得到：库在启动阶段就连不上的话，
            //   Migrations 的 @PostConstruct 会先失败、容器刷新中断，
            //   本类作为 ApplicationRunner 根本不会执行。
            //   那条路径由 DatabaseUnreachableFailureAnalyzer 负责（见它的类注释）。
            //   这里留着是为了「启动时连得上、之后库挂了」的情况，以及兜底不崩。
            //
            // 把异常对象整个传进去（不是 e.getMessage()）：这是最需要堆栈的一处，
            // 「连不上库」至少有七种原因，而它们的一句话描述几乎一样，
            // 只有堆栈里的异常类型能区分。日志 pattern 末尾的 %wEx 会打堆栈。
            log.error("");
            log.error("连不上数据库。", e);
            log.error("  根因 {}", DbTroubleshoot.rootCause(e));
            for (String line : DbTroubleshoot.whereIsConfig()) {
                log.error("  {}", line);
            }
            log.error("");
        }
    }

    /**
     * schema 版本 + 数据量。
     *
     * <p>这一行是整个自检里最有用的一行。它回答的是
     * 「我刚改完配置重启，数据还在不在」——而这个问题的答案，
     * 以前要么靠翻页面看，要么靠去数据库里手动 count。
     */
    private void reportContents() {
        try {
            Integer version = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(version), 0) FROM schema_version", Integer.class);
            Integer links = jdbc.queryForObject("SELECT COUNT(*) FROM link", Integer.class);
            Integer users = jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class);

            log.info("schema 版本 {} · 链接 {} 条 · 账号 {} 个",
                    version, links == null ? 0 : links, users == null ? 0 : users);

            if (users != null && users == 0) {
                log.info("  还没有账号，去前端注册一个就能开始用了");
            }
        } catch (Exception e) {
            // warn 级别用 e.toString() 而不是整个异常：这里是「不影响使用」的降级，
            // 堆栈会把启动日志淹掉。但 toString() 至少带上异常类型——
            // 只打 getMessage() 的话，SQL 语法错误和权限错误可能都是空字符串，
            // 日志里就只剩「读数据量失败：」这几个字，等于没打。
            log.warn("读数据量失败（不影响使用）：{}", e.toString());
        }
    }

    /**
     * API Key：没读到就说清楚去哪儿找。
     *
     * <p>它读不到时应用不会报错（{@code optional:file:} 是刻意这么配的，
     * 为了让「第一次跑、还没配 Key」也能起来看界面），
     * 但用户会以为「AI 坏了」。所以这里必须把「为什么没有」讲出来。
     *
     * <p>两个候选路径都看一眼，因为 {@code spring.config.import} 就是两个都试的。
     */
    private void checkApiKey() {
        if (props.deepseek().configured()) {
            log.info("AI 配置 已就绪（模型 {}）", props.deepseek().model());
            return;
        }

        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path inServer = cwd.resolve("server").resolve(".env.properties");
        Path here = cwd.resolve(".env.properties");

        log.warn("AI 配置 未配置 —— 读不到 .env.properties，AI 分析功能不可用");
        if (Files.exists(inServer)) {
            log.warn("  {} 是存在的，但没被读到。检查 spring.config.import 那两行。", inServer);
        } else if (Files.exists(here)) {
            log.warn("  {} 是存在的，但里面没有 DEEPSEEK_API_KEY。", here);
        } else {
            log.warn("  复制 server/.env.properties.example 为 server/.env.properties 并填入 Key 即可。");
        }
    }

    /** 代理是「配了却可能没生效」的典型，打出来省得回头猜。 */
    private void reportProxy() {
        ShilianProperties.Fetch.Proxy proxy = props.fetch().proxy();
        if (proxy != null && proxy.configured()) {
            log.info("抓取代理 {}:{}", proxy.host(), proxy.port());
        } else {
            log.info("抓取代理 未配置（直连）");
        }
    }
}

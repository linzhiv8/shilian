package com.shilian.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import java.util.ArrayList;
import java.util.List;

/**
 * 连不上数据库时，用一段能照着做的提示，换掉那 60 行 Spring 异常堆栈。
 *
 * <p><b>为什么需要它（以及为什么 {@link StartupSelfCheck} 顶不上）。</b>
 * {@code StartupSelfCheck} 是 {@code ApplicationRunner}，跑在容器刷新<b>完成之后</b>；
 * 而 {@code Migrations} 的 {@code @PostConstruct} 在刷新<b>过程中</b>就会去连库。
 * 所以「MySQL 连不上」这个最常见的故障，实际表现是：
 * <pre>
 *   Caused by: org.springframework.beans.factory.BeanCreationException:
 *       Error creating bean with name 'migrations' ...
 *     Caused by: CannotGetJdbcConnectionException: Failed to obtain JDBC Connection
 *       Caused by: CommunicationsException: Communications link failure
 *         Caused by: java.net.UnknownHostException: 不知道这样的主机。 (xxx)
 *     ... 40 行框架栈
 * </pre>
 * 自己写的那段提示一行都不会打出来 —— 它根本没机会执行。
 *
 * <p>而 Spring Boot 给这件事留了专门的扩展点：{@code FailureAnalyzer}。
 * 命中之后它会用「Description / Action」两段式输出<b>替代</b>整个堆栈，
 * 这是框架设计好的路径，比自己 try-catch 打日志更稳。
 *
 * <p><b>注册方式。</b>{@code META-INF/spring.factories}，key 是
 * {@code org.springframework.boot.diagnostics.FailureAnalyzer}。
 * 注意这里<b>不是</b>自动配置，所以不能写进 {@code META-INF/spring/*.imports}；
 * 写错位置不报错、不警告，这个类就是不生效（同类坑踩过一次）。
 *
 * <p><b>为什么构造器要收一个 {@link Environment}。</b>
 * 为了把「应用实际用的是哪个连接串」打出来——「我改了 .env.properties 怎么没生效」
 * 这类问题看一眼就知道了。而 Spring Boot 3.5 是通过
 * {@code SpringFactoriesLoader} 的 ArgumentResolver 来实例化分析器的，
 * 它认得 {@code Environment} 和 {@code BeanFactory} 这两个参数，所以构造器能直接拿到。
 * <pre>
 *   ⚠ 不能写成「无参构造 + @Autowired 字段」——那条路依赖 BeanPostProcessor，
 *     而分析器不是通过 beanFactory.createBean() 创建的，字段会永远是 null。
 *     这个坑实测过：连接串那一行怎么都不出现。
 *   ⚠ 也不能两个构造器都留着，因为选哪个由框架决定，不确定。
 *
 *   代价：容器还没建起来就失败的情况（比如配置文件语法错），
 *   ArgumentResolver 拿不到 Environment，这个类会被跳过、退回原始堆栈。
 *   可以接受 —— 那种故障本来也不是「连不上库」。
 * </pre>
 *
 * <p>只接 {@link CannotGetJdbcConnectionException}，不接 {@code SQLException}：
 * 迁移脚本里的 SQL 语法错误同样会带 SQLException，但那种情况需要的是<b>原始堆栈</b>
 * （要看是哪条 SQL、哪一行），换成这段提示反而是帮倒忙。
 */
public class DatabaseUnreachableFailureAnalyzer
        extends AbstractFailureAnalyzer<CannotGetJdbcConnectionException> {

    private final Environment environment;

    public DatabaseUnreachableFailureAnalyzer(Environment environment) {
        this.environment = environment;
    }

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, CannotGetJdbcConnectionException cause) {
        List<String> description = new ArrayList<>();
        description.add("连不上 MySQL，应用起不来。");
        description.add("");
        description.add("这是好事：比起得来但连到空库上强 —— 那种情况不报错，"
                + "只是「数据全没了」，反而更难查。");
        description.add("");

        String url = datasourceUrl();
        if (url != null) {
            description.add("应用实际用的连接串：");
            description.add("  " + maskPassword(url));
            description.add("");
        }

        description.add("根因：");
        description.add("  " + DbTroubleshoot.rootCause(cause));

        List<String> specific = DbTroubleshoot.byRootCause(cause);
        if (!specific.isEmpty()) {
            description.add("");
            description.addAll(specific);
        }

        List<String> action = new ArrayList<>();
        action.addAll(DbTroubleshoot.whereIsConfig());
        action.add("");
        action.addAll(DbTroubleshoot.bySymptom());

        return new FailureAnalysis(String.join(System.lineSeparator(), description),
                String.join(System.lineSeparator(), action), cause);
    }

    /** 取不到就返回 null —— 调用方会跳过那一行。 */
    private String datasourceUrl() {
        if (environment == null) {
            return null;
        }
        return environment.getProperty("spring.datasource.url");
    }

    /**
     * URL 里通常没有密码（密码是独立的配置项），但确实有人会拼成
     * {@code ?password=xxx}。这个类会把内容打到日志和终端，所以必须过一遍。
     */
    private String maskPassword(String url) {
        return url.replaceAll("(?i)(password|pwd)=[^&]*", "$1=***");
    }
}

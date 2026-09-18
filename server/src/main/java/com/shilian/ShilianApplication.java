package com.shilian;

import com.shilian.config.ShilianProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 拾链后端入口。
 *
 * <p>连哪个库完全由配置决定：{@code spring.datasource.url} 那一组。
 * 本地开发、容器部署用的是同一个 profile、同一份代码，区别只在
 * {@code server/.env.properties} 里填的地址——**不存在「本地用另一个库」的分支**。
 * 这样「本地跑通了」和「部署起来能跑」验的是同一条路径。
 *
 * <p>连不上库时应用会拒绝启动（{@code Migrations} 在容器刷新过程中就要连库），
 * 并由 {@code DatabaseUnreachableFailureAnalyzer} 输出一段能照着做的提示。
 * 这是刻意的：**报错是安全的，静默是危险的**——如果带着一个连不上的库照常启动，
 * 用户看到的会是「我的数据全没了」，比启动失败难查得多。
 */
@SpringBootApplication
@EnableConfigurationProperties(ShilianProperties.class)
@EnableScheduling
public class ShilianApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShilianApplication.class, args);
    }
}

package com.shilian.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用的配置契约，对应 {@code application.yml} 里的 {@code shilian.*}。
 *
 * <p><b>为什么集中成一个 record，而不是散落的 {@code @Value}。</b>
 * 集中之后改一个字段名编译器会把所有用到的地方指出来；用
 * {@code @Value("${...}")} 的话键名写错只会在运行时变成 null，
 * 而 null 会一路飘到「模型返回的内容不对」这种看不出源头的地方。
 *
 * <p><b>字段名和配置键是绑定的。</b>Spring Boot 的宽松绑定会把驼峰字段名
 * 映射到 kebab-case：{@code maxTokens} ↔ {@code max-tokens}、
 * {@code timeoutMs} ↔ {@code timeout-ms}。所以改字段名等于改配置键，两边要一起改。
 *
 * <p><b>默认值不写在这里。</b>一律写在 {@code application.yml} 里——
 * 那样「为什么是这个数」的解释可以紧挨着值写，而且默认值只有一处。
 * 这个类只声明「有什么配置」，不声明「默认是多少」。
 *
 * <p>各项的含义、取值理由、以及踩过的坑，见 {@code application.yml} 里对应段落的注释。
 */
@ConfigurationProperties(prefix = "shilian")
public record ShilianProperties(Deepseek deepseek, Fetch fetch, Web web, Embedding embedding,
                                Resilience resilience, Auth auth) {

    public record Deepseek(
            String baseUrl,
            String model,
            String apiKey,
            double temperature,
            int maxTokens,
            int maxRepair,
            int timeoutMs
    ) {
        public boolean configured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    /**
     * 调用外部服务时的韧性参数。
     *
     * <p>这些数字的共同点：它们全都是「上游不听话时我们怎么自保」，
     * 和业务逻辑无关，所以集中放在一处，而不是散在各个客户端里各写一套。
     *
     * @param maxAttempts   网络层最多尝试几次（含首次）。针对可重试的失败，
     *                      与「模型输出不合法让它重写」那套重试是两码事
     * @param backoffBaseMs 退避基数，实际等待 = 基数 × 2^(第几次-1) 再加抖动
     * @param budgetMs      单次调用的总时间预算。预算耗尽就不再重试——
     *                      没有它的话，重试会把最坏耗时翻着倍往上翻
     * @param failureThreshold 连续失败几次后熔断
     * @param openMs        熔断打开后冷却多久
     * @param maxConcurrentAnalyses 同时最多几个分析在跑
     */
    public record Resilience(
            int maxAttempts,
            long backoffBaseMs,
            long budgetMs,
            int failureThreshold,
            long openMs,
            int maxConcurrentAnalyses
    ) {}

    /**
     * 登录安全。
     *
     * @param maxFailedAttempts 连续失败几次就锁定。不设成无限：
     *                          撞库的人最怕的就是「试几次就锁住」
     * @param lockMinutes       锁多久。太短挡不住，太长会烦到真人——
     *                          15 分钟是「够让撞库变得不划算、又不至于让用户骂人」的折中
     * @param maxRegistrationsPerHour  每个 IP 一小时内能注册几个账号。
     *                                 注册接口不做限流的话可以被无限刷，
     *                                 哪怕刷出来的账号毫无用处，也是在浪费存储和后续所有查询的成本
     * @param maxLoginAttemptsPerMinute 每个 IP 一分钟内能试几次登录。
     *                                 账号锁定挡的是「盯着一个账号撞」，
     *                                 这个挡的是「拿一堆账号各试一次」——两者必须都有
     * @param maxRegistrationsPerEmailPerDay 同一个邮箱一天内能发起几次注册。
     *                                 光按 IP 限挡不住换 IP 的人：代理池很便宜，
     *                                 而「每个邮箱只试一次」的攻击者在 IP 维度上完全正常。
     *                                 代价是别人可以用你的邮箱把额度打满、让你注册不了，
     *                                 所以这个数给得宽松——正常人手滑重试碰不到，挡的是「反复」
     */
    public record Auth(
            int maxFailedAttempts,
            int lockMinutes,
            int maxRegistrationsPerHour,
            int maxLoginAttemptsPerMinute,
            int maxRegistrationsPerEmailPerDay
    ) {}

    /**
     * 抓取网页。
     *
     * @param proxy 抓取时走的 HTTP 代理，{@code host} 留空表示直连。
     *              <p><b>为什么需要它。</b>国内直连 github.com 这类站点，
     *              TCP 连接阶段就会超时——报的是 {@code HttpConnectTimeoutException}，
     *              而用户会觉得莫名其妙，因为他浏览器明明打得开。
     *              差别在于：浏览器走系统代理，<b>Java 不会自动读系统代理设置</b>
     *              （Windows 的注册表、环境变量里的 http_proxy 它都不看）。
     *              <p>只在抓取这一条路上走代理，不设成 JVM 全局属性：
     *              模型服务在国内可以直连，把整台 JVM 塞进代理，
     *              等于平白多一个「代理没开就所有功能全挂」的依赖。
     */
    public record Fetch(int timeoutMs, int maxChars, String userAgent, Proxy proxy) {

        /** HTTP 代理。{@code host} 为空表示不用代理。 */
        public record Proxy(String host, Integer port) {
            public boolean configured() {
                return host != null && !host.isBlank() && port != null && port > 0;
            }
        }
    }

    public record Web(String allowedOrigins) {
        public String[] origins() {
            return allowedOrigins == null || allowedOrigins.isBlank()
                    ? new String[0]
                    : allowedOrigins.split("\\s*,\\s*");
        }
    }

    /**
     * 语义搜索用的向量服务。
     *
     * <p><b>为什么单独一份配置而不是复用 DeepSeek。</b> DeepSeek 只提供
     * chat completions，没有 embeddings 接口（2026-09 确认过官方文档）。
     * 所以向量必须来自别处，也就必然是一套独立的地址 + 密钥。
     *
     * <p>协议按 OpenAI 的 {@code POST /embeddings} 写，因为这是事实标准：
     * 硅基流动、智谱、DashScope、Ollama、LM Studio 都能直接对上，
     * 换一家只改 base-url 和 model 两个值。
     *
     * <p>刻意<b>没有 dim 配置</b>：维度由服务端返回的向量长度决定，我们存下来就是了。
     * 让用户手填维度只会多一个能填错、且填错了不报错的地方
     * （填大了截断、填小了补零，余弦照样算得出来，只是结果全错）。
     */
    public record Embedding(
            String baseUrl,
            String model,
            String apiKey,
            int batchSize,
            double minScore,
            int timeoutMs
    ) {
        public boolean configured() {
            return apiKey != null && !apiKey.isBlank()
                    && baseUrl != null && !baseUrl.isBlank()
                    && model != null && !model.isBlank();
        }

        public String endpoint() {
            return baseUrl.replaceAll("/+$", "") + "/embeddings";
        }

        /** 没配置时给一句能直接照做的事，而不是一个空字符串或 null。 */
        public String reason() {
            if (apiKey == null || apiKey.isBlank()) {
                return "没有配置向量服务的 Key。语义搜索需要一个 embeddings 接口"
                        + "（DeepSeek 不提供），在 server/.env.properties 里写 EMBEDDING_API_KEY=xxx";
            }
            if (baseUrl == null || baseUrl.isBlank()) {
                return "没有配置向量服务地址（EMBEDDING_BASE_URL）";
            }
            if (model == null || model.isBlank()) {
                return "没有配置向量模型名（EMBEDDING_MODEL）";
            }
            return "语义搜索不可用";
        }
    }
}

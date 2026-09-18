# 拾链后端 · 架构重构方案

> 面向对象：单用户、本机运行、数据量几百到几千条的 Spring Boot 3.5 + Java 21 + SQLite 单体。
> 状态：**方案待评审，尚未动代码。**

> **⚠ 这是一份历史文档，不是当前状态。** 写它的时候重构还没开始，
> 所以下面「24 个 Java 文件零单元测试」「连接池开 1」「`SQLite` 单体」
> 这些都是**当时的现状**，不是现在的情况。
> （不过「零单元测试」这条现在又成立了：重构时写过 123 个测试，
> 上线前把 `src/test/` 删了。）
> 当前状态看 `README.md` 和 `REQUIREMENTS.md` 第 8 节。
> 保留它的价值在于：它是「为什么会有这套结构」的原始论证。

---

## 0. 结论先行

这套后端**不需要分布式重构**。它的"扩展性"瓶颈不是流量，而是下面三件事：

| 真正的瓶颈 | 表现 |
|---|---|
| **改不动** | 24 个 Java 文件零单元测试。业务规则（URL 归一化、校验、钳位、周报指纹、correction 语义）全靠一个**要真花 token** 的端到端冒烟兜底。改一行不敢确认没踩坏别的。 |
| **外部依赖一抽风就干等** | `DeepSeekClient` 只有 90s 超时，无退避、无熔断、无并发闸门。AI 慢的时候用户干等；连续失败一路打到 `AnalysisFailedException`。 |
| **靠注释守纪律** | 连接池 `maximum-pool-size: 1` 依赖"绝不在持有连接时调 AI"。这条规矩写在 README 和 Javadoc 里，**但没有任何机制保证它被遵守**。一次误加 `@Transactional` 就全站堵死。 |

所以重构的目标是：**让它敢改、坏得不扩散、涨到几千条不卡**。不是让它扛住十万 QPS。

**明确的非目标**（现在不要做）：微服务拆分、Kafka/消息队列、K8s、读写分离、迁 Postgres、多租户。
理由：单用户本机工具，任何分布式组件带来的运维成本都远超收益。但架构上**留缝**——边界用接口切开，将来真要拆，是"搬模块"而不是"重写"。

---

## 1. 现状盘点

### 1.1 做对了的，不要动

这些是地基，重构建立在它们之上，不是推翻它们：

- 虚拟线程（`spring.threads.virtual.enabled=true`）——长 IO 等待不占平台线程，选对了
- `GlobalExceptionHandler` 统一错误出口 + 语义化错误码（404/400/409/502/503 各有指向）
- 排序白名单 `orderBy()`、参数钳位（当时配了 `tools/review-clamp.mjs` 回归守着，该脚本已删）
- 向量惰性补齐 + `text_hash` 指纹 + `model`/`dim` 落库（换模型能识别"该重算了"）
- 周报缓存带数据指纹，对不上就重写
- `LinkRepository.Reanalysis` 用独立 record 在**类型层面**写死"这次不许碰哪些字段"
- `sanitizeDomain` / `sanitizePurposes` 兜底：校验失败也不返回让前端崩的数据

### 1.2 风险清单（按严重度）

| 级别 | 问题 | 位置 | 后果 |
|---|---|---|---|
| **P0** | 零单元测试 | 全局 | 不敢改，这是所有扩展性问题的根 |
| **P0** | 单连接池无守卫 | `application.yml` + 所有 `@Repository` | 一次慢 SQL / 误加事务 → 全站排队 |
| **P0** | 外部调用无韧性 | `DeepSeekClient` / `EmbeddingClient` | 干等 90s、雪崩、无并发上限 |
| **P1** | 无 schema 迁移 | `schema.sql` | 已被迫把指纹塞进 JSON 的 `_fp` 字段（README 自述） |
| **P1** | 时间规则下推 SQL | `REVIEW_WHERE` 的 `julianday('now','localtime')` | 不可测、换时区就错、规则无法复用 |
| **P1** | 仓储层拼提示词 | `LinkRepository implements CorrectionsSource` | 职责越界，DB 层不该知道提示词长什么样 |
| **P1** | 全量拉取 + 客户端筛选 | `/api/links` | 几千条后响应体数 MB |
| **P2** | 无可观测性 | 全局 | "这次为什么慢"无法回答，无 traceId、无耗时指标 |
| **P2** | `LIKE` 全文检索 | `LinkRepository.search` | 几千条中文长文本后显著变慢 |
| **P2** | `ORDER BY RANDOM()` | `dueForReview` | 全表扫描排序，几千条开始变慢 |
| **P2** | 草稿在内存 | `DraftStore` | 重启即丢（当前可接受）；是"服务端有状态"的口子 |
| **P2** | 无 API 版本 / 分页契约 | `/api/**` | 前后端耦合，加字段易破坏前端 |

---

## 2. 架构目标

1. **业务规则可穷举测试** —— 领域逻辑是纯函数，零 IO，单测毫秒级跑完
2. **外部依赖可替换** —— 换 LLM 厂商 / 换向量服务 / 断网，都不改业务代码
3. **依赖坏得不扩散** —— 有超时预算、退避重试、熔断、并发闸门
4. **数据演进有据可查** —— schema 变更版本化、可回滚
5. **涨到几千条不卡** —— 查询下推、分页、索引到位
6. **出问题能回答"为什么"** —— traceId + 分段耗时 + 慢查询告警

---

## 3. 目标分层

```
com.shilian
├── interface/          协议层：Controller / DTO / 统一异常 / 参数校验
│   └── 只做协议转换。不含业务判断，不含 SQL。
│
├── application/        用例层：编排与韧性
│   ├── AnalyzeLinkUseCase        抓取→提取→提示词→调用→校验→重试
│   ├── ReviewQueueUseCase        回顾队列与计数（共用同一判据）
│   ├── WeeklyDigestUseCase       统计 + AI 摘要 + 指纹缓存
│   └── SemanticSearchUseCase     惰性补齐 → 排序 → 阈值兜底
│   └── 超时预算、退避重试、熔断、并发闸门、事务边界，只在这一层
│
├── domain/             领域层：纯 Java，零框架零 IO
│   ├── model/          Link, AnalyzeDraft, AnalysisResult, FetchedPage
│   ├── policy/         ReviewPolicy（闲置判据）, SearchPolicy（阈值兜底）
│   ├── vo/             Domain, Purpose, ContentType（含中文名与校验）
│   ├── port/           LlmPort, EmbeddingPort, FetcherPort, LinkStore, Clock
│   └── support/        Urls（归一化 / dedupKey）, Digests（指纹）, Json
│
└── infrastructure/     适配层：实现 port
    ├── persistence/    JdbcLinkStore, JdbcEmbeddingStore, Migrations
    ├── llm/            DeepSeekLlm, FakeLlm
    ├── embedding/      OpenAiCompatEmbedding, FakeEmbedding
    ├── fetch/          JsoupFetcher
    └── time/           SystemClock, MutableClock（测试用）
```

**一条铁律：依赖方向只能向内。** `domain` 不 import Spring、JDBC、HTTP 任何东西。
`application` 依赖 `domain.port` 接口，不知道实现类是谁。

### 关键目录动作

- `LinkRepository` 不再 `implements CorrectionsSource`。提示词拼装搬到 `application` 或 `domain.support`，仓储只管存取。
- `patch()` 接收的 `Map<String, Object>` 换成强类型 `EditLinkCommand` + 白名单校验，编译期就能挡住非法字段。
- `LinkItem` 从纯 record 升级为带行为的领域对象：`isDueForReview(now, days)`、`searchableText()` 这类方法回到它自己身上。

---

## 4. 关键机制（逐条讲为什么）

### 4.1 Clock 注入 —— 干掉 SQL 里的时间运算

**现在**：`julianday('now','localtime') - julianday(COALESCE(last_opened_at, created_at)) >= :days`
问题有三：规则不可测、换时区/换机器可能错、同一个判据无法在别处复用。

**改法**：注入 `Clock`，Java 侧算出 `cutoff`，SQL 只做参数化比较。

```java
// domain/port/Clock.java
public interface Clock { Instant now(); }

// domain/policy/ReviewPolicy.java —— 纯函数，可穷举
public record ReviewPolicy(int idleDays) {
    public boolean isDue(LinkItem item, Instant now) {
        if (item.starred()) return false;
        if (item.status().equals("used") || item.status().equals("read")) return false;
        Instant ref = item.lastOpenedAt() != null ? item.lastOpenedAt() : item.createdAt();
        return Duration.between(ref, now).toDays() >= idleDays;
    }
}
```

SQL 变成 `WHERE COALESCE(last_opened_at, created_at) <= :cutoff`，`cutoff` 由用例层传入。
**收益**：这条规则从此有单测；`dueForReview` 和 `countDueForReview` 共用同一份判据代码，而不是靠"同一个 SQL 常量"这种脆弱约定。

### 4.2 端口化外部依赖 —— 换厂商不改业务

`LlmPort` / `EmbeddingPort` / `FetcherPort` 各一个窄接口，现有 `DeepSeekClient`、`EmbeddingClient`、`FetchService` 降级为实现类。

```java
public interface LlmPort {
    boolean configured();
    String unavailableReason();
    LlmReply complete(List<ChatMessage> messages) throws LlmException; // 内部自带超时
}
```

**收益**：`AnalyzeLinkUseCase` 可以不启 Spring、不联网、用 `FakeLlm` 跑完整重试路径——现在这条路径只能靠 `MOCK_BROKEN=json|text` 起一个假服务才能验，成本高所以很少验。

### 4.3 韧性四件套 —— 让外部依赖坏得不扩散

**建议手写约 80 行的装饰器，不引 Resilience4j。** 理由和项目一贯风格一致（用 JDK `HttpClient` 而不是 WebClient）：只用到 4 个模式，引入一个带传递依赖的库不划算。若将来要 RateLimiter/舱壁隔离细化再换 Resilience4j 也不迟。

| 模式 | 参数 | 为什么 |
|---|---|---|
| **总超时预算** | 分析整体 30s，用 `deadline` 贯穿 | 现在 90s×2 最坏 3 分钟。`deadline` 让"还剩多少预算"成为显式参数，预算不足就**不再重试**、直接降级返回 |
| **退避重试** | 最多 2 次，指数退避 + 抖动（500ms → 1s） | 只针对**网络/5xx/429** 重试。现在任何失败都不重试，一次抖动就整条失败 |
| **熔断** | 连续 5 次失败开闸，30s 后半开 | AI 挂了要**快速失败**给用户人话提示，而不是让他每次等 30 秒 |
| **并发闸门** | `Semaphore(2)`，满了返回 429 + "同时分析的太多了，稍等" | 书签小工具连点几次就是几个并发长任务。排队到超时不如直接说清楚 |

**熔断打开时的降级口径**（沿用现有产品哲学）：不抛 500，返回"AI 暂时连不上，你可以直接存这条，之后再补分析"——保存路径不依赖 AI，这条链路必须始终可用。

### 4.4 单连接池：把"纪律"变成"机制"

`pool-size=1` 对 SQLite 单写者模型是对的，**保留**。但要补三道保险：

1. **慢查询告警**：包一层 `JdbcTemplate` 代理，超过 200ms 打 WARN 并带上 traceId 和 SQL 摘要。把"迟早会破的纪律"变成"破了立刻看得见"。
2. **写操作串行化**：所有写走一个 `WriteExecutor`（单线程虚拟线程调度器），读走连接池。将来要放开读并发时，改这一处即可。
3. **静态检查**：ArchUnit 加一条规则——`domain` 包不得依赖 `org.springframework` / `java.sql`。CI 里跑，破了直接失败。

### 4.5 schema 迁移：用 `PRAGMA user_version`，不上 Flyway

**Flyway 社区版不官方支持 SQLite**（要引第三方 dialect，且和 `CREATE TABLE IF NOT EXISTS` 的历史库共存麻烦）。SQLite 自带 `PRAGMA user_version`，写一个版本化迁移器约 100 行、零依赖、完全可控：

```java
// infrastructure/persistence/Migrations.java
record Migration(int version, String description, List<String> sql) {}

// 启动时：读 user_version → 依次执行未应用的 → 每个版本一个事务
// → 全部成功后 PRAGMA user_version = N
```

`V1__baseline.sql` 就是现有 `schema.sql` 全量（保证老库跳过）。之后 `V2__add_digest_fp_column.sql` 之类就能把 `_fp` 从 JSON 里解放出来。

**越早引越便宜** —— 现在库里数据还少，等真数据多了再补就晚了。

### 4.6 查询下推与分页

- `/api/links` 增加 `limit` + `cursor`（`created_at|id` 游标，不用 offset——列表按 `created_at DESC` 排，offset 在插入新数据时会漂移）
- 用途过滤：现在在 Java 里 `containsAll`。几千条内没问题，但条件要能**同时**在 SQL 侧表达，否则下推那天要重写。用 `json_each` 或加一张 `link_purpose(link_id, purpose)` 关联表，选后者——SQLite 的 JSON 扩展依赖编译选项，关联表更稳且能走索引
- 单独的计数接口 `/api/links/count`，让前端不用拉全量才知道有多少
- 前端筛选从"全量拉回客户端过滤"切到服务端，作为**数据量阈值**触发的改造，不提前做

### 4.7 检索与随机

- **FTS5 + trigram**：SQLite 3.34+ 自带 trigram 分词器，中文无需额外分词库。`link_fts` 虚表 + 触发器同步，替代 `LIKE '%...%'`
- **随机**：`ORDER BY RANDOM()` 换成"先只查 id（覆盖索引，很轻）→ Java 洗牌取 N → 按 id 回查"。几千条时差距明显
- **向量**：内存暴力余弦保持不变。几千条 × 1024 维几毫秒，到几万条再考虑 sqlite-vec

### 4.8 可观测性（本机自用也需要）

- **traceId**：一个 Servlet Filter 生成，放 MDC，响应头带 `X-Trace-Id`。日志格式带上它
- **分段耗时**：分析链路已有耗时统计，把它结构化——`fetch_ms` / `extract_ms` / `llm_ms` / `validate_ms` 单独打点。"这次为什么慢"是个人工具最常见的疑问
- **极简 `/api/health`**：自写不引 Actuator（避免暴露面）。返回 DB 可读、LLM 是否配置、向量服务是否配置、当前 `user_version`
- **成本台账**：`ai_log` 表已存在，补一个按周汇总，让 token 花费可见

---

## 5. 演进路线

每个阶段**独立可交付、独立可验证**，做完一个就能停。

| 阶段 | 主题 | 内容 | 验收口径 |
|---|---|---|---|
| **0** | 可测性 | Clock 注入 · 端口化 Llm/Embedding/Fetcher · 抽出 ReviewPolicy/SearchPolicy · 单测骨架 + ArchUnit | 领域层单测覆盖全部业务规则，`mvn test` 30s 内跑完且不联网不花 token |
| **1** | 韧性 | 超时预算 · 退避重试 · 熔断 · 并发闸门 · 降级口径 | 用 `FakeLlm` 注入连续失败，断言 3 秒内返回人话提示而非干等；并发 3 个分析，第 3 个拿到 429 |
| **2** | 可观测 | traceId · 分段耗时 · `/api/health` · 慢查询告警 | 任一次慢请求能从日志反查出耗时落在哪一段 |
| **3** | 数据演进 | Migrations（user_version）· 游标分页 · 计数接口 · FTS5 · 随机改造 | 老库启动自动升到目标版本；5000 条数据下列表 P95 < 200ms |
| **4** | 长任务（可选） | 分析任务落 `job` 表 + SSE/轮询 · 草稿持久化 | 分析途中刷新页面不丢进度 |
| **5** | 按需（不做） | 读写分离 / Postgres / 多用户 | —— |

**建议顺序的理由**：阶段 0 是其他一切的前提——没有测试，1/2/3 每一步都是在雷区走。而 0 本身**不改任何行为**，纯结构性调整，风险最低、收益最高。

---

## 6. 阶段 0 详细清单（可直接开工）

不新增功能、不改 API 契约、不改界面，只调结构。

**新增**
1. `domain/port/Clock.java`、`infrastructure/time/SystemClock.java`
2. `domain/port/LlmPort.java` / `EmbeddingPort.java` / `FetcherPort.java`
3. `domain/policy/ReviewPolicy.java`（含 `isDue` 判据，从 `REVIEW_WHERE` 平移）
4. `domain/policy/SearchPolicy.java`（阈值 + 兜底三条的决策）
5. `domain/support/Digests.java`（SHA-256 指纹，从 `SemanticSearchService.sha256` 平移）
6. `domain/vo/` 下 `Domain` / `Purpose` / `ContentType` 三个枚举从 `domain` 包搬入

**修改**
7. `LinkRepository` 去掉 `implements CorrectionsSource`；`correctionsBlock` 迁到 `application/CorrectionsFeed.java`
8. `REVIEW_WHERE` 改为参数化 `cutoff`；`dueForReview` / `countDueForReview` 共用 `ReviewPolicy`
9. `patch()` 入参 `Map<String,Object>` → `EditLinkCommand`（强类型 + 校验）
10. `SemanticSearchService` 用 `Clock` 与 `SearchPolicy`，`sha256`/`cosine` 迁到 `domain.support`
11. `DeepSeekClient` / `EmbeddingClient` 实现对应 port，行为逐字不变

**测试**
12. `UrlsTest`：归一化、dedupKey、monogram（边界：带 utm、大小写、尾斜杠、非 http）
13. `ReviewPolicyTest`：闲置天数边界、星标/已读/已用排除、`lastOpenedAt` 为空回退 `createdAt`
14. `ValidationServiceTest`：各类越界输入
15. `SearchPolicyTest`：阈值兜底、维度不符跳过
16. `WeeklyDigestFingerprintTest`：指纹随输入变化
17. ArchUnit 规则：`domain..` 不得依赖 `org.springframework..` / `java.sql..`

**验证**：改完后 `server/smoke.mjs` 必须原样通过（它是唯一的行为基准）。阶段 0 的正确定义就是——**行为零变化，结构可测了**。

---

## 7. 评审待定

- 韧性装饰器手写 vs 引 Resilience4j（方案建议手写，见 4.3）
- 用途过滤：JSON 保留 vs 加 `link_purpose` 关联表（方案建议关联表，见 4.6）
- 阶段 4 长任务化是否现在就做（本机自用、分析 4–30s，收益中等）

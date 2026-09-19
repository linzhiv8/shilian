# 拾链 · 后端

Spring Boot 3.5 + Java 21 + MySQL。单人自用。

## 跑起来

```bash
cd server
cp .env.properties.example .env.properties   # 填 DEEPSEEK_API_KEY 和 MySQL 连接
./mvn.sh spring-boot:run                     # 起在 127.0.0.1:8080
```

MySQL 那三项（`SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD`）不填就用默认值，
指向本机 3306 上的 `shilian` 库。**键名必须是大写+下划线的形式**——
`application.yml` 里写的是 `${SPRING_DATASOURCE_URL:...}`，
`spring.config.import` 把这个文件当普通 properties 读，两边同名才接得上；
写成 `spring.datasource.url` 是不生效的，而且不报错。

连不上时不用猜是哪一层：启动失败会打出一段「根因 + 是哪一层 + 怎么办」的提示
（`DatabaseUnreachableFailureAnalyzer`），里面有一张六项对照表
（域名 / 端口 / 防火墙 / 账号 / 库 / 认证插件）。JDBC 那几句报错
（端口没开 / 防火墙丢包 / host 没授权 / 库没建）在日志里长得几乎一样，
照着对一遍就知道断在哪一层。

**本地开发也需要一个能连的 MySQL**（本机装一个，或直接用服务器上那个）。
这个工程**只有 MySQL 一条路**：本地跑通就等于部署能跑，验的是同一条代码路径。

`mvn.sh` 是必需的包装脚本。Git Bash 下直接跑 `mvn` 会报
`ClassNotFoundException: org.codehaus.plexus.classworlds.launcher.Launcher`——
Maven 自带的 `mvn` 是 POSIX sh 脚本，在 Git Bash 里算不出自己的安装路径。
包装脚本把四个必需的 system property 显式写死，绕过它。

打 jar：

```bash
./mvn.sh clean package
java -jar target/shilian-server-0.1.0.jar
```

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/health` | 启动自检。`aiConfigured=false` 说明 Key 没配上 |
| `GET` | `/api/meta` | 领域/用途/内容形态的字典（中文名只有这一份） |
| `POST` | `/api/analyze` | `{url, text?}` → 抓取 + AI 分析，返回草稿与 `draftId`。**不落库**。带 `text` 时跳过抓取，直接用粘贴的正文 |
| `POST` | `/api/links` | `{draftId, ...用户确认后的字段}` → 入库，返回卡片 |
| `GET` | `/api/links` | 列表。`?domain=&purposes=a,b&q=&sort=recent\|starred\|stale` |
| `GET` | `/api/links/{id}` | 单条 |
| `PATCH` | `/api/links/{id}` | 局部更新。字段为 null 即不改 |
| `DELETE` | `/api/links/{id}` | 删除 |
| `GET` | `/api/review` | 「该回头看了」抽一批。`?days=7&limit=3` → `{items, dueTotal, days}` |
| `GET` | `/api/review/count` | 队列里一共多少条 → `{dueTotal, days}`。侧栏的数字用这个 |
| `GET` | `/api/review/weekly` | 周报。`?days=7&refresh=true`。摘要按周缓存，`refresh` 强制重写 |
| `POST` | `/api/links/{id}/reanalyze` | `{text?}` → 给**已存**的记录重跑分析，返回新草稿。**不落库**。带 `text` 用粘贴的正文，不带就重新抓一次。网址从记录里取，不接受前端传 |
| `POST` | `/api/links/{id}/apply` | 把上面那次重分析的结果写回该记录。请求体和 `POST /api/links` 完全一样 |

后两个是「给已存记录补正文」用的。它们和 `PATCH /api/links/{id}` 的分工：
`PATCH` 改的是**用户对这条记录的态度**（备注、分类、加星、已读），
`/apply` 换的是 **AI 对它的判断**（标题、摘要、正文快照、置信度）。
两件事的区别不只是「改哪些字段」——`PATCH` 会往 `correction` 表留一条
「用户想要什么」的证据，而 `/apply` 刻意不留（详见根 README）。

`days` 的语义是**「至少闲置多少天」**，不是「看最近多少天」——所以 `days` 越大命中越少。
两个参数都会两头夹住（`clamp(v, 1, 3650)` / `clamp(limit, 1, 20)`）。

### 错误码

所有失败都从同一个出口返回 `{"error": "一句人话"}`。约定的分界是
**「谁的错」**，因为这决定了下一步该改什么：

| 码 | 含义 | 常见原因 |
|---|---|---|
| `400` | 请求本身有问题 | 网址非法、参数类型不对（会点出参数名） |
| `404` | 找不到 | 记录不存在、接口路径拼错（会回显路径） |
| `405` | 方法不对 | 对着只读接口发了 POST |
| `409` | 冲突 | 网址已收藏过，响应里带 `existingId` |
| `410` | 已过期 | 草稿超时（`draftId` 是内存里的 LRU，重启即失效） |
| `502` | 上游不听话 | DeepSeek 调用失败、重试后仍不合法。**不是我们的 bug** |
| `503` | 环境没就绪 | 没配 API Key |
| `500` | 真的是 bug | 上面都不匹配时。这条一旦出现就该去查日志 |

`404` / `405` / 参数类型错误这三类曾经都会掉进 `500`，让「前端拼错路径」
看起来像「后端崩了」。现在分开，并且不打堆栈到日志。

### 为什么分析和保存是两个接口

AI 的判断天然有偏差。直接入库等于把不确定性推给用户「以后自己发现并修正」；
而让他存之前扫一眼、改一个下拉框，成本几乎为零。
这两步之间用 `draftId` 串起来，草稿在服务端存一份（有界 LRU，最多 50 条）。

顺带解决了一个实际问题：正文快照可能有 8000 字，让前端原样回传既浪费带宽
又给了篡改的机会。现在前端只需要带 `draftId` 回来。

## 目录

```
src/main/java/com/shilian/
├── analyze/           分析管线
│   ├── FetchService      Jsoup 抓取，失败永不抛异常
│   ├── ExtractService    从 HTML 抽标题/描述/正文
│   ├── Prompts           提示词加载器（去 CRLF 头），分析和周报共用
│   ├── PromptService     拼分析用的用户消息
│   ├── DeepSeekClient    java.net.http + Jackson，JSON 模式
│   ├── ValidationService 校验模型输出（提示词是建议，这里才是保证）
│   ├── AnalyzeService    编排 + 校验不过自动重试
│   ├── WeeklyDigestService 周报：统计 + AI 摘要 + 按指纹作废的缓存
│   └── CorrectionsFeed  用户历史修正的提供方（拼成提示词里的一块）
├── domain/            枚举与数据载体
├── repo/              仓储（MyBatis-Plus）+ entity/ mapper/ handler/
├── util/              Urls、RelativeTime
└── web/               Controller、DraftStore、异常出口
```

### 没有任何自动化验证

以前有两个端到端脚本（一个走完整分析流程、一个起干净库打一遍接口），**都已删掉**
——它们的前提都是「有一个可以随便造的库」，换成 MySQL 之后不成立了。

单元测试（`src/test/`）也在 2026-09-18 上线前清理时删掉，之后没有恢复
（中间短暂立起过一套冒烟基座，当天按「工程保持干净」的要求又删了，`spring-boot-starter-test`
依赖也一并移除）。**所以现在仍然没有任何自动化验证**：
「SQL 到底能不能执行」「多用户隔离有没有漏」「迁移能不能重跑」「限流边界对不对」
这几类只能手工验，暂时没有补的计划。

## 几个关键决定

**数据访问走 MyBatis-Plus 3.5.17**（2026-09-18 从手写 JdbcTemplate 迁过来）。
`link` 表的每一条查询都必须带 `user_id` 过滤，所以那边刻意**手写 SQL**，
让过滤条件留在 `WHERE` 里肉眼可见；`app_user` 这种标准单主键表才用
`BaseMapper` / `LambdaQueryWrapper`。`Migrations`（建表脚本）和 `StartupSelfCheck`
仍用 JdbcTemplate——MyBatis-Plus 是 ORM，不管 DDL。

**HikariCP 连接池开 8。** 按「单人自用 + 偶尔几台设备」估的，够用且不会把
MySQL 的 `max_connections` 吃掉。`max-lifetime` 必须短于 MySQL 的 `wait_timeout`
（默认 28800 秒），否则连接会被服务器单方面掐掉而池子还以为它是好的——
表现为「跑了一整天之后第一个请求报 Communications link failure」，重启就好，所以特别难查。

换库之前这个数字是 1：SQLite 是单写入者模型，池子开大只会引入 `SQLITE_BUSY` 和锁竞争。
不管池子开多大，有一条纪律不变：**绝不在持有连接时去调 AI 或抓网页。**
一次 AI 调用几百毫秒，而事务开得越久，锁持有越久、回滚代价越大。

**抓取失败不中断管线。** SPA、需登录、Cloudflare 这三类站点抓不到正文是常态。
抓不到正文时 `<meta name="description">` 往往还在，而它通常足以判断这是什么站——
这就是提示词里「第二档置信度」（0.5–0.7，标记需复核）的来源。
存一张待补的占位卡，比什么都不存好。

**贴了正文就跳过抓取。** `/api/analyze` 的 `text` 参数是抓取失败时的补救入口。
既然用户会去贴，就说明抓取已经失败过；再等 20 秒去撞同一堵墙没有意义，
而他贴的内容比抓取结果更完整。所以这条路径**根本不调 `FetchService`**，
直接造一个 `FetchedPage`（`BodySource.PASTED`）。

`FetchedPage` 里带 `BodySource` 是必须的，因为两种情况下「缺什么」是相反的：
抓取失败缺正文（有标题和描述），贴正文缺元信息（有正文，没标题）。提示词要说的话
不一样，前端要显示的提示也不一样。`ok` 字段在这里的含义是「拿到了可用正文」，
不是「抓取成功了」——抓取这件事根本没发生。

最短长度 50 字是**效果限制不是技术限制**：二三十个字连一句话都没说完，
判出来的分类还不如「只剩域名」那一档准，却会让用户以为「补了正文就有好结果」。
这个数字前后端各存一份（`AnalyzeService.MIN_PASTED_CHARS` /
`SavePanel.tsx` 的 `MIN_PASTE_CHARS`），前端那一道只是为了让用户立刻看到反馈，
后端那一道不能省。

**校验不过会自动重试一次。** 提示词里已经写死了 note_options 的 15–35 字约束，
但模型遇到需要列举文件名这类内容时仍会写超。继续调提示词的边际收益很低，
而且每次调都可能把别的场景调坏。换成把**具体的失败原因**回灌给模型让它自己改，
一次就能压回区间内。上限 1 次——第一次改不好的，第三次大概率也改不好，
而每次重试都要重发完整上下文。

**系统提示词逐字节固定。** 它约 2500 token，占单次输入的一半。
DeepSeek 的上下文缓存按「前缀完全一致」命中，所以启动时读一次就固定住。
「用户以往修正」这类每次都变的内容必须放在**用户消息**里——
一旦混进系统提示词，前缀就变了，缓存全废，成本直接翻倍。

**`used`（已用）是状态不是用途。** 前端早期把它同时放在用途列表和状态字段里，
会出现「用途显示已用但状态还是未读」这种自相矛盾的数据。
服务端统一归位：`purposes` 里收到 `used` 就摘掉，改成 `status='used'`。

## 回顾队列：随机，而且计数必须和队列同源

**`ORDER BY RANDOM()` 是有意的。** 按时间排的队列如果最前面那几条恰好不感兴趣，
队列就冻住了——用户天天看到同样几条，很快连这个入口都不点了。
随机则每次重抽。代价是某条可能等得久一点，但池子只有几十条，它不会消失。
池子真到几千条时该换成「按 last_opened_at 分桶 + 桶内随机」，现在不需要。

**排除条件是「星标 / 已用 / 已读」。** 三个都排除，理由不同：
星标是「我很在意，不用你提醒」；已用是「用过了」；已读是「我看过了，别再推」。
`status='read'` 和 `last_opened_at` 是两件事——后者只是把计时器归零，
7 天后还会回来；前者是永久的「别再推给我」。少了前者，用户处理完一圈，
下周看到的是同一批东西。

**`REVIEW_WHERE` 抽成常量，队列和计数共用。** 侧栏那个「N 条超过 7 天没打开」
要是和点进去看到的条数对不上，用户会开始不信这个数字，进而不再点它——
而回顾正是这个产品的命门。所以单独开了 `/api/review/count`，它不查卡片内容，
只跑同一段 `WHERE` 的 `COUNT(*)`。改这里要两边一起改——队列和计数只要有一边漂了
（比如忘了排除 `read`），界面上的数字就会和实际条数对不上。

## 周报：缓存必须能被数据作废

摘要按 ISO 周缓存（`2026-W38`），不然每打开一次就花一次 token。统计则不缓存——
几条 COUNT，毫秒级，每次重算反而不会过期。

**但光有周键不够。** 这里踩过一个真 bug：摘要写「这周存了 3 条」，
下面的统计却写着 6。原因是窗口是**滑动的最近 7 天**，不是固定的一段历史——
用户这周每存一条，输入就变了，而摘要只按周缓存，于是拿旧摘要配新数字。

现在缓存里连同一份**数据指纹**一起存：对喂给模型的那段用户消息取 SHA-256 前 8 字节
（`WeeklyDigestService.fingerprint`）。读缓存时对一下指纹，对不上就重写。
拿完整的模型输入做哈希，语义正好对上「输入相同才敢复用」——
用户改了一条备注、把某条标成已用，输入变了，摘要也该跟着变。

指纹塞在缓存 JSON 的 `_fp` 字段里，而不是单开一列。理由：`schema.sql` 是
`CREATE TABLE IF NOT EXISTS`，加不了列，而这个库已经跑起来了。塞进 JSON 是自洽的——
读的时候多取一个字段，渲染只认 `headline`/`body`/`observation`。
（升级后第一周的那份旧缓存没有 `_fp`，会被当成未命中重写一次，这是对的。）

**AI 挂了不会让整页挂掉。** 统计才是主体，摘要只是加值，所以调用失败时降级返回
统计 + 一句说明，而不是抛 502。空周也走本地生成，不花调用。

**ISO 周键而不是「日期区间」。** 跨年那几天的周号归属很容易算错
（2027-01-01 可能属于 2026 的第 53 周），交给 `WeekFields.ISO` 处理。

## 提示词只有一份

在 `tools/prompt-lab/system-prompt.md`，构建时复制进 jar 的 `prompts/`。
实验室里调好什么，线上就跑什么。改提示词改那个文件，重新构建即生效。

## 还没做 / 已知欠账

- **没有任何自动化测试。** `src/test/` 已经删掉，所以「SQL 到底能不能执行」
  「多用户隔离有没有漏」「迁移能不能重跑」「限流边界对不对」这几类只能靠手工验。
- **全文检索用的是 `LIKE`**，中文长文本上到几千条后应该换 MySQL 的
  FULLTEXT + ngram 分词器。
- **回顾队列的池子到几千条时**，`ORDER BY RAND()` 会给每行算一个随机数、等于全表扫描。
  到时换成「按 `last_opened_at` 分桶 + 桶内随机」。
- **`ai_log` 只记「完成的保存/应用」**，不记 `/api/analyze` 和 `/reanalyze` 本身。
  这是有意的（那张表的 `link_id` 在保存前还不存在），但代价是
  「反复重分析最后没保存」的消耗看不见。

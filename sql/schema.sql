-- 拾链 · MySQL 表结构（baseline）
--
-- 这份 DDL 既是「新库的初始化」，也是迁移列表里的 V1。
--
-- ⚠ 建库由人来做，不在这里 —— 见 DEPLOY.md。库必须建成 utf8mb4，
--   否则中文存进去会变成 ???，而且不报错。
--
-- 每张表都显式写了 ENGINE=InnoDB 和字符集，别删：外键**只在 InnoDB 上生效**，
-- 而 MySQL 对非 InnoDB 表会**静默忽略** FOREIGN KEY（不报错、不警告），
-- 所以不能依赖服务器的 default_storage_engine。
--
--
-- 〇、⚠ 怎么执行这个文件（这个坑踩过）
--
-- 它是**多条语句**（7 个 CREATE TABLE）。所以：
--
--   · **应用启动时会自动执行它**（Migrations → Spring 的 ScriptUtils，会正确拆分）。
--     也就是说你不需要手工跑它：建好库和账号就够了，表由应用建。
--
--   · 手工跑的话，要用客户端的**「执行 SQL 脚本」**模式，不要「执行语句」。
--     在 DBeaver / Navicat / IDEA 里「全选 + 执行单条语句」会把整个文件当成
--     一条发给 MySQL，于是报（下面这段是当时的报错原文）：
--
--       ERROR 1064 (42000): ... right syntax to use near
--       'CREATE TABLE IF NOT EXISTS app_user (' at line 75
--
--     这个报错的位置是**第 2 条语句的开头**，很容易被误读成「某一行的 SQL 写错了」。
--     其实那一行没问题——是第 1 条语句已经正常结束，而服务器不接受后面还有内容。
--
--     判断依据（比记行号可靠，因为行号会随文件增删而变）：报错位置正好落在
--     「上一条语句的 `);`」和「下一条的 CREATE」之间的接缝处。SQL 真写错了的话，
--     报错位置会在**语句内部**（比如某个列定义上），而不是两条语句的接缝处。
--
--   · 命令行也行（重定向，让 mysql 自己按脚本解析）：
--       mysql -h HOST -u USER -p DBNAME < sql/schema.sql
--
--
-- 一、为什么从 SQLite 的两份脚本合并成了一份
--
-- 原来是 schema.sql（V1）+ db/V2__multi_tenant.sql（V2）。换到 MySQL 时合并：
-- MySQL 这边是全新的库，不存在「V1 时代的旧库要升到 V2」这回事。
-- 留着两步只会把 V2 里那套「建新表 → 搬数据 → 删旧表 → 改名」的 SQLite 绕路
-- 原样搬过来 —— 而那套绕路的唯一理由（SQLite 不能 ALTER 唯一索引）在 MySQL 里
-- 根本不成立，ALTER TABLE 直接改索引就行。
--
--
-- 二、⚠ 迁移脚本必须幂等，这是 MySQL 逼出来的纪律
--
-- SQLite 的 DDL 能进事务，出错整体回滚，所以「跑一半失败」不会留下痕迹。
-- MySQL 不能：每条 DDL 都会隐式提交（提交完再报错也没法撤销）。
-- 于是「迁移跑到一半失败」会留下半截结构，下次启动重试时撞上「已经存在」——
-- 而版本号没推进，重试是必然发生的。
--
-- 所以这份脚本里每一条都必须是可重复执行的：
--   · 表一律 CREATE TABLE IF NOT EXISTS
--   · 索引一律写在 CREATE TABLE 里面，不写单独的 CREATE INDEX
--
-- 第二条是刻意的，也是这份文件和一般 MySQL DDL 最不一样的地方：
-- MySQL 不支持 CREATE INDEX IF NOT EXISTS，单独的建索引语句重试时必然报
-- 「Duplicate key name」。写在建表里，它就跟表同生共死，天然幂等。
--
--
-- 三、为什么时间列是 VARCHAR(19) 而不是 DATETIME
--
-- 存的是 'yyyy-MM-ddTHH:mm:ss'（见 RelativeTime.STORE）。
--
--   · 定长，所以字典序等于时间序，可以直接 SQL 排序和比较。
--     回顾队列和周报的判据就是这么写的：「闲置 N 天」翻译成一个时间点，
--     然后 WHERE COALESCE(last_opened_at, created_at) <= :cutoff。
--   · 顺带绕开了时区。存进去的是应用自己算好的本地时间字符串，
--     MySQL 不去解释它，也就不会出现「服务器时区 vs JVM 时区」这种静默偏差。
--     换成 DATETIME 就得回答「这个时间算哪个时区」，而这个问题的答案
--     在容器里通常是错的（容器默认 UTC），表现为「今天」的边界差 8 小时。
--   · 代价是用不了 MySQL 的日期函数。但代码里本来就没用——
--     判据在 ReviewPolicy 里，是纯 Java 纯函数，可以单测。
--
--
-- 四、为什么索引键长卡在 VARCHAR(700)
--
-- InnoDB 的索引键长上限是 3072 字节，utf8mb4 每字符 4 字节。
-- uk_link_user_url 是 (user_id, url_normalized)，所以要
--   (32 + 700) × 4 = 2928 ≤ 3072
-- 700 是这么来的，不是随便写的。改大这个数字之前先重算一遍，
-- 否则 CREATE TABLE 会直接报「Specified key was too long」。
-- （实测库里最长的 url_normalized 是 102 字符，700 有足够余量。）

/* ────────────── 迁移记录 ────────────── */

-- 取代 SQLite 的 PRAGMA user_version。那个是随库走的整数，MySQL 没有对应物，
-- 所以自己建一张表。它不放进 MIGRATIONS 列表 —— 它是迁移机制本身的一部分，
-- 由 Migrations.migrate() 在执行任何迁移之前建出来。
CREATE TABLE IF NOT EXISTS schema_version (
  version    INT         NOT NULL,
  name       VARCHAR(64) NOT NULL,
  applied_at VARCHAR(19) NOT NULL,
  PRIMARY KEY (version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

/* ────────────── 用户 ────────────── */

-- 表名不叫 user：user 是 SQL 关键字，名字上避开比在每个查询里加引号可靠。
CREATE TABLE IF NOT EXISTS app_user (
  id              VARCHAR(32)  NOT NULL,
  username        VARCHAR(190) NOT NULL,
  email           VARCHAR(190) NULL,
  -- bcrypt 结果是固定 60 字符，但别卡死：将来换 Argon2 长度会变
  password_hash   VARCHAR(255) NULL,
  nickname        VARCHAR(64)  NULL,
  status          VARCHAR(16)  NOT NULL DEFAULT 'active',
  -- 登录失败计数与锁定时点。放库里而不是内存：
  -- 内存方案一重启就清零，等于给攻击者一个「重启即解锁」的口子。
  failed_attempts INT          NOT NULL DEFAULT 0,
  locked_until    VARCHAR(19)  NULL,
  created_at      VARCHAR(19)  NOT NULL,
  last_login_at   VARCHAR(19)  NULL,
  PRIMARY KEY (id),
  -- 邮箱是选填的。MySQL 的 UNIQUE 允许多个 NULL，所以「没填邮箱」不会互相占用；
  -- 但空字符串 '' 会 —— 代码里存的是 null，这点别改。
  UNIQUE KEY uk_app_user_username (username),
  UNIQUE KEY uk_app_user_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

/* ────────────── 链接 ────────────── */

CREATE TABLE IF NOT EXISTS link (
  id                  VARCHAR(32)  NOT NULL,
  url                 TEXT         NOT NULL,
  -- 去重键。COLLATE utf8mb4_bin 是必须的：MySQL 默认的排序规则不区分大小写，
  -- 而 URL 的路径区分 —— /Path 和 /path 是两个页面。用默认排序规则的话
  -- 第二个会被判成重复而存不进去，属于凭空报错。
  url_normalized      VARCHAR(700) COLLATE utf8mb4_bin NOT NULL,
  user_id             VARCHAR(32)  NULL,
  domain              VARCHAR(255) NULL,   -- 主机名（去 www），不是分类。分类在 domain_category
  site_name           VARCHAR(255) NULL,   -- og:site_name，站点自称的名字
  -- 下面这些是 AI 生成的自由文本，一律 TEXT。
  -- 不用 VARCHAR(n)：写长了会直接插入失败（严格模式下报 Data too long），
  -- 而 AI 输出多长不由我们控制 —— 为省几字节把「保存」弄失败不划算。
  title               TEXT         NULL,
  summary_short       TEXT         NULL,
  summary_long        TEXT         NULL,
  note                TEXT         NULL,
  note_options        TEXT         NULL,   -- JSON 数组，AI 给的三句候选
  domain_category     VARCHAR(32)  NULL,   -- 领域，单选
  purpose_categories  TEXT         NULL,   -- JSON 数组，用途，多选
  tags                TEXT         NULL,   -- JSON 数组
  content_type        VARCHAR(32)  NULL,
  confidence          DOUBLE       NULL,
  needs_review        TINYINT      NOT NULL DEFAULT 0,
  is_private          TINYINT      NOT NULL DEFAULT 0,  -- 1 = 跳过 AI，仅本地保存
  status              VARCHAR(16)  NOT NULL DEFAULT 'unread',
  starred             TINYINT      NOT NULL DEFAULT 0,
  snapshot_text       MEDIUMTEXT   NULL,   -- 正文快照，防死链
  content_hash        VARCHAR(64)  NULL,   -- 内容指纹，没变就不重新分析
  ai_raw              MEDIUMTEXT   NULL,   -- AI 原始输出的完整 JSON，用于精确计算用户修正
  analyze_status      VARCHAR(16)  NOT NULL DEFAULT 'done',
  ai_attempts         INT          NULL,
  prompt_tokens       INT          NULL,
  completion_tokens   INT          NULL,
  created_at          VARCHAR(19)  NOT NULL,
  updated_at          VARCHAR(19)  NULL,
  last_opened_at      VARCHAR(19)  NULL,
  PRIMARY KEY (id),

  -- 同一用户下的网址去重，而不是全库去重。
  -- 这一条就是 V2 迁移当年存在的全部理由：原来 url_normalized 是全局唯一，
  -- A 存过的网址 B 就存不进去了。在 MySQL 里改成复合索引只是一句话的事。
  UNIQUE KEY uk_link_user_url (user_id, url_normalized),
  KEY idx_link_user_created (user_id, created_at),
  KEY idx_link_user_domain (user_id, domain_category),
  KEY idx_link_review (needs_review)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

/* ────────────── AI 调用日志 ────────────── */

-- 用于成本可见与排查。
CREATE TABLE IF NOT EXISTS ai_log (
  id                BIGINT      NOT NULL AUTO_INCREMENT,
  link_id           VARCHAR(32) NULL,
  user_id           VARCHAR(32) NULL,
  model             VARCHAR(64) NULL,
  attempt           INT         NULL,
  prompt_tokens     INT         NULL,
  completion_tokens INT         NULL,
  ok                TINYINT     NULL,
  errors            TEXT        NULL,
  created_at        VARCHAR(19) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_ai_log_user (user_id, created_at),
  KEY idx_ai_log_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

/* ────────────── 用户修正记录 ────────────── */

-- AI 判断 → 用户改成什么。后续作为 few-shot 让 AI 往用户偏好靠。
--
-- 刻意不加外键级联：链接被删掉时，这里的记录保留。
-- 理由：这张表存的是「用户表达过的偏好」，不是「某条链接的属性」。
-- 用户删掉一条链接，不代表他当时那次判断偏好作废——恰恰相反，
-- 他之所以会去改，往往正是因为他清楚自己要什么。
-- 汇总查询只按 field / ai_value / user_value 分组，不依赖链接是否还在。
CREATE TABLE IF NOT EXISTS correction (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  link_id    VARCHAR(32) NULL,
  user_id    VARCHAR(32) NULL,
  -- 不叫 field：那是 SQL 函数名（FIELD()），也容易和 Java 关键字撞。
  field_name VARCHAR(32) NULL,
  ai_value   TEXT        NULL,
  user_value TEXT        NULL,
  created_at VARCHAR(19) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_correction_user (user_id, field_name),
  KEY idx_correction_field (field_name, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

/* ────────────── 周报 ────────────── */

-- 只缓存 AI 写的那段摘要，不缓存统计数字。
--
-- 理由：统计是几条 COUNT 查询，毫秒级，随时重算反而不会过期；
-- 而 AI 摘要每次都要花 token 和一两秒，值得缓存。
--
-- 按「ISO 周」+ 用户做主键。光有周键不够：窗口是滑动的最近 7 天，
-- 用户这周每存一条，输入就变了。所以缓存内容里另存一份「数据指纹」
-- （见 WeeklyDigestService.fingerprint），读缓存时对一下，对不上就重写。
--
-- ⚠ user_id 的 DEFAULT '' 不是随手写的：主键列在 MySQL 里不能为 NULL，
-- 而「还没有归属」这件事必须有个表示。空字符串就是那个占位符，
-- 对应 claimOrphans() 里 WHERE user_id = '' 那一句（别的表用的是 IS NULL，
-- 差别只在这里）。真实用户 id 是 12 位十六进制，永远不可能是空串，不会撞。
CREATE TABLE IF NOT EXISTS weekly_digest (
  user_id           VARCHAR(32)  NOT NULL DEFAULT '',
  week_key          VARCHAR(16)  NOT NULL,   -- 例如 2026-W38
  summary           MEDIUMTEXT   NOT NULL,   -- AI 写的摘要，JSON，含 _fp 数据指纹
  model             VARCHAR(64)  NULL,
  prompt_tokens     INT          NULL,
  completion_tokens INT          NULL,
  created_at        VARCHAR(19)  NOT NULL,
  PRIMARY KEY (user_id, week_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

/* ────────────── 向量缓存 ────────────── */

-- 为什么不每次搜索现算：一条记录的文本没变，向量就不会变，重算是纯粹的浪费。
-- text_hash 存「拼好的可检索文本」的 SHA-256，读的时候对一下，对不上才重算。
-- 拼装规则见 SemanticSearchService.searchTextOf —— 改了拼法必须让 hash 跟着变，
-- 否则老向量会被当成有效的继续用。
--
-- model 和 dim 必须存。换一个 embedding 模型（或同一模型的另一个维度）之后，
-- 新旧向量不在同一个空间里，算余弦得到的是垃圾 —— 而且不会报错，
-- 只会让搜索结果悄悄变差，极难往回追。存下来才能在换模型时识别出「这些得重算」。
--
-- 向量存 float32 小端二进制，长度 = dim × 4 字节。
-- 故意不引向量扩展：个人库的量级（几千条 × 1024 维 ≈ 十几 MB）
-- 在内存里暴力算余弦只要几毫秒，省掉一个原生扩展的编译与分发麻烦。
--
-- 这里加外键级联（和 correction 表刻意相反）：向量是链接的派生属性，
-- 不是用户表达过的偏好。链接没了，向量就该跟着没，否则会攒一堆孤儿行，
-- 而且下次搜索还会把它们算进去。用级联而不是在删除逻辑里手写一句，
-- 是因为手写的那句迟早会有人忘。
CREATE TABLE IF NOT EXISTS link_embedding (
  link_id    VARCHAR(32) NOT NULL,
  model      VARCHAR(64) NOT NULL,
  dim        INT         NOT NULL,
  vec        MEDIUMBLOB  NOT NULL,
  text_hash  VARCHAR(64) NOT NULL,
  updated_at VARCHAR(19) NOT NULL,
  PRIMARY KEY (link_id),
  KEY idx_link_embedding_model (model),
  CONSTRAINT fk_link_embedding_link
    FOREIGN KEY (link_id) REFERENCES link (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

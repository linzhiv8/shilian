-- 拾链 · V3 email：邮箱验证标记（app_user.email_verified）+ 一次性令牌（email_token）
--
-- 这份脚本是给**已经跑过 V2 的库**升级用的，所以它只由幂等的语句组成。
-- 全新库不要用它建表：那份是 db/V1__baseline.sql（应用启动时自动执行）
-- 和 sql/schema.sql（人工建库时执行）——两者都已经包含了这里的最终结构，
-- 而它们建出来的表会让本脚本的每一条都自然退化成空操作。
--
--
-- 〇、为什么它必须每一条都能重复执行
--
-- MySQL 的 DDL 会**隐式提交**、不能回滚。于是「迁移跑到一半失败」会留下半截结构，
-- 而版本号没推进、下次启动必然重试。所以每条语句都要满足「跑第二遍不报错、且结果不变」：
--   · 新表用 CREATE TABLE IF NOT EXISTS
--   · 加列只能先查 information_schema 再动态拼 DDL（MySQL 8.0 没有 ADD COLUMN IF NOT EXISTS）
--   · 索引一律写在 CREATE TABLE 里面（MySQL 也没有 CREATE INDEX IF NOT EXISTS）
--
--
-- 一、app_user.email_verified
--
-- 0 = 没验证过，1 = 验证过。刻意用 TINYINT 而不是 BIT：
-- BIT 在部分驱动和客户端里显示成一坨二进制，排查时还得转换一次，
-- 而这一列的取值永远只有两个，用 TINYINT 省事且可读。
--
-- **验证不是强制的**：没验证也能正常用拾链。它只卡一件事——
-- 没验证的邮箱不能走「忘记密码」自助重置。理由是那封重置邮件发到一个
-- 我们没确认过的地址上，等于把账号的控制权交给任何一个填了这个邮箱的人。
--
-- ⚠ 存量账号一律是 0（未验证），不是 1。
--   反过来填会让所有老账号「假装已验证」，把上面那条保护直接架空。

SET @has_verified := (SELECT COUNT(*) FROM information_schema.COLUMNS
                      WHERE TABLE_SCHEMA = DATABASE()
                        AND TABLE_NAME = 'app_user'
                        AND COLUMN_NAME = 'email_verified');
SET @ddl := IF(@has_verified = 0,
  'ALTER TABLE app_user ADD COLUMN email_verified TINYINT NOT NULL DEFAULT 0 AFTER email',
  'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

/* ────────────── 一次性令牌 ────────────── */

-- 「找回密码」和「验证邮箱」共用的令牌表。
--
-- ⚠ token_hash 存的是 **SHA-256 哈希**，不是令牌本身。
-- 这条是这个表最重要的一个决定：
--   令牌出现在两个地方——邮件正文（会转发、会留在收件箱里）和数据库。
--   数据库泄露（备份外流、SQL 注入）如果等于「能重置任何人的密码」，
--   那一次泄露就是一次全站接管。存哈希之后，光拿到库是没法造出可用链接的。
-- 代价是没法「把链接再发一遍」——只能重新生成一个，这完全可以接受。
--
-- 三个时间列都是 VARCHAR(19)，和工程其余表一致（见 LinkEntity 的注释）。
CREATE TABLE IF NOT EXISTS email_token (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  user_id     VARCHAR(32)  NOT NULL,
  email       VARCHAR(190) NOT NULL,  -- 快照：发信时那个地址，不是用户现在的地址
  purpose     VARCHAR(16)  NOT NULL,  -- 'reset' | 'verify'
  token_hash  VARCHAR(64)  NOT NULL,  -- SHA-256 十六进制，见上面
  expires_at  VARCHAR(19)  NOT NULL,
  used_at     VARCHAR(19)  NULL,      -- 非 null 表示已用过，用后即废
  created_at  VARCHAR(19)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_email_token_hash (token_hash),
  KEY idx_email_token_user (user_id, purpose),
  KEY idx_email_token_exp (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

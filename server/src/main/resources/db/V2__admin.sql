-- 拾链 · V2 admin：角色（app_user.role）+ 审计日志（audit_log）
--
-- 这份脚本是给**已经跑过 V1 的库**升级用的，所以它只由幂等的语句组成。
-- 全新库不要用它建表：那份是 db/V1__baseline.sql（应用启动时自动执行）
-- 和 sql/schema.sql（人工建库时执行）——两者都已经包含了这里的最终结构，
-- 而它们建出来的表会让本脚本的每一条都自然退化成空操作。
--
--
-- 〇、为什么它必须每一条都能重复执行
--
-- MySQL 的 DDL 会**隐式提交**、不能回滚（SQLite 能，那是上一代的事）。
-- 于是「迁移跑到一半失败」会留下半截结构，而版本号没推进、下次启动必然重试。
-- 所以每条语句都要满足「跑第二遍不报错、且结果不变」：
--   · 新表用 CREATE TABLE IF NOT EXISTS
--   · 加列只能先查 information_schema 再动态拼 DDL（MySQL 8.0 没有 ADD COLUMN IF NOT EXISTS）
--   · 索引一律写在 CREATE TABLE 里面（MySQL 也没有 CREATE INDEX IF NOT EXISTS）
--
--
-- 一、app_user.role
--
-- 取值只有 'user' / 'admin' 两个，由代码侧 User.ROLE_USER / ROLE_ADMIN 定义。
-- 列宽 16 而不是 8：将来要加 'auditor' 这类只读角色时不用再改一次表。
--
-- ⚠ 这里只建列，**不指定谁是管理员**。第一个管理员由人用一条显式 UPDATE 设定
--   （UPDATE app_user SET role = 'admin' WHERE username = 'chuange';），
--   刻意不做「第一个注册的人自动成为管理员」这种隐式规则——
--   那条规则在「第一个注册的人是别人」时会静默地把管理员交出去。

-- MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS，所以先查一次 information_schema，
-- 再用 PREPARE 拼出真正的 DDL；列已经存在时执行 DO 0（一个合法的空操作）。
-- DO 0 而不是 SELECT 1：后者会产生一个结果集，迁移执行器只关心有没有报错，
-- 多出来的结果集没有任何用处。
SET @has_role := (SELECT COUNT(*) FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE()
                    AND TABLE_NAME = 'app_user'
                    AND COLUMN_NAME = 'role');
SET @ddl := IF(@has_role = 0,
  'ALTER TABLE app_user ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT ''user'' AFTER status',
  'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 存量账号不需要改数据：ADD COLUMN 带 DEFAULT 时，MySQL 会把默认值写进已有行。
-- 也就是说升级上来的每个账号都是 'user'，没有人是管理员，直到上面那条 UPDATE 被执行。

/* ────────────── 审计日志 ────────────── */

-- 「谁在什么时候做了什么」。管理端按用户 / 动作查它。
--
-- 刻意**不加外键**：审计记录的生命期不该挂在 app_user 上。
-- 用户被删掉（将来可能有这个操作）之后，他做过的事仍然要能查到——
-- 那正是审计存在的理由。所以 username 在这里冗余存一份快照：
-- 只存 user_id 的话，用户没了之后这条记录就只剩一个看不懂的 id。
--
-- 同理，detail 存的是当时的文字说明而不是外键引用，
-- 免得「删链接」这种动作在链接删除之后变成一条指向空处的记录。
CREATE TABLE IF NOT EXISTS audit_log (
  id         BIGINT       NOT NULL AUTO_INCREMENT,
  user_id    VARCHAR(32)  NULL,     -- 登录失败且查无此人时为 NULL
  username   VARCHAR(190) NULL,     -- 冗余快照，见上面的理由
  action     VARCHAR(32)  NOT NULL, -- 取值见 AuditService 的七个常量
  target     VARCHAR(190) NULL,     -- 被操作的对象（链接 id / 被禁用的账号 id）
  detail     TEXT         NULL,     -- 补充说明（例如失败原因）
  result     VARCHAR(16)  NULL,     -- success | failure | denied
  ip         VARCHAR(64)  NULL,     -- 来源 IP，取法见 ClientIp
  created_at VARCHAR(19)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_audit_user (user_id, created_at),
  KEY idx_audit_action (action, created_at),
  KEY idx_audit_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

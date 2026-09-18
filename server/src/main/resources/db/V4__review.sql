-- 拾链 · V4 review：把「已用」从 status 里拆出来
--
-- 这份脚本是给**已经跑过 V3 的库**升级用的，所以它只由幂等的语句组成。
-- 全新库不要用它建表：那份是 db/V1__baseline.sql（应用启动时自动执行）
-- 和 sql/schema.sql（人工建库时执行）——两者都已经包含了这里的最终结构。
--
--
-- 〇、为什么必须拆
--
-- 原来的 link.status 一个列装了三种意思：
--   unread  没看过
--   read    看过了，别再推给我
--   used    用上了
--
-- 前两个是「要不要再出现」，第三个是「我用没用上」——**两件不相干的事**。
-- 挤在一个列里的直接后果是：「已用」成了单向门。
-- 想取消已用时，不知道该把 status 改回 unread 还是 read：
--   · 一律回 unread：一条他早就标过「看过了、别再推」的记录会重新冒回顾队列
--   · 保留 read：一条从没看过的记录被永久排除，再也不会出现
-- 两种都不对，而库里已经丢掉了「原来到底是哪个」这个信息。
--
-- 拆开之后：status 只管「看过了没有」，used 是独立的一个开关，可以来回拨。
--
--
-- 一、加 used 列
--
-- 取值 0 / 1。默认 0，所以存量记录全是「没用过」。

SET @has_used := (SELECT COUNT(*) FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE()
                    AND TABLE_NAME = 'link'
                    AND COLUMN_NAME = 'used');
SET @ddl := IF(@has_used = 0,
  'ALTER TABLE link ADD COLUMN used TINYINT NOT NULL DEFAULT 0 AFTER status',
  'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 二、搬数据：原来标了 used 的，拆成「没看过 + 用过」
--
-- status 给 'unread' 而不是 'read'，这是这一步唯一容易写反的地方：
--
--   · 给 'read'：这批记录靠 status 被排除在回顾队列外。看起来对，
--     但用户一旦取消「已用」，status 还是 'read'，它照样不进队列 ——
--     「取消已用」等于什么都没发生，这次拆分就白拆了。
--
--   · 给 'unread'：排除队列改由 used = 1 负责（Java 侧的 LinkMapper.REVIEW_WHERE
--     和 ReviewPolicy 同步改，见那两处），
--     当前行为和拆分前完全一致（还是不进队列），
--     而取消已用之后它自然回到队列 —— 这才是这次要的效果。
--
-- 换句话说：「别再推给我」这个表态用户从没做过，他当时标的是「我用上了」。
-- 不能替他补一个他没点过的事。
--
-- 幂等：跑第二遍时这些行的 status 已经是 'unread'，条件不再命中。
UPDATE link SET used = 1, status = 'unread' WHERE status = 'used';

/* ────────────── 清理死列 ────────────── */

-- content_hash 是设计阶段留下的，打算做「内容没变就不重新分析」，这个功能没做。
--
-- 它是**真的死列**：全工程没有任何一处写它，所以每一行的值都是 NULL。
-- 留着它的代价不是空间，是误导：下一个读这张表的人会以为「内容指纹」是算过的，
-- 于是写一段依赖它的代码，拿到的一律是 NULL。
-- 一个永远为 NULL 的列比没有这一列更糟——没有的话人会去找别的办法，
-- 有的话人会先试一遍错的。
--
-- MySQL 没有 DROP COLUMN IF EXISTS，所以还是查 information_schema 再动态拼。
SET @has_ch := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'link'
                  AND COLUMN_NAME = 'content_hash');
SET @ddl := IF(@has_ch > 0, 'ALTER TABLE link DROP COLUMN content_hash', 'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

--
-- ★ 这里原本还要删 is_private，后来撤销了。原因值得记下来：
--
--   一开始判断它是死列（「没有代码读写」），这是错的——
--   LinkRepository.insertQuick() 会写 is_private = 1，
--   那条路径是活的（POST /api/links/quick，前端的「跳过 AI 直接存」在用）。
--   它和 content_hash 的区别正在这里：content_hash 一行都没被写过（恒为 NULL），
--   而 is_private 有真实取值，记的是「这条记录是怎么来的」。
--
--   虽然今天也没有任何查询读它，但删列不可逆，
--   而「跳过 AI 存下来的那批是哪些」这个信息一旦丢了就补不回来
--   （analyze_status='pending' 看起来能替代，但重分析失败也会回到 pending，
--     两者不是一回事）。拿不准的时候别删，留着最多是冗余。

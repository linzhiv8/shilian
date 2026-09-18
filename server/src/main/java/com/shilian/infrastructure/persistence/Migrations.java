package com.shilian.infrastructure.persistence;

import com.shilian.util.RelativeTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

/**
 * schema 版本迁移。
 *
 * <p><b>为什么不上 Flyway。</b>
 * 当初不用的理由是「Flyway 社区版不官方支持 SQLite」，换到 MySQL 之后这个理由
 * 已经消失了——Flyway 支持 MySQL。现在继续用手写的，是因为它只有一百行、
 * 当前只有一条迁移、行为完全可控；换过去要引入一套新约定和一份历史表，
 * 而收益（checksum、repair、baseline）在只有一条迁移时体现不出来。
 * 等迁移多起来、或者需要「校验脚本有没有被改过」这类能力时，换它更划算。
 *
 * <p><b>版本号存在 {@code schema_version} 表里。</b>
 * SQLite 自带 {@code PRAGMA user_version}，一个随库走的整数，用不着自己建表。
 * MySQL 没有对应物，所以建一张 {@code schema_version}。它不属于 {@link #MIGRATIONS}
 * ——它是迁移机制本身的一部分，在执行任何迁移之前就要有。
 *
 * <p><b>⚠ MySQL 的 DDL 不能回滚，这是和 SQLite 最根本的差别。</b>
 * SQLite 的 DDL 能进事务，出错整体 rollback，「跑一半失败」不留痕迹。
 * MySQL 每条 DDL 都会<b>隐式提交</b>，提交完再报错也没法撤销。
 * 所以迁移失败时会留下半截结构，而版本号没推进、下次启动必然重试。
 *
 * <p>这直接决定了迁移脚本必须遵守的纪律：<b>每一条语句都要能重复执行</b>
 * （表用 {@code CREATE TABLE IF NOT EXISTS}，索引写进建表里）。
 * 详细理由写在 {@code db/V1__baseline.sql} 的注释里。
 * 这里是另一半：<b>不要再试图用事务去兜底</b>——那只会给人「已经安全了」的错觉。
 *
 * <p>失败重试的安全性靠两件事保证：脚本幂等 + 版本号在脚本成功之后才写。
 */
@Component
public class Migrations {

    private static final Logger log = LoggerFactory.getLogger(Migrations.class);

    /** 版本记录表。不放进 {@link #MIGRATIONS}，它是机制本身。 */
    static final String VERSION_TABLE = "schema_version";

    /**
     * @param script         classpath 下的 SQL 脚本路径
     * @param rebuildsTables 是否重建表。为真时会在执行前临时关掉外键约束。
     *                       当前唯一的迁移用不到它，留着是给以后那些
     *                       「建新表 → 搬数据 → 删旧表」的迁移用的：
     *                       删旧表会顺着 {@code ON DELETE CASCADE} 把子表数据一起带走，
     *                       而且不报错（{@code link_embedding} 就挂在 {@code link} 下面）。
     */
    public record Migration(int version, String name, String script, boolean rebuildsTables) {}

    /**
     * 迁移列表，按版本号升序。
     *
     * <p><b>为什么第二个迁移叫 V2，而需求文档里写的是 V4。</b>
     * 需求文档（{@code 拾链-迭代需求-2026-09-18.md}）按批次排号：批次 3 一个迁移、
     * 批次 4 一个迁移、批次 6 是 V4。<b>但批次 3、4 到现在都还没做</b>，
     * 生产库 {@code schema_version} 里只有 V1——本条是库里的<b>第二条</b>迁移，
     * 所以它是 V2。
     *
     * <p>版本号必须等于「库里实际的下一个版本」，不能按文档预留：
     * {@link #migrate()} 用 {@code m.version() <= current} 跳过旧迁移，
     * 写成 4 的话，等批次 3/4 真的做完、它们的脚本以 V3/V4 进来时，
     * 会因为「版本号已经推进到 4」被<b>静默跳过</b>——表结构静默地缺一块，
     * 而且没有任何报错。跳号本身无害，把号占掉才有害。
     *
     * <p>将来批次 3/4 落地时，它们依次补上 V3、V4，本条仍然留在 V2 不动。
     */
    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, "baseline", "db/V1__baseline.sql", false),
            new Migration(2, "admin", "db/V2__admin.sql", false)
    );

    private final JdbcTemplate jdbc;
    private final List<Migration> migrations;
    private final String versionTable;

    /**
     * {@code @Autowired} 不能省。
     *
     * <p>这个类现在有<b>两个</b>构造器，而 Spring 的规则是：
     * 只有一个构造器时自动用它（4.3 起不需要注解）；有多个而<b>一个都没标注</b>时，
     * 它会退回去找无参构造器——找不到就抛
     * {@code NoSuchMethodException: Migrations.<init>()}，报错信息里完全不提
     * 「你有两个构造器」这件事，很容易以为是包扫描或依赖注入坏了。
     */
    @Autowired
    public Migrations(JdbcTemplate jdbc) {
        this(jdbc, MIGRATIONS, VERSION_TABLE);
    }

    /** 给测试用：换一套迁移列表，好把「迁移失败」这条路真正走出来。 */
    Migrations(JdbcTemplate jdbc, List<Migration> migrations, String versionTable) {
        this.jdbc = jdbc;
        this.migrations = migrations;
        this.versionTable = versionTable;
    }

    @PostConstruct
    public void migrate() {
        ensureVersionTable();

        int current = currentVersion();
        int target = migrations.get(migrations.size() - 1).version();
        if (current >= target) {
            log.info("数据库 schema 已是最新（版本 {}）", current);
            return;
        }
        log.info("数据库 schema 版本 {} → 目标 {}", current, target);

        for (Migration m : migrations) {
            if (m.version() <= current) {
                continue;
            }
            apply(m);
        }
        log.info("schema 迁移完成，当前版本 {}", currentVersion());
    }

    /**
     * 执行一个迁移。
     *
     * <p><b>这里没有事务，是刻意的。</b>
     * SQLite 版本里有一整套「关外键 → 开事务 → 执行 → commit / rollback」的编排，
     * 因为那时候它真的有用。在 MySQL 上这套编排毫无作用：DDL 语句会隐式提交，
     * 事务边界对它不生效。留着一个不会生效的 rollback，比没有更坏——
     * 读代码的人会以为失败是安全的。
     *
     * <p>替代方案是幂等（脚本里每条语句都能重复执行），失败时抛异常、
     * 版本号不推进，下次启动原样重来一遍。
     */
    private void apply(Migration m) {
        log.info("应用迁移 V{} · {}", m.version(), m.name());
        try (Connection con = jdbc.getDataSource().getConnection()) {
            if (m.rebuildsTables()) {
                // 会话级的开关，不在事务里，所以 MySQL / SQLite 上都成立
                execute(con, "SET FOREIGN_KEY_CHECKS=0");
            }
            try {
                /*
                 * 用 ScriptUtils 而不是自己按分号切分：
                 * SQL 里的注释、字符串字面量都可能含分号，手写切分迟早切错。
                 */
                ScriptUtils.executeSqlScript(con, new ClassPathResource(m.script()));
            } finally {
                if (m.rebuildsTables()) {
                    execute(con, "SET FOREIGN_KEY_CHECKS=1");
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "schema 迁移 V" + m.version() + "（" + m.name() + "）失败："
                            + e.getMessage()
                            + "。注意 MySQL 的 DDL 不能回滚，库里可能留下半截结构；"
                            + "脚本是幂等的，修好原因后重启会自动重试。", e);
        }
        // 版本号在脚本成功之后才写。失败就停在原地，下次启动会重试。
        jdbc.update("INSERT INTO " + versionTable + " (version, name, applied_at) VALUES (?,?,?)",
                m.version(), m.name(), LocalDateTime.now().format(RelativeTime.STORE));
    }

    private void execute(Connection con, String sql) throws Exception {
        try (Statement s = con.createStatement()) {
            s.execute(sql);
        }
    }

    /**
     * 版本表要在读版本号之前就存在。
     *
     * <p>它的建表语句不能放进 {@code V1__baseline.sql} —— 迁移脚本只在
     * 「版本落后」时才跑，而版本号本身要从这张表里读，是个死循环。
     */
    private void ensureVersionTable() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + versionTable + " ("
                + "version    INT         NOT NULL, "
                + "name       VARCHAR(64) NOT NULL, "
                + "applied_at VARCHAR(19) NOT NULL, "
                + "PRIMARY KEY (version))");
    }

    private int currentVersion() {
        Integer v = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM " + versionTable, Integer.class);
        return v == null ? 0 : v;
    }

    /** 给测试用：迁移列表。 */
    List<Migration> migrations() {
        return migrations;
    }
}

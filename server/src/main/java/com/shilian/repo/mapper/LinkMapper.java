package com.shilian.repo.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shilian.repo.entity.LinkEntity;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * {@code link} 的 Mapper。
 *
 * <p><b>这个 Mapper 和 {@code AppUserMapper} 的风格刻意不同：这里几乎全部手写 SQL。</b>
 *
 * <p>原因只有一条，但它比「写起来省事」重要得多：
 * <b>每一条查询都必须带 {@code user_id} 过滤，这是数据隔离的唯一防线。</b>
 *
 * <p>用 {@code LambdaQueryWrapper} 的话，过滤条件会变成一句
 * {@code .eq(LinkEntity::getUserId, uid)} 混在若干条件里 —— 漏掉它不报错、
 * 不告警，表现是「A 能读到 B 的记录」，只有等用户真的看见别人的数据才会被发现。
 * 写在 {@code WHERE} 里则是肉眼可见的一行，code review 时扫一眼就能确认在不在。
 *
 * <p>（对照：{@code AppUserMapper} 里就放心用了 Wrapper，因为 {@code app_user}
 * 本身就是用户表，没有「按当前用户过滤」这回事。这不是风格不一致，
 * 是两张表的安全属性不同。）
 *
 * <p>两个例外——{@link #search} 和 {@link #patch} 用了 Wrapper，
 * 因为它们的条件是真正动态的。这两处的 {@code user_id} 仍然显式写在
 * wrapper 里，理由见各自的方法注释。
 */
@Mapper
public interface LinkMapper extends BaseMapper<LinkEntity> {

    /**
     * 回顾队列的筛选条件。
     *
     * <p><b>抽成常量是刻意的：取一批和数总数必须用同一个条件</b>，
     * 否则侧栏说「有 5 条」、点进去只有 3 条，用户会以为丢了数据。
     * 接口的字段是隐式 {@code static final} 的编译期常量，
     * 所以能直接拼进下面的 {@code @Select} 里。
     *
     * <p>时间用字符串比较而不是 {@code julianday()} / 日期函数：存储格式定长
     * （{@code yyyy-MM-dd'T'HH:mm:ss}，见 {@code RelativeTime.STORE}），
     * 字典序等于时间序。更关键的是，判据本身在 {@code ReviewPolicy} 里
     * 是个纯 Java 纯函数，SQL 只负责比大小 —— 不把「闲置 N 天」这条业务规则
     * 绑死在某个数据库的函数上。
     */
    String REVIEW_WHERE =
            "user_id = #{uid} AND starred = 0 AND status NOT IN ('used', 'read') "
                    + "AND COALESCE(last_opened_at, created_at) <= #{cutoff}";

    /* ────────────── 查询 ────────────── */

    /**
     * 按 id 查，带用户过滤。
     *
     * <p>过滤写在 {@code WHERE} 里而不是「先查出来再比对」：
     * 后者「忘了比对」的表现是 A 能读到 B 的记录 —— 不报错、不告警。
     * 写在 {@code WHERE} 里，结果就是「找不到」，和记录不存在完全一样，
     * 天然不泄露「这条记录存在，只是不属于你」。
     */
    @Select("SELECT * FROM link WHERE id = #{id} AND user_id = #{uid}")
    LinkEntity findByIdForUser(@Param("id") String id, @Param("uid") String uid);

    /**
     * 同一用户下是否存过这个网址。
     *
     * <p>带用户条件不只是为了隔离 —— 唯一索引本来就是
     * {@code (user_id, url_normalized)}，A 存过的网址 B 是可以再存一次的。
     * 不加这个条件，B 存一个 A 存过的网址会收到「重复」的提示，属于凭空报错。
     *
     * <p><b>{@code dedupKey} 必须是已经过 {@code fitToColumn} 截断的</b>，
     * 和写入时用同一套截断，否则会出现「明明存过却查不出重复」。
     */
    @Select("SELECT * FROM link WHERE url_normalized = #{key} AND user_id = #{uid}")
    LinkEntity findByDedupKey(@Param("key") String key, @Param("uid") String uid);

    /**
     * 列表查询。条件是真动态的，所以用 Wrapper。
     *
     * <p>{@code uid} 仍然显式写成第一个条件，而且它<b>不是可选的</b>——
     * 这个方法没有「查全部用户」的用法。
     *
     * @param likePattern 已经转义并包好 {@code %} 的 LIKE 模式，null 表示不按关键词筛。
     *                    转义放在仓储里做（见 {@code LinkRepository.escapeLike}），
     *                    因为 {@code ESCAPE '!'} 这个选择属于「怎么用 SQL」，
     *                    而转义符要跨 Java 字符串 → SQL 字面量 → LIKE 模式三层数反斜杠，
     *                    换个字符当转义符就不用数了。
     * @param orderBy     已经过白名单校验的排序子句。绝不让用户输入直接进来。
     */
    default List<LinkEntity> search(String uid, String domainCategory,
                                    String likePattern, String orderBy) {
        LambdaQueryWrapper<LinkEntity> w = new LambdaQueryWrapper<>();
        w.eq(LinkEntity::getUserId, uid);

        if (domainCategory != null && !domainCategory.isBlank()) {
            w.eq(LinkEntity::getDomainCategory, domainCategory);
        }
        if (likePattern != null) {
            w.and(q -> q
                    .apply("title LIKE {0} ESCAPE '!'", likePattern)
                    .or().apply("summary_short LIKE {0} ESCAPE '!'", likePattern)
                    .or().apply("summary_long LIKE {0} ESCAPE '!'", likePattern)
                    .or().apply("note LIKE {0} ESCAPE '!'", likePattern)
                    .or().apply("tags LIKE {0} ESCAPE '!'", likePattern));
        }
        // 排序白名单在仓储里，这里只负责拼上去。
        // 用 last() 而不是 orderByAsc/Desc：有三个排序表达式表达不了
        // （starred DESC, created_at DESC / COALESCE(...) ASC），
        // 而且 orderByAsc 是按字段名生成的，表达不了函数。
        w.last("ORDER BY " + orderBy);
        return selectList(w);
    }

    /**
     * 回顾队列：存了 N 天以上、没星标、也没处理过的。
     * 这是「收藏了却再也不看」这个隐性问题的唯一解药。
     *
     * <p><b>为什么是随机而不是「最久的排最前」。</b>
     * 按时间排的队列有个致命问题：如果最前面那几条恰好是你不感兴趣的，
     * 队列就冻在那里了 —— 天天打开都是同样几条，很快就会连整个
     * 「该回头看了」都不点。随机让每次抽到的不一样。代价是某一条可能很久才轮到，
     * 但它不会消失（一直在池子里），而个人库的池子通常只有几十条。
     *
     * <p><b>为什么排除 {@code read}。</b> {@code read} 表示「我看过了，别再推」。
     * 只有 {@code unread} 才需要被唤醒 —— 回顾队列的职责是让人想起忘掉的东西，
     * 不是反复提醒已经处理过的。
     *
     * <p>注意 {@code read} 和 {@code last_opened_at} 是两回事：
     * 打开原站只是重置计时（过 N 天还会再出现），而标成 {@code read} 是明确表态
     * 「不用再推给我了」。前者是「我瞄了一眼」，后者是「我处理完了」。
     *
     * <p>池子只有几十条，{@code ORDER BY RAND()} 会给每行算一个随机数，
     * 这个量级下开销可以忽略。
     */
    @Select("SELECT * FROM link WHERE " + REVIEW_WHERE + " ORDER BY RAND() LIMIT #{limit}")
    List<LinkEntity> dueForReview(@Param("uid") String uid,
                                  @Param("cutoff") String cutoff,
                                  @Param("limit") int limit);

    /** 队列里一共有多少条。用于侧栏计数和「今天还剩几张」。 */
    @Select("SELECT COUNT(*) FROM link WHERE " + REVIEW_WHERE)
    int countDueForReview(@Param("uid") String uid, @Param("cutoff") String cutoff);

    /* ────────────── 周报统计 ────────────── */

    /** 领域分布的一项。{@code @ConstructorArgs} 的理由见 {@code CorrectionMapper}。 */
    record DomainCount(String domain, int count) {}

    @Select("SELECT COUNT(*) FROM link WHERE user_id = #{uid} AND created_at >= #{since}")
    int countSavedSince(@Param("uid") String uid, @Param("since") String since);

    @Select("SELECT COUNT(*) FROM link WHERE user_id = #{uid} "
            + "AND last_opened_at IS NOT NULL AND last_opened_at >= #{since}")
    int countOpenedSince(@Param("uid") String uid, @Param("since") String since);

    /**
     * 这段时间标为已用的几条。
     *
     * <p>用 {@code updated_at} 而不是 {@code created_at}：改状态会刷新
     * {@code updated_at}，用创建时间会把「这周标为已用的」错算成
     * 「这周存的里面标了已用的」。
     */
    @Select("SELECT COUNT(*) FROM link WHERE user_id = #{uid} AND status = 'used' "
            + "AND updated_at IS NOT NULL AND updated_at >= #{since}")
    int countUsedSince(@Param("uid") String uid, @Param("since") String since);

    /** 从存下来到现在一次都没打开过的总数 ——「数字坟场」最直接的度量。 */
    @Select("SELECT COUNT(*) FROM link WHERE user_id = #{uid} AND last_opened_at IS NULL")
    int countNeverOpened(@Param("uid") String uid);

    @Select("SELECT COUNT(*) FROM link WHERE user_id = #{uid}")
    int countAllForUser(@Param("uid") String uid);

    @Select("SELECT COALESCE(domain_category, 'other') AS d, COUNT(*) AS n FROM link "
            + "WHERE user_id = #{uid} AND created_at >= #{since} "
            + "GROUP BY d ORDER BY n DESC, d ASC")
    @ConstructorArgs({
            @Arg(column = "d", javaType = String.class),
            @Arg(column = "n", javaType = int.class)
    })
    List<DomainCount> topDomainsSince(@Param("uid") String uid, @Param("since") String since);

    @Select("SELECT * FROM link WHERE user_id = #{uid} AND created_at >= #{since} "
            + "ORDER BY created_at DESC")
    List<LinkEntity> createdSince(@Param("uid") String uid, @Param("since") String since);

    /* ────────────── 修改与删除 ────────────── */

    /**
     * 用一次新的分析结果替换掉已存记录里「属于 AI 的那部分」。
     *
     * <p><b>SQL 里列出来的列就是这次会动的全部</b>，这是刻意写死而不是用
     * {@code update(entity, wrapper)}：后者会把实体里所有非 null 字段都写进去，
     * 包括 {@code url} / {@code starred} / {@code status} —— 而这三样恰恰是
     * 「用户和这条记录的关系」，补一次正文不该让一条已加星、已标已用的记录
     * 退回未读，也不该把「存了多久」重置成现在。
     *
     * <p>值直接用 {@code #{e.xxx}}，所以 <b>null 会被写成 NULL</b>，
     * 和迁移前的行为一致（{@code update(entity, ...)} 会跳过 null，
     * 那是另一种语义，不能用在这儿）。
     */
    @Update("UPDATE link SET "
            + "title = #{e.title}, summary_short = #{e.summaryShort}, "
            + "summary_long = #{e.summaryLong}, note = #{e.note}, "
            + "note_options = #{e.noteOptions, typeHandler=com.shilian.repo.handler.JsonListTypeHandler}, "
            + "domain_category = #{e.domainCategory}, "
            + "purpose_categories = #{e.purposeCategories, typeHandler=com.shilian.repo.handler.JsonListTypeHandler}, "
            + "tags = #{e.tags, typeHandler=com.shilian.repo.handler.JsonListTypeHandler}, "
            + "content_type = #{e.contentType}, confidence = #{e.confidence}, "
            + "needs_review = #{e.needsReview}, snapshot_text = #{e.snapshotText}, "
            + "ai_raw = #{e.aiRaw}, analyze_status = 'done', ai_attempts = #{e.aiAttempts}, "
            + "prompt_tokens = #{e.promptTokens}, completion_tokens = #{e.completionTokens}, "
            + "updated_at = #{updatedAt} "
            + "WHERE id = #{e.id} AND user_id = #{uid}")
    int replaceAnalysis(@Param("e") LinkEntity e,
                        @Param("uid") String uid,
                        @Param("updatedAt") String updatedAt);

    /**
     * 局部更新。{@code sets} 里的列名已经过仓储的白名单校验。
     *
     * <p>用 {@code UpdateWrapper.set()} 而不是 {@code updateById}：
     * 前者会把 {@code null} 也写成 NULL，后者会跳过 null 字段。
     * {@code patch} 的语义是「调用方想改成什么就是什么」，包括清空备注。
     *
     * <p>{@code user_id} 显式写进 wrapper —— 不加上的话，
     * 「改一条不属于自己的记录」会静默成功。
     */
    default int patchForUser(String id, String uid, java.util.Map<String, Object> sets,
                             String updatedAt) {
        UpdateWrapper<LinkEntity> w = new UpdateWrapper<>();
        w.eq("id", id).eq("user_id", uid);
        sets.forEach(w::set);
        w.set("updated_at", updatedAt);
        return update(null, w);
    }

    /**
     * 删除。
     *
     * <p>返回影响行数：0 表示「不存在」或「不是你的」，两种情况在接口层都是 404。
     * <b>区分二者等于告诉调用方「这条记录存在，只是不属于你」</b>——
     * 那正是数据泄露的起点。
     */
    @Delete("DELETE FROM link WHERE id = #{id} AND user_id = #{uid}")
    int deleteForUser(@Param("id") String id, @Param("uid") String uid);

    /** 记一次打开，用于「多久没看了」的判断。 */
    @Update("UPDATE link SET last_opened_at = #{at} WHERE id = #{id} AND user_id = #{uid}")
    int touchOpened(@Param("id") String id, @Param("uid") String uid, @Param("at") String at);

    /* ────────────── 留痕用的读 ────────────── */

    /**
     * 读 AI 的原始输出。
     *
     * <p>用户改了两次时，记下来的仍然是「AI 原本判的 → 用户最终改成的」，
     * 靠的就是这里读到的是原始 JSON 而不是上一次改完的值。
     */
    @Select("SELECT ai_raw FROM link WHERE id = #{id} AND user_id = #{uid}")
    String readAiRaw(@Param("id") String id, @Param("uid") String uid);

    /** 把没有主人的历史数据划给某个用户。只在「第一个账号」时调。 */
    @Update("UPDATE link SET user_id = #{userId} WHERE user_id IS NULL")
    int claimOrphans(@Param("userId") String userId);
}

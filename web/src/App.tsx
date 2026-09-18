import { useCallback, useEffect, useMemo, useState } from "react";
import { SearchX, Check, Link2, RefreshCw, ServerCrash, AlertCircle } from "lucide-react";
import TopBar, { type SortKey } from "./components/TopBar";
import LinkEntry from "./components/LinkEntry";
import SavePanel from "./components/SavePanel";
import ReviewView from "./components/ReviewView";
import WeeklyView from "./components/WeeklyView";
import AuthView from "./components/AuthView";
import {
  ApiError, getMeta, listLinks, patchLink, deleteLink, getReviewCount,
  semanticSearch, getMe, logout,
  type AuthUser, type Meta,
} from "./api";
import { DOMAINS, PURPOSES, type DomainKey, type LinkItem, type PurposeKey } from "./types";

/**
 * 提示词和枚举的中文名只有服务端一份。前端为了配色还是得留一份 DOMAINS，
 * 于是就有走偏的可能——之前 `biz` 的标签就是这么漂掉的（前端写「商业·资讯」、
 * 服务端早已改成「商业·行业」，直接导致技术博客被判错类）。
 *
 * 这里做一个启动期的一致性检查：对不上就在控制台报出来。
 * 不为它加运行时依赖（labels 还是要能同步渲染），但把静默的坑变成看得见的警告。
 */
function checkMetaConsistency(meta: Meta) {
  const diffs: string[] = [];
  meta.domains.forEach((d) => {
    const local = DOMAINS.find((x) => x.key === d.code);
    if (local && local.name !== d.label) {
      diffs.push(`领域 ${d.code}：前端「${local.name}」≠ 后端「${d.label}」`);
    }
  });
  meta.purposes.forEach((p) => {
    const local = PURPOSES.find((x) => x.key === p.code);
    if (local && local.name !== p.label) {
      diffs.push(`用途 ${p.code}：前端「${local.name}」≠ 后端「${p.label}」`);
    }
  });
  if (diffs.length) {
    console.warn("[拾链] 前后端枚举中文名不一致，请同步 src/types.ts：\n" + diffs.join("\n"));
  }
}

type View = "list" | "review" | "weekly";

export default function App() {
  const [links, setLinks] = useState<LinkItem[]>([]);
  const [meta, setMeta] = useState<Meta | null>(null);
  const [loading, setLoading] = useState(true);
  const [bootError, setBootError] = useState<string | null>(null);

  /**
   * 当前登录的人。null = 未登录，此时整个应用不加载任何数据。
   *
   * <b>刻意不把它存进 localStorage。</b>
   * 前端记住「我登录了」而服务端的会话其实已经过期，
   * 表现就是界面上还挂着我的名字、但每个请求都 401——
   * 用户看到的是一片空白加上一堆失败提示，完全不知道发生了什么。
   * 每次启动问一次 `/api/auth/me`，让界面上的状态和真实会话始终同步。
   */
  const [user, setUser] = useState<AuthUser | null>(null);
  /** 首次确认登录态之前的短暂空档。不先画一帧空白，登录页会闪一下。 */
  const [authChecking, setAuthChecking] = useState(true);

  /**
   * 当前在哪个视图：列表 / 回顾 / 周报。
   *
   * 回顾和周报做成整屏视图，不是弹窗。弹窗会被当成「打扰」随手关掉，
   * 而回顾这件事恰恰需要用户停一下。整屏之后左侧栏还在，
   * 他随时能切回去，但进来之后注意力只在这一条上。
   */
  const [view, setView] = useState<View>("list");
  const [reviewCount, setReviewCount] = useState(0);

  const [query, setQuery] = useState("");
  const [semantic, setSemantic] = useState(false);
  /**
   * 语义搜索的结果：id → 相似度。null 表示「还没算出来」。
   *
   * 只存顺序和分数，不存记录本身。这样它和 links 列表是解耦的——
   * 用户删掉一条、或者改完标题重新拉列表，都不需要重新搜一次。
   */
  const [semanticOrder, setSemanticOrder] = useState<Map<string, number> | null>(null);
  /** true 表示没有一条达到阈值，返回的几条是「矮子里拔将军」 */
  const [semanticFallback, setSemanticFallback] = useState(false);
  const [semanticLoading, setSemanticLoading] = useState(false);
  /** 语义搜索失败的原因。单独存而不是复用全局 toast：它要一直挂在列表上方，不是一闪而过 */
  const [semanticError, setSemanticError] = useState<string | null>(null);
  /** 本次补算了几条向量，>0 时告诉用户「这次慢是因为在建索引」 */
  const [semanticComputed, setSemanticComputed] = useState(0);
  const [sort, setSort] = useState<SortKey>("recent");
  const [activeDomain, setActiveDomain] = useState<DomainKey | "all">("all");
  const [activePurposes, setActivePurposes] = useState<PurposeKey[]>([]);
  // 主题选择要留下来。之前只存在内存里，刷新一次就回到浅色——
  // 对一个天天开着用的工具来说，每次都要重新点一下是很烦的。
  // 没存过时跟随系统偏好，而不是硬编码浅色。
  const [theme, setTheme] = useState<"light" | "dark">(() => {
    const saved = localStorage.getItem("shilian-theme");
    if (saved === "light" || saved === "dark") return saved;
    return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
  });
  const [panelOpen, setPanelOpen] = useState(false);
  const [panelUrl, setPanelUrl] = useState("");
  /**
   * 非空表示抽屉正在「给这条已存记录补正文」，而不是新建。
   * 抽屉只认它的 id（见 SavePanel 里的说明）。
   */
  const [fixTarget, setFixTarget] = useState<LinkItem | null>(null);
  /**
   * 非空表示抽屉正在「编辑这条已存记录」。和 fixTarget 互斥。
   *
   * 分成两个状态而不是一个带 mode 的状态：让「不可能同时是补正文和编辑」
   * 这件事由类型和初始化保证，而不是靠调用方记得清另一个。
   */
  const [editTarget, setEditTarget] = useState<LinkItem | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [toastKind, setToastKind] = useState<"ok" | "warn">("ok");

  useEffect(() => {
    document.documentElement.classList.toggle("dark", theme === "dark");
    localStorage.setItem("shilian-theme", theme);
  }, [theme]);

  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(null), 2800);
    return () => clearTimeout(t);
  }, [toast]);

  const notify = useCallback((msg: string, kind: "ok" | "warn" = "ok") => {
    setToastKind(kind);
    setToast(msg);
  }, []);

  /**
   * 重算侧栏那个「该回头看了」的计数。
   *
   * 刻意问服务端要，而不是拿本地 links 自己 filter：后端的筛选条件
   * （排除星标/已用/已读，还要按闲置天数比）抄到前端就是第二份实现，
   * 一漂就会「侧栏说有 5 条、点进去只有 3 条」。代价只是一次 COUNT。
   */
  const refreshReviewCount = useCallback(async () => {
    try {
      const r = await getReviewCount();
      setReviewCount(r.dueTotal);
    } catch {
      // 计数拿不到不该打断任何事。宁可留着旧数字，也别弹一个错误出来
    }
  }, []);

  /* ── 启动：一次性把全量数据和字典拉回来 ── */

  const boot = useCallback(async () => {
    setLoading(true);
    setBootError(null);
    try {
      const [items, m] = await Promise.all([listLinks(), getMeta()]);
      setLinks(items);
      setMeta(m);
      checkMetaConsistency(m);
      void refreshReviewCount();
    } catch (e) {
      const err = e as ApiError;
      // 会话过期了。别报「后端没连上」——那会让人去重启服务，
      // 而真正要做的只是重新登录一次。
      if (err.status === 401) {
        setUser(null);
        return;
      }
      setBootError(
        err.offline
          ? "连不上后端服务。先在 server/ 目录（和 web/ 平级）跑 ./mvn.sh spring-boot:run"
          : err.message,
      );
    } finally {
      setLoading(false);
    }
  }, [refreshReviewCount]);

  /* ── 启动第一步：先确认有没有登录 ── */
  useEffect(() => {
    let alive = true;
    getMe()
      .then((u) => { if (alive) setUser(u); })
      .catch(() => { if (alive) setUser(null); })
      .finally(() => { if (alive) setAuthChecking(false); });
    return () => { alive = false; };
  }, []);

  /* ── 确认登录后才拉数据 ── */
  useEffect(() => {
    if (user) void boot();
  }, [user, boot]);

  /**
   * 登出。
   *
   * 不管请求成不成功都要清本地状态：用户点的是「退出」，
   * 就算服务端那一下没成功，界面也必须先回到登录页——
   * 否则他会以为没退出成功，然后反复点。
   */
  const handleLogout = async () => {
    try {
      await logout();
    } catch {
      // 忽略：退出这件事以本地清干净为准
    }
    setUser(null);
    setLinks([]);
    setMeta(null);
    setReviewCount(0);
    setQuery("");
    setSemanticOrder(null);
    setView("list");
  };

  /**
   * 支持 `?url=<网址>` 直接进入分析。
   *
   * 这不是为了测试加的便利口子——它就是书签小工具（bookmarklet）需要的入口。
   * 书签干的事就是把当前页地址拼成这个链接：用户在任何页面点一下书签，
   * 就跳到拾链并自动开始分析，不用复制、切窗口、粘贴、回车四步。
   *
   * 用完立刻把参数从地址栏抹掉，否则刷新会重复触发分析（白花一次 token）。
   */
  useEffect(() => {
    const u = new URLSearchParams(window.location.search).get("url");
    if (!u) return;
    setPanelUrl(u);
    setPanelOpen(true);
    window.history.replaceState({}, "", window.location.pathname);
    // 只在首次挂载时跑一次；openPanel 只调用稳定的 setState，不需要进依赖
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /*
   * 筛选全部在客户端做。
   *
   * 理由：侧栏的领域/用途计数本来就需要全量数据，所以列表无论如何都要整个拉回来；
   * 既然数据在手，搜索框边打边筛就不该再走一趟网络——那样每敲一个字都有延迟。
   *
   * 代价是数据量。个人库到几千条、响应体到几 MB 时该换成服务端筛选
   * （后端 /api/links 的 domain / purposes / q / sort 参数已经就绪），
   * 再单独加一个返回计数的接口。
   */
  const domainCounts = useMemo(() => {
    const m: Record<string, number> = {};
    links.forEach((l) => (m[l.domainKey] = (m[l.domainKey] ?? 0) + 1));
    return m;
  }, [links]);

  const purposeCounts = useMemo(() => {
    const m: Record<string, number> = {};
    links.forEach((l) => l.purposes.forEach((p) => (m[p] = (m[p] ?? 0) + 1)));
    return m;
  }, [links]);

  /**
   * 语义搜索。只在「开了语义模式 + 有查询词」时才发请求。
   *
   * <p>防抖 350ms：这一下要打后端、后端还要打向量服务，边打边搜会发出七八个请求，
   * 而每个请求都要算一次查询向量——那是真花钱的。350ms 是「停下来想一下」的量级。
   */
  useEffect(() => {
    const q = query.trim();
    if (!semantic || !q) {
      setSemanticOrder(null);
      setSemanticError(null);
      setSemanticFallback(false);
      setSemanticComputed(0);
      setSemanticLoading(false);
      return;
    }
    /*
     * 用一个标志位丢弃过期响应。
     * 只 clearTimeout 是不够的：请求已经发出去之后用户又改了一个字，
     * 旧请求的响应可能在新请求之后才回来，于是列表被一个过时的查询覆盖——
     * 表现为「搜索框里写着 A，结果却是 B 的」。
     */
    let alive = true;
    const timer = setTimeout(() => {
      setSemanticLoading(true);
      semanticSearch(q)
        .then((r) => {
          if (!alive) return;
          setSemanticOrder(new Map(r.hits.map((h) => [h.id, h.score])));
          setSemanticFallback(r.fallback);
          setSemanticComputed(r.computed);
          setSemanticError(null);
        })
        .catch((e: unknown) => {
          if (!alive) return;
          setSemanticOrder(null);
          setSemanticError(e instanceof ApiError ? e.message : "语义搜索失败了");
        })
        .finally(() => {
          if (alive) setSemanticLoading(false);
        });
    }, 350);
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [semantic, query]);

  const filtered = useMemo(() => {
    let out = links;
    if (activeDomain !== "all") out = out.filter((l) => l.domainKey === activeDomain);
    if (activePurposes.length)
      out = out.filter((l) => activePurposes.every((p) => l.purposes.includes(p)));

    /*
     * 语义模式：命中的集合由服务端给（已按相似度排好），本地的领域/用途筛选照旧生效。
     * 两者是**求交集**的关系——先取语义命中，再用用户勾的筛选条件缩小范围。
     *
     * 这样写而不是让服务端把筛选也做了，是因为侧栏的计数本来就要全量数据，
     * 前端无论如何都握着完整列表；把筛选抄到服务端等于同一套条件维护两份，必然漂。
     */
    if (semantic && semanticOrder) {
      return out
        .filter((l) => semanticOrder.has(l.id))
        .sort((a, b) => (semanticOrder.get(b.id) ?? 0) - (semanticOrder.get(a.id) ?? 0));
    }

    if (query.trim()) {
      const q = query.trim().toLowerCase();
      out = out.filter((l) =>
        [l.title, l.summary, l.note, l.site, l.contentType, ...l.tags]
          .join(" ")
          .toLowerCase()
          .includes(q),
      );
    }
    const s = [...out];
    // 服务端已按 created_at 倒序返回，所以「最近收藏」保持原顺序即可
    if (sort === "starred") s.sort((a, b) => Number(b.starred) - Number(a.starred));
    if (sort === "stale") s.sort((a, b) => (b.idleDays ?? 0) - (a.idleDays ?? 0));
    return s;
  }, [links, activeDomain, activePurposes, query, sort, semantic, semanticOrder]);

  /* ── 改动：先乐观更新，失败再回滚 ── */

  const toggleStar = async (id: string) => {
    const target = links.find((l) => l.id === id);
    if (!target) return;
    const next = !target.starred;
    setLinks((prev) => prev.map((l) => (l.id === id ? { ...l, starred: next } : l)));
    try {
      await patchLink(id, { starred: next });
      // 加星会让它退出回顾队列，计数得跟着变
      void refreshReviewCount();
    } catch (e) {
      setLinks((prev) => prev.map((l) => (l.id === id ? { ...l, starred: !next } : l)));
      notify((e as ApiError).message, "warn");
    }
  };

  /**
   * 打开原站时顺手记一次「已打开」。
   *
   * 这一步看着可有可无，其实是回顾机制的前提：`last_opened_at` 没人写的话，
   * 「该回头看了」永远算不准，这个功能就废了。
   */
  const handleOpen = (item: LinkItem) => {
    patchLink(item.id, { markOpened: true })
      // 记不上不影响打开，静默即可
      .then(() => void refreshReviewCount())
      .catch(() => {});
    setLinks((prev) =>
      prev.map((l) => (l.id === item.id ? { ...l, idleDays: 0, savedLabel: l.savedLabel } : l)),
    );
  };

  const handleDelete = async (id: string) => {
    const backup = links;
    setLinks((prev) => prev.filter((l) => l.id !== id));
    try {
      await deleteLink(id);
      notify("已删除");
      void refreshReviewCount();
    } catch (e) {
      setLinks(backup);
      notify((e as ApiError).message, "warn");
    }
  };

  /**
   * 标记「看过了」。
   *
   * <p>这和「打开过」是两件事，不能合并：
   * 打开过只是写 `last_opened_at`，把计时器归零，7 天后它还会回来；
   * 看过了是 `status = 'read'`，永久的「别再推给我」。
   *
   * <p>回顾模式里必须有后者。否则用户处理完一圈，下周看到的还是同一批东西，
   * 很快就会连这个模式都不进了——那这个产品最大的风险就兑现了。
   */
  const handleMarkRead = async (id: string) => {
    const backup = links;
    setLinks((prev) => prev.map((l) => (l.id === id ? { ...l, status: "read" } : l)));
    try {
      await patchLink(id, { status: "read" });
      void refreshReviewCount();
    } catch (e) {
      setLinks(backup);
      notify((e as ApiError).message, "warn");
    }
  };

  const togglePurpose = (k: PurposeKey) => {
    setActivePurposes((prev) =>
      prev.includes(k) ? prev.filter((x) => x !== k) : [...prev, k],
    );
    setView("list");
  };

  /**
   * 点领域 = 想看列表。在回顾/周报里点它也要切回来，
   * 否则点了没反应，会像坏了一样。
   */
  const pickDomain = (k: DomainKey | "all") => {
    setActiveDomain(k);
    setView("list");
  };

  /** 从整屏视图回列表。顺手重算计数——刚才可能处理掉了几条。 */
  const exitToList = () => {
    setView("list");
    void refreshReviewCount();
  };

  const openPanel = (url: string) => {
    setFixTarget(null);
    setEditTarget(null);
    setPanelUrl(url);
    setPanelOpen(true);
  };

  /** 打开同一个抽屉，但切成「补正文」模式：给一条待补的卡片重跑分析。 */
  const openFix = (item: LinkItem) => {
    setEditTarget(null);
    setFixTarget(item);
    setPanelUrl(item.url);
    setPanelOpen(true);
  };

  /**
   * 打开同一个抽屉，切成「编辑」模式：改这条记录的内容，不调模型。
   *
   * 和 openFix 互斥——两个 target 同时有值时面板分不清该走哪条路，
   * 所以各自打开前把另一个清掉。
   */
  const openEdit = (item: LinkItem) => {
    setFixTarget(null);
    setEditTarget(item);
    setPanelUrl(item.url);
    setPanelOpen(true);
  };

  /**
   * 抽屉的回写。
   *
   * mode 由面板显式传，不靠「列表里有没有这条」去猜——
   * 猜错的后果是把一次更新当成新记录插到最前面，看起来像凭空多了一条。
   */
  const handleSave = (item: LinkItem, mode: "new" | "fix" | "edit") => {
    setLinks((prev) =>
      mode === "new"
        ? [item, ...prev]
        : prev.map((l) => (l.id === item.id ? item : l)),
    );
    setPanelOpen(false);
    setFixTarget(null);
    setEditTarget(null);
    // 刚存完就该看见它。刚才若在回顾/周报里，切回列表
    setView("list");
    notify(
      mode === "fix"
        ? "已用这次的分析结果替换"
        : mode === "edit"
          ? "改好了"
          : "已存入 · 分类和备注随时可以改",
    );
    // 补正文可能把「已用」勾进用途（那是状态，服务端会归位），队列会变
    void refreshReviewCount();
  };

  /* ── 还没确认登录态：先别画任何东西，否则登录页会闪一下 ── */

  if (authChecking) {
    return <div className="h-full w-full bg-canvas" />;
  }

  /* ── 未登录：只给登录页，不加载任何数据 ── */
  if (!user) {
    return (
      <AuthView
        onAuthed={setUser}
        theme={theme}
        toggleTheme={() => setTheme(theme === "light" ? "dark" : "light")}
      />
    );
  }

  /* ── 后端没起来时的整页提示 ── */

  if (bootError) {
    return (
      <div className="flex h-full w-full items-center justify-center bg-canvas px-6">
        <div className="max-w-[420px] text-center">
          <ServerCrash size={28} className="mx-auto text-ink3/60" strokeWidth={1.5} />
          <h1 className="mt-4 text-[15px] font-medium text-ink">后端没连上</h1>
          <p className="mt-2 text-[13px] leading-relaxed text-ink2">{bootError}</p>
          <button
            onClick={() => void boot()}
            className="mt-5 inline-flex items-center gap-1.5 rounded-lg border border-line px-3.5 py-[7px] text-[12.5px] text-ink2 transition-colors hover:border-line-strong hover:text-ink"
          >
            <RefreshCw size={12.5} />
            重试
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="flex h-full w-full flex-col overflow-hidden bg-canvas">
      {/*
        顶栏在所有视图里都在：录入框随时可用，看到好东西不用先切回列表。
        它内部自己决定要不要渲染筛选行——回顾和周报时藏起来，
        因为筛选对「一次只看一张」没有意义，留着只会让人以为能把这一屏筛一筛。
      */}
      <TopBar
        onSubmitUrl={openPanel}
        total={links.length}
        reviewCount={reviewCount}
        view={view}
        onReview={() => setView("review")}
        onWeekly={() => setView("weekly")}
        theme={theme}
        toggleTheme={() => setTheme(theme === "light" ? "dark" : "light")}
        aiReady={meta?.aiConfigured ?? false}
        query={query}
        setQuery={setQuery}
        semantic={semantic}
        setSemantic={setSemantic}
        semanticAvailable={meta?.semantic?.available ?? false}
        semanticReason={meta?.semantic?.reason ?? null}
        semanticLoading={semanticLoading}
        sort={sort}
        setSort={setSort}
        domainCounts={domainCounts}
        purposeCounts={purposeCounts}
        activeDomain={activeDomain}
        setActiveDomain={pickDomain}
        activePurposes={activePurposes}
        togglePurpose={togglePurpose}
        onRefresh={() => void boot()}
        refreshing={loading}
        count={filtered.length}
        user={user}
        onLogout={() => void handleLogout()}
        notify={notify}
      />

      <main className="flex-1 overflow-y-auto">
        {view !== "list" ? (
          view === "review" ? (
            <ReviewView
              onOpen={handleOpen}
              onToggleStar={toggleStar}
              onDelete={handleDelete}
              onMarkRead={handleMarkRead}
              onExit={exitToList}
              notify={notify}
            />
          ) : (
            <WeeklyView onOpen={handleOpen} onExit={exitToList} notify={notify} />
          )
        ) : (
          <div className="mx-auto w-full max-w-[880px] px-10 pb-20 pt-2">
              {/*
                语义搜索的两条说明。都刻意常驻而不是一闪而过的 toast：
                它们解释的是「你现在看到的这个列表为什么长这样」，
                用户扫一眼列表的过程中随时可能需要回头看。
              */}
              {semanticError && (
                <div className="mb-4 flex items-start gap-2 rounded-lg border border-line bg-sunken px-3 py-2.5">
                  <AlertCircle size={13} className="mt-[2px] shrink-0 text-ink3" />
                  <p className="text-[12px] leading-relaxed text-ink2">
                    语义搜索没成功：{semanticError}
                    <span className="text-ink3">（可以关掉语义，用关键词搜）</span>
                  </p>
                </div>
              )}

              {!semanticError && semantic && query.trim() && semanticComputed > 0 && (
                <div className="mb-4 rounded-lg border border-line bg-sunken px-3 py-2.5">
                  <p className="text-[12px] leading-relaxed text-ink2">
                    这次慢一点，是因为刚给 {semanticComputed} 条记录算了语义索引。
                    <span className="text-ink3">之后同样的搜索会快很多。</span>
                  </p>
                </div>
              )}

              {!semanticError && semanticFallback && (
                <div className="mb-4 rounded-lg border border-line bg-sunken px-3 py-2.5">
                  <p className="text-[12px] leading-relaxed text-ink2">
                    没有和「{query.trim()}」很接近的记录，下面这几条是相对最像的。
                    <span className="text-ink3">换个说法试试，或者关掉语义用关键词搜。</span>
                  </p>
                </div>
              )}

              {loading && links.length === 0 ? (
                <SkeletonList />
              ) : filtered.length === 0 ? (
                <div className="flex flex-col items-center justify-center py-28 text-center">
                  <SearchX size={26} className="text-ink3/60" strokeWidth={1.5} />
                  <p className="mt-4 text-[13.5px] text-ink2">
                    {links.length === 0 ? "还没有收藏" : "这里还空着"}
                  </p>
                  <p className="mt-1 max-w-[300px] text-[12px] leading-relaxed text-ink3">
                    {links.length === 0
                      ? "在上面粘贴一个网址，AI 会帮你写好摘要、备注和分类"
                      : "换个筛选条件，或者直接在上面粘贴一个网址试试"}
                  </p>
                  {links.length === 0 && (
                    <button
                      onClick={() => openPanel("https://github.com/anthropics/skills")}
                      className="mt-5 flex items-center gap-1.5 rounded-lg border border-line px-3.5 py-[7px] text-[12.5px] text-ink2 transition-colors hover:border-line-strong hover:text-ink"
                    >
                      <Link2 size={12.5} />
                      试存一个网址
                    </button>
                  )}
                </div>
              ) : (
                <div>
                  {filtered.map((l, i) => (
                    <div
                      key={l.id}
                      className="rise"
                      style={{ animationDelay: `${Math.min(i, 11) * 26}ms` }}
                    >
                      <LinkEntry
                        item={l}
                        onToggleStar={toggleStar}
                        onOpen={handleOpen}
                        onDelete={handleDelete}
                        onFix={openFix}
                        onEdit={openEdit}
                      />
                    </div>
                  ))}
                </div>
              )}
          </div>
        )}
      </main>

      <SavePanel
        open={panelOpen}
        url={panelUrl}
        fixLink={fixTarget}
        editLink={editTarget}
        onClose={() => {
          setPanelOpen(false);
          setFixTarget(null);
          setEditTarget(null);
        }}
        onSave={handleSave}
        onNotify={notify}
      />

      {toast && (
        <div className="fadein fixed bottom-6 left-1/2 z-[60] flex -translate-x-1/2 items-center gap-2 rounded-lg border border-line bg-surface px-3.5 py-2 shadow-[0_10px_30px_-12px_rgba(0,0,0,0.25)]">
          {toastKind === "ok" ? (
            <Check size={13} className="text-emerald-600" />
          ) : (
            <ServerCrash size={13} className="text-amber-600" />
          )}
          <span className="text-[12.5px] text-ink">{toast}</span>
        </div>
      )}
    </div>
  );
}

function SkeletonList() {
  return (
    <div>
      {Array.from({ length: 6 }).map((_, i) => (
        <div
          key={i}
          className="grid grid-cols-[3px_1fr] gap-x-4 border-b border-linesoft py-[18px] last:border-b-0"
        >
          {/* 骨架要跟真条目同形状：3px 竖线 + 标题 + 两行摘要。
              形状对不上，加载完那一瞬间会跳一下。 */}
          <div className="rounded-[2px] bg-sunken pulse-soft" />
          <div>
            <div className="h-[17px] w-2/5 rounded bg-sunken pulse-soft" />
            <div className="mt-2 h-3 w-4/5 rounded bg-sunken pulse-soft" />
            <div className="mt-2 h-3 w-3/5 rounded bg-sunken pulse-soft" />
          </div>
        </div>
      ))}
    </div>
  );
}

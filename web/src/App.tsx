import { useCallback, useEffect, useMemo, useState } from "react";
import { SearchX, Check, Link2, RefreshCw, ServerCrash, AlertCircle } from "lucide-react";
import TopBar, { type SortKey } from "./components/TopBar";
import LinkEntry from "./components/LinkEntry";
import SavePanel from "./components/SavePanel";
import ReviewView from "./components/ReviewView";
import WeeklyView from "./components/WeeklyView";
import AdminView from "./components/AdminView";
import { ResetView, VerifyView } from "./components/MailLinkView";
import TagManager from "./components/TagManager";
import AuthView from "./components/AuthView";
import {
  ApiError, getMeta, listLinks, patchLink, deleteLink, getReviewCount,
  semanticSearch, getMe, logout, listTags, isAdmin,
  type AuthUser, type Meta, type TagItem,
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

/*
 * reset / verify 这两个视图和前面几个不一样：
 * list/review/weekly/admin 都是点出来的，而这两个是**从邮件链接点进来的**——
 * 用户此时手上只有一个带 token 的网址，没有「先登录再点某个按钮」这条路径。
 * 所以它们必须由地址栏驱动，见下面读 URL 的那个 effect。
 */
type View = "list" | "review" | "weekly" | "admin" | "reset" | "verify";

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

  /**
   * 邮件链接带进来的令牌。
   *
   * 单独存一个 state 而不是让重置页自己去读地址栏：
   * 读完我们会立刻把地址栏抹掉（见下面那个 effect），
   * 而页面之后还要用这个令牌调接口——抹掉之后再读就只剩 null 了，
   * 于是用户看到「链接失效」，可他明明刚点开。
   */
  const [mailToken, setMailToken] = useState<string | null>(null);

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
  /**
   * 自增计数，只用来「手动重跑一次语义搜索」。
   *
   * <p>把它加进搜索 effect 的依赖数组，就是「再试一次」按钮的全部实现。
   * 不选「把 query 清空再设回去」那种写法：那会触发两次渲染、闪一下空列表，
   * 而且如果用户搜的词本来就是同一个，React 可能把两次 setState 合并掉，
   * 结果是「点了按钮没反应」——最要命的正是这个按钮失灵。
   */
  const [semanticRetry, setSemanticRetry] = useState(0);
  const [sort, setSort] = useState<SortKey>("recent");
  const [activeDomain, setActiveDomain] = useState<DomainKey | "all">("all");
  const [activePurposes, setActivePurposes] = useState<PurposeKey[]>([]);
  /**
   * 标签筛选。只存名字，和用途一样是「勾几个就是都要有」。
   *
   * <p>为什么是「都要有」而不是「有一个就行」：用途那边已经是这个语义
   * （`activePurposes.every`），两套筛选的规矩不一致会让用户每次都要重新试一遍——
   * 而勾得越多结果越少这件事，是符合直觉的那一种。
   */
  const [activeTags, setActiveTags] = useState<string[]>([]);
  /**
   * 全部标签及其条数。**从服务端取**，不从本地 links 自己数。
   *
   * <p>标签的增删改是服务端的跨行操作（一条 SQL 动几十行的 tags 数组），
   * 既然管理走服务端，这份清单也从同一个地方来，省得「chips 上说 5 条、
   * 删完说动了 7 条」这种对不上。
   */
  const [tags, setTags] = useState<TagItem[]>([]);
  /** 标签管理面板（改名 / 合并 / 删除） */
  const [tagManagerOpen, setTagManagerOpen] = useState(false);
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
  /**
   * 从回顾里发起的补正文，补完把结果回塞给回顾视图的那张卡片。
   *
   * <p>seq 用时间戳而不是递增计数器，是为了让「同一条连补两次」也能触发
   * ——React 的 effect 比的是引用，只放 item 的话第二次会漏。
   */
  const [reviewFixed, setReviewFixed] = useState<{ item: LinkItem; seq: number } | null>(null);
  /**
   * 需要滚动定位并高亮的那条记录的 id。用完（滚到位 + 高亮 2 秒）自动清空。
   *
   * <p>两件事共用它：R-01「重复链接去看看那条」和 R-02「quick 存完定位到新记录」。
   * 两处的诉求一模一样——把一条具体的记录推到用户眼前，所以走同一套机制，
   * 而不是各写一份滚动逻辑。
   */
  const [focusId, setFocusId] = useState<string | null>(null);
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
      /*
       * 标签也一起拉，但**单独兜住失败**：它只是筛选条上的一排 chips，
       * 拿不到的话列表照样能看、能搜。不该让一个次要请求把整个启动拖成错误页。
       */
      const [items, m, t] = await Promise.all([
        listLinks(),
        getMeta(),
        listTags().catch(() => [] as TagItem[]),
      ]);
      setLinks(items);
      setMeta(m);
      setTags(t);
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
    setTags([]);
    setActiveTags([]);
    setTagManagerOpen(false);
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
  /**
   * 邮件里的链接：`/reset?token=...` 和 `/verify?token=...`。
   *
   * 这两个是**真实网址**，不是内部 state 切换——用户从邮箱点开就是直接命中它，
   * 中间没有「先打开拾链再点某个按钮」这一步。所以只能在这里认。
   *
   * <p>工程没有路由库，这是刻意的：整个应用只有四个视图，
   * 为一个 state 切换引入 router、history 抽象和一堆路由配置不值当。
   * 代价就是这两个从外面进来的链接得手写一次识别——就这几行，可以接受。
   *
   * <p><b>认完立刻抹掉地址栏。</b>
   * 不抹的话刷新会拿同一个 token 再跑一遍，而令牌是用后即废的，
   * 于是第二次必定「链接失效」。用户看来就是：我明明刚点开，刷新一下就坏了。
   */
  useEffect(() => {
    const token = new URLSearchParams(window.location.search).get("token");
    if (!token) return;
    const path = window.location.pathname.replace(/\/+$/, "");
    if (path === "/reset" || path === "/verify") {
      setMailToken(token);
      setView(path === "/reset" ? "reset" : "verify");
      // replaceState 而不是 pushState：多一条历史记录的话，
      // 用户按返回键会回到那个还带着 token 的网址，等于又触发一次。
      window.history.replaceState({}, "", "/");
    }
    // 只在首次挂载时跑一次
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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
    /*
     * semanticRetry 是「再试一次」按钮的触发源：它一变就重跑整个 effect，
     * 包括那段防抖和 alive 标志位——所以重试和第一次搜索走的是同一条路径，
     * 不会出现「重试时旧响应把新结果覆盖掉」这种只有重试才有的 bug。
     */
  }, [semantic, query, semanticRetry]);

  /**
   * 把 focusId 指向的那条记录滚到视野里，并短暂高亮。
   *
   * <p>为什么不是「设了 focusId 立刻滚」：清筛选（setActiveDomain 等）和设 focusId
   * 是同一次事件里发生的，React 会把它们合并成一次重渲染。要等这一次渲染把
   * <b>清完筛选后的列表</b>提交到 DOM，getElementById 才找得到那条记录——
   * 直接同步查大概率落空。60ms 是给这一帧留的余量。
   *
   * <p>找不到不能静默：列表是全量渲染的，理论上不该找不到；真找不到说明状态被谁
   * 清错了，得让用户看见（否则点了「去看看那条」毫无反应，像是按钮坏了）。
   *
   * <p>滚动容器是下面那个 `<main className="flex-1 overflow-y-auto">`，
   * scrollIntoView 在它内部生效，不需要额外传容器。
   */
  useEffect(() => {
    if (!focusId) return;
    const scrollTimer = setTimeout(() => {
      const el = document.getElementById(`link-${focusId}`);
      if (!el) {
        notify("没找到那条记录", "warn");
        setFocusId(null);
        return;
      }
      el.scrollIntoView({ block: "center", behavior: "smooth" });
    }, 60);
    // 高亮 2 秒后撤掉：太短看不清是哪一条，太长会让人以为它一直是选中态。
    const clearTimer = setTimeout(() => setFocusId(null), 60 + 2000);
    return () => {
      clearTimeout(scrollTimer);
      clearTimeout(clearTimer);
    };
  }, [focusId, notify]);

  /**
   * 管理端的路由守卫。
   *
   * <p>入口只对管理员渲染，正常路径走不到这里。但视图是个状态：
   * 会话可能在半路变了（管理员身份被撤、换了账号），而视图还停在 admin 上。
   * 不拦的话就是一个空白页——用户只会觉得应用坏了，不会猜到是权限的事。
   *
   * <p>后端那一层还有一道（非管理员 404），页面本身也兜了「没有权限」那一屏
   * （见 AdminView）。三道不是重复：它们分别挡住「入口看得见」
   * 「请求发得出去」「界面白屏」这三种不同的漏法。
   */
  useEffect(() => {
    if (view !== "admin") return;
    if (isAdmin(user)) return;
    notify("没有权限", "warn");
    setView("list");
  }, [view, user, notify]);

  const filtered = useMemo(() => {
    let out = links;
    if (activeDomain !== "all") out = out.filter((l) => l.domainKey === activeDomain);
    if (activePurposes.length)
      out = out.filter((l) => activePurposes.every((p) => l.purposes.includes(p)));
    if (activeTags.length)
      out = out.filter((l) => activeTags.every((t) => l.tags.includes(t)));

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
  }, [links, activeDomain, activePurposes, activeTags, query, sort, semantic, semanticOrder]);

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

  /**
   * 标「用上了」。
   *
   * <p>和「看过了」是两种表态，别混：看过了是「别再推给我」，
   * 用上了是「我真的用上了」。以前这两件事挤在 status 一个字段里，
   * 于是标了已用就撤不回来——现在 used 是独立列，随手能拨回去
   * （在编辑面板里，见 SavePanel 那个开关）。
   */
  const handleMarkUsed = async (id: string) => {
    const backup = links;
    setLinks((prev) => prev.map((l) => (l.id === id ? { ...l, used: true } : l)));
    try {
      await patchLink(id, { used: true });
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

  /** 点标签 chip。和点用途一样：点筛选就是在说「我要看列表」。 */
  const toggleTag = (name: string) => {
    setActiveTags((prev) =>
      prev.includes(name) ? prev.filter((x) => x !== name) : [...prev, name],
    );
    setView("list");
  };

  /**
   * 标签改完（改名 / 合并 / 删除）之后的收尾。
   *
   * <p><b>整体重拉一次</b>，不在本地模仿服务端的改动。那套改动是跨行的
   * （几十条记录的 tags 数组），前端抄一遍就是第二份实现，必然漂。
   *
   * <p><b>顺手清掉标签筛选</b>：用户刚才筛的那个名字可能已经被改名或删掉了，
   * 留着它只会得到一个「筛选着、但一条都没有」的空列表，而且看不出为什么空。
   */
  const handleTagsChanged = (message: string) => {
    setTagManagerOpen(false);
    setActiveTags([]);
    notify(message);
    void boot();
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

    /*
     * 补正文是从回顾里发起的时候，补完留在回顾，不切回列表。
     *
     * 他是翻回顾翻到一半撞上「这条没抓到正文」才点的补，
     * 把他扔回列表等于让他自己重新找一遍刚才翻到哪了。
     * 结果通过 reviewFixed 回塞进那张卡片，原地就能看到补成了什么样。
     */
    const fixedFromReview = mode === "fix" && view === "review";
    if (fixedFromReview) {
      setReviewFixed({ item, seq: Date.now() });
    } else {
      // 刚存完就该看见它。刚才若在周报里，切回列表
      setView("list");
    }

    notify(
      mode === "fix"
        ? fixedFromReview
          ? "补好了，就是现在这个样子"
          : "已用这次的分析结果替换"
        : mode === "edit"
          ? "改好了"
          : "已存入 · 分类和备注随时可以改",
    );
    // 补完正文、或状态变了，队列可能跟着变
    void refreshReviewCount();
  };

  /**
   * 保存撞到 409 时点「去看看那条」。
   *
   * <p>要做的第一件事是<b>关抽屉并清掉两个 target</b>——否则用户点了按钮、抽屉一关，
   * 下次再打开面板会莫名其妙回到上次的补正文/编辑状态。
   *
   * <p>然后清掉所有可能挡住这条记录的筛选。定位目标是一条具体记录，而领域/用途/
   * 关键词/语义筛选都可能把它挡在列表之外——不清就会「点了按钮，列表却没滚过去，
   * 因为那条根本不在这儿」。此刻用户的意图很明确（我要看这一条），筛选的意图得让位。
   */
  const handleGotoExisting = (id: string) => {
    setPanelOpen(false);
    setFixTarget(null);
    setEditTarget(null);
    setActiveDomain("all");
    setActivePurposes([]);
    setActiveTags([]);
    setQuery("");
    setSemantic(false);
    setView("list");
    setFocusId(id);
  };

  /**
   * quick 存下来之后的回写（R-02）。
   *
   * <p>单独一个 handler，没往 handleSave 的 mode 里塞第四个值：quick 记录不经过
   * 草稿审核、不替换任何东西，和 new/fix/edit 三种 mode 没有一处共享逻辑。
   * 硬塞进去只会让 handleSave 多一个只走两行的分支，反而更难读。
   *
   * <p>提示语也不复用 handleSave 那句「已存入 · 分类和备注随时可以改」——
   * quick 记录还没有分类和备注，那句话对它来说是假的。
   */
  const handleQuickSaved = (item: LinkItem) => {
    setLinks((prev) => [item, ...prev]);
    setPanelOpen(false);
    setFixTarget(null);
    setEditTarget(null);
    // 和「去看看那条」同样先清筛选：新记录虽然插在最前，但筛选一挡就看不见，
    // 而用户刚点了「先存网址」，下一步一定是想确认它存进去了。
    setActiveDomain("all");
    setActivePurposes([]);
    setActiveTags([]);
    setQuery("");
    setSemantic(false);
    setView("list");
    setFocusId(item.id);
    notify("先存住了 · 补上正文就能分析");
    void refreshReviewCount();
  };

  /* ── 还没确认登录态：先别画任何东西，否则登录页会闪一下 ── */

  if (authChecking) {
    return <div className="h-full w-full bg-canvas" />;
  }

  /*
   * 重置密码必须能在**没登录**的时候打开。
   *
   * 这条分支要放在下面那个「未登录就只给登录页」之前，
   * 否则整条路径是死的：走「忘记密码」的人**恰恰是登不进去的那个人**，
   * 先要求他登录才能改密码，是一个他永远走不出去的环。
   */
  if (view === "reset") {
    return (
      <ResetView
        token={mailToken}
        onDone={() => {
          setMailToken(null);
          setView("list");
        }}
      />
    );
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

  /*
   * 验证邮箱必须已登录（后端要求令牌属于当前登录的人）。
   *
   * 做成整屏而不是塞进主布局：这是「从邮件点进来」的一个瞬间，
   * 不是使用拾链的过程——上面顶着录入框和筛选行，
   * 只会让人以为要先去干点什么才能验证完。
   */
  if (view === "verify") {
    return (
      <VerifyView
        token={mailToken}
        onDone={() => {
          setMailToken(null);
          setView("list");
        }}
        notify={notify}
        onUserChange={setUser}
      />
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
        isAdmin={isAdmin(user)}
        onAdmin={() => setView("admin")}
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
        tags={tags}
        activeTags={activeTags}
        toggleTag={toggleTag}
        onManageTags={() => setTagManagerOpen(true)}
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
              onMarkUsed={handleMarkUsed}
              onFix={openFix}
              fixedItem={reviewFixed}
              onExit={exitToList}
              notify={notify}
            />
          ) : view === "admin" ? (
            <AdminView myId={user.id} onExit={exitToList} notify={notify} />
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
                  <p className="flex-1 text-[12px] leading-relaxed text-ink2">
                    语义搜索没成功：{semanticError}
                  </p>
                  {/*
                    两个出口。主出口是「再试一次」——用户撞到失败的第一反应就是
                    再来一遍，而语义搜索的失败多半是上游抖动（向量服务超时），
                    重试经常就好了。次要出口是关掉语义退回关键词搜，
                    保留它是因为前者万一一直失败，用户总得有路可走。
                  */}
                  <div className="flex shrink-0 items-center gap-2 pt-[1px]">
                    <button
                      onClick={() => setSemanticRetry((n) => n + 1)}
                      className="rounded-md border border-line px-2 py-[3px] text-[11.5px] text-ink2 transition-colors hover:border-line-strong hover:text-ink"
                    >
                      再试一次
                    </button>
                    <button
                      onClick={() => setSemantic(false)}
                      className="text-[11.5px] text-ink3 transition-colors hover:text-ink"
                    >
                      关掉语义，用关键词搜
                    </button>
                  </div>
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
                        highlight={l.id === focusId}
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
        onGotoExisting={handleGotoExisting}
        onQuickSaved={handleQuickSaved}
        onNotify={notify}
      />

      {/*
        标签管理。做成独立面板而不是塞进筛选条：改名/合并/删除是**整理动作**，
        和「我现在想看什么」不是同一件事，混在一排 chips 里会被顺手点到。
      */}
      {tagManagerOpen && (
        <TagManager
          tags={tags}
          onClose={() => setTagManagerOpen(false)}
          onChanged={handleTagsChanged}
        />
      )}

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

import type { AnalyzeDraft, DomainKey, LinkItem, PurposeKey } from "./types";

const BASE = "/api";

/**
 * 统一的接口错误。
 *
 * status = 0 表示压根没连上后端（服务没起、端口不对）。
 * 这个区分很重要：用户最常犯的错就是忘了启动后端，
 * 而「请求失败」这种笼统提示会让他去查代码。
 *
 * 注意：status = 0 不只来自 fetch 抛错。开发环境下前端走 Vite 代理，
 * 后端没起时代理会返回一个 **502 的纯文本页**（`upstream connect failed`），
 * 这时候 status 是 502 而不是 0。所以判定「没连上」不能只看状态码，
 * 要看响应体是不是 JSON —— 我们自己的后端任何情况下都返回 JSON。
 * 这个坑是实测才发现的，写代码时想不到。
 */
export class ApiError extends Error {
  readonly status: number;
  /** 409 时带上已有记录的 id，前端可以直接跳过去 */
  readonly existingId?: string;

  constructor(status: number, message: string, existingId?: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.existingId = existingId;
  }

  get offline() {
    return this.status === 0;
  }
}

/** 后端连不上时统一用这个错误，别把 502/504 这种码丢给用户看。 */
const offlineError = () => new ApiError(0, "连不上后端服务");

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response;
  try {
    res = await fetch(BASE + path, {
      ...init,
      // 登录靠的是会话 cookie，每个请求都必须带上它。
      // 开发环境走 Vite 代理，同源，默认就会带；显式写出来是为了让
      // 「这个应用依赖 cookie」这件事在代码里看得见——
      // 哪天改成直连后端（跨域）时，这一行就是必须改的地方。
      credentials: "include",
      headers: init?.body ? { "Content-Type": "application/json" } : undefined,
    });
  } catch {
    throw offlineError();
  }

  if (res.status === 204) {
    return undefined as T;
  }

  const text = await res.text();
  let json: unknown = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    // 不是 JSON，说明这个响应不是后端发的（多半是代理的错误页）。
    // 下面按「连不上」处理。
  }

  if (!res.ok) {
    const body = json as { error?: string; existingId?: string } | null;
    if (body === null) {
      throw offlineError();
    }
    throw new ApiError(
      res.status,
      body.error ?? `请求失败（HTTP ${res.status}）`,
      body.existingId,
    );
  }

  if (json === null) {
    // 200 但不是 JSON，同样不可能是后端发的
    throw offlineError();
  }
  return json as T;
}

/* ────────────── 字典与健康 ────────────── */

export interface Meta {
  domains: { code: DomainKey; label: string }[];
  purposes: { code: PurposeKey; label: string }[];
  contentTypes: string[];
  aiConfigured: boolean;
  model: string;
  /**
   * 语义搜索的可用性。**和 aiConfigured 是两件事**：
   * 分析用 DeepSeek，而 DeepSeek 不提供 embeddings 接口，
   * 所以向量得来自另一套服务，两者会各自独立地可用或不可用。
   */
  semantic: SemanticInfo;
}

export const getMeta = () => request<Meta>("/meta");

export interface Health {
  /** ok 或 degraded。熔断打开时会是 degraded——那是「现在别用」，不是「没配好」 */
  status: string;
  aiConfigured: boolean;
  model: string;
  semanticAvailable: boolean;
  /** 熔断器是否打开（上游刚挂过，还在冷却） */
  circuitOpen: boolean;
  /** 剩余分析并发 / 总容量，比如 "2/2" */
  analysisSlots: string;
}

export const getHealth = () => request<Health>("/health");

/* ────────────── 分析 ────────────── */

export interface AnalyzeResponse {
  draftId: string;
  draft: AnalyzeDraft;
}

/**
 * 分析一个网址。**不落库** —— 返回草稿让用户先看一眼。
 * 这个请求要等抓网页 + 调模型，实测 3–30 秒，UI 必须给出进度感。
 *
 * @param text 可选。抓取失败时用户贴进来的正文。贴了就不再抓取——
 *   他之所以会贴正是因为抓取失败了，再等 20 秒去撞同一堵墙没有意义。
 */
export const analyze = (url: string, text?: string) =>
  request<AnalyzeResponse>("/analyze", {
    method: "POST",
    body: JSON.stringify(text?.trim() ? { url, text } : { url }),
  });

/* ────────────── 保存 ────────────── */

export interface SavePayload {
  draftId: string;
  title: string;
  summary: string;
  summaryLong: string | null;
  noteOptions: string[];
  note: string | null;
  domainKey: DomainKey;
  purposes: PurposeKey[];
  tags: string[];
  contentType: string;
  confidence: number;
  needsReview: boolean;
}

export const saveLink = (payload: SavePayload) =>
  request<LinkItem>("/links", { method: "POST", body: JSON.stringify(payload) });

/**
 * 跳过 AI，直接把网址存下来。**分析失败时的兜底出口。**
 *
 * <p>这是产品最核心的承诺：丢一个网址进来，最差也得能存住。熔断 / 没配 Key /
 * 超时这三种情况下分析一定失败，但那不该等于「这个网址就丢了」——先以「待补」
 * 落库，等模型恢复、或者用户拿到正文了再补。
 *
 * <p>不传 title 就是一条光秃秃的网址，列表里用 URL 当标题显示；
 * 用户手上恰好有标题时带一个，能让这条在列表里认得出。
 *
 * @param title 可选。用户手动给一个标题，避免存进去一条认不出来的记录。
 */
export const quickSave = (url: string, title?: string) =>
  request<LinkItem>("/links/quick", {
    method: "POST",
    body: JSON.stringify(title?.trim() ? { url, title } : { url }),
  });

/**
 * 给一条**已存**的记录重跑分析。**不改库**，返回草稿等用户确认。
 *
 * <p>补上了全应用最后一个「用户遇到问题却无解」的场景：之前只有新收藏时能贴正文，
 * 一张卡片存下来之后就没有补的入口了。
 *
 * @param text 可选。抓取失败时用户贴进来的正文。不传则重新抓一次——
 *   抓取失败有时只是暂时的，隔几天再试可能就好了。
 */
export const reanalyzeLink = (id: string, text?: string) =>
  request<AnalyzeResponse>(`/links/${id}/reanalyze`, {
    method: "POST",
    body: JSON.stringify(text?.trim() ? { text } : {}),
  });

/**
 * 把一次重分析的结果写到已存记录上。
 *
 * <p>请求体和 {@link saveLink} 完全一样（后端也复用了同一个 DTO）——
 * 用户在面板里能改的东西，新建和补正文时是一样的。
 * 区别只在服务端：这个走覆盖，而且**不碰**加星、已用、收藏时间。
 */
export const applyReanalysis = (id: string, payload: SavePayload) =>
  request<LinkItem>(`/links/${id}/apply`, { method: "POST", body: JSON.stringify(payload) });

/* ────────────── 列表与筛选 ────────────── */

export type SortKey = "recent" | "starred" | "stale";

export interface ListParams {
  domain?: DomainKey | "all";
  purposes?: PurposeKey[];
  q?: string;
  sort?: SortKey;
}

export function listLinks(params: ListParams = {}) {
  const sp = new URLSearchParams();
  if (params.domain && params.domain !== "all") sp.set("domain", params.domain);
  if (params.purposes?.length) sp.set("purposes", params.purposes.join(","));
  if (params.q?.trim()) sp.set("q", params.q.trim());
  if (params.sort) sp.set("sort", params.sort);
  const qs = sp.toString();
  return request<LinkItem[]>(`/links${qs ? `?${qs}` : ""}`);
}

/**
 * 「该回头看了」的阈值（天）。
 *
 * 前后端共用这个数字：后端 `/api/review` 的默认值是 7，
 * 前端的侧栏计数也用 7。两处写不同的数字会让「侧栏说有 3 条、
 * 点进去看到 5 条」这种对不上的情况出现。
 */
export const REVIEW_DAYS = 7;

/** 一次抽几张。后端默认也是 3，这里显式传是为了让「抽卡」的张数只有一处定义。 */
export const REVIEW_BATCH = 3;

export interface ReviewQueue {
  items: LinkItem[];
  /** 队列里一共多少条（不只是这一批） */
  dueTotal: number;
  days: number;
}

/**
 * 抽一批「该回头看了」。
 *
 * 服务端是**随机**抽的，不是取最旧的几条——理由见后端 `LinkRepository.dueForReview`：
 * 按时间排的队列如果最前面那几条恰好不感兴趣，队列就冻住了，
 * 天天看到同样几条，很快连这个功能都不点了。
 */
export const getReviewQueue = (days = REVIEW_DAYS, limit = REVIEW_BATCH) =>
  request<ReviewQueue>(`/review?days=${days}&limit=${limit}`);

/**
 * 队列里有多少条。侧栏用它显示计数。
 *
 * 刻意不在前端用全量列表自己过滤——那等于把后端的筛选条件
 * （排除星标/已用/已读，还要按闲置天数比较）抄第二遍，
 * 一旦漂移就会出现「侧栏说有 5 条、点进去只有 3 条」。
 */
export const getReviewCount = (days = REVIEW_DAYS) =>
  request<{ dueTotal: number; days: number }>(`/review/count?days=${days}`);

export interface DomainCount {
  domain: DomainKey;
  count: number;
}

/** 周报。摘要按周缓存，正常情况下 cached=true 且不花 token。 */
export interface WeeklyDigest {
  weekKey: string;
  /** 摘要直接读了缓存 */
  cached: boolean;
  /** false 表示摘要不是模型写的（空周本地生成，或调用失败降级） */
  aiWritten: boolean;
  /** AI 调用失败时的说明 */
  degraded: string | null;
  headline: string | null;
  body: string | null;
  observation: string | null;
  stats: {
    saved: number;
    opened: number;
    used: number;
    neverOpenedTotal: number;
    total: number;
    dueTotal: number;
    domains: DomainCount[];
    newLinks: LinkItem[];
  };
}

/** @param refresh 强制重写 AI 摘要（默认读本周缓存，不花 token） */
export const getWeeklyDigest = (refresh = false) =>
  request<WeeklyDigest>(`/review/weekly${refresh ? "?refresh=true" : ""}`);

export interface PatchPayload {
  title?: string;
  summaryShort?: string;
  note?: string;
  domainKey?: DomainKey;
  purposes?: PurposeKey[];
  tags?: string[];
  starred?: boolean;
  status?: "unread" | "read" | "used";
  markOpened?: boolean;
}

export const patchLink = (id: string, payload: PatchPayload) =>
  request<LinkItem>(`/links/${id}`, { method: "PATCH", body: JSON.stringify(payload) });

export const deleteLink = (id: string) =>
  request<void>(`/links/${id}`, { method: "DELETE" });

/* ────────────── 语义搜索 ────────────── */

/**
 * 语义搜索的可用性。
 *
 * <p>单独报而不是复用 `aiConfigured`：分析用 DeepSeek，而 DeepSeek
 * **不提供 embeddings 接口**，所以向量得来自另一套服务。两者会各自独立地
 * 可用或不可用，前端必须能如实显示「分析能用、语义搜索缺个 Key」这种状态。
 *
 * @param reason 不可用时的原因，一句能照着做的话
 */
export interface SemanticInfo {
  available: boolean;
  reason: string | null;
  model: string | null;
  minScore: number;
}

export interface SemanticHit {
  id: string;
  /** 余弦相似度，-1 到 1。越大越像 */
  score: number;
}

export interface SemanticResult {
  hits: SemanticHit[];
  /** true 表示「没有一条达到阈值，这几条是矮子里拔将军」 */
  fallback: boolean;
  /** 本次补算了几条向量。>0 说明这次慢在了补齐上 */
  computed: number;
  total: number;
  minScore: number;
  model: string;
}

/**
 * 语义搜索。
 *
 * <p>返回的是**「id → 相似度」的排序**，不是过滤后的列表。这样领域/用途筛选
 * 仍然由前端在本地做，两套逻辑正交，不会出现「服务端筛一遍、客户端再筛一遍」
 * 那种必然漂移的写法。
 *
 * <p>第一次搜索会慢一些：库里没算过向量的记录要在这一步批量补上。
 * `computed` 会告诉你补了几条，UI 可以据此解释这次的延迟。
 */
export const semanticSearch = (q: string) =>
  request<SemanticResult>(`/search/semantic?q=${encodeURIComponent(q)}`);

/* ────────────── 账号 ────────────── */

export interface AuthUser {
  id: string;
  username: string;
  nickname: string;
  email: string | null;
  displayName: string;
  /**
   * 角色。`"user"` 是默认，`"admin"` 才能进管理端。
   *
   * <p>写成可选而不是必填：管理端是后端这一轮才加的东西，
   * 而前端可能比后端先上线（或反过来）。取不到就当普通用户——
   * 宁可让管理员一时看不到入口，也不能让普通人看见一个自己进不去的入口。
   */
  role?: string;
}

/**
 * 判断一个人是不是管理员。**全应用只此一处定义。**
 *
 * 各处都写 `u.role === "admin"` 的话，将来加第二个有特权的角色时
 * 就要改好几个地方，漏一处就是一个越权口子。
 */
export const isAdmin = (u: AuthUser | null | undefined): boolean => u?.role === "admin";

/**
 * 取当前登录的人。没登录时后端返回 401。
 *
 * 这是启动时判断「要不要弹登录页」的唯一依据：
 * 不在前端存一个 isLoggedIn 标记——那个标记会和真实会话不同步，
 * 表现为「明明已经退出，界面还显示着我的名字」。
 */
export const getMe = () => request<AuthUser>("/auth/me");

export const login = (username: string, password: string) =>
  request<AuthUser>("/auth/login", {
    method: "POST",
    body: JSON.stringify({ username, password }),
  });

export interface RegisterPayload {
  username: string;
  email: string;
  password: string;
  nickname?: string;
}

/**
 * 注册。**注册不会自动登录**，返回后要再调一次 login。
 *
 * 这不是多余的绕路：注册和登录是两种不同的事，
 * 合并成一个「注册并登录」的接口，等于把「账号创建成功」
 * 和「会话建立成功」两个结果塞进一个响应当中——
 * 一旦后者失败（比如会话存储出问题），用户会以为注册失败了，
 * 于是再注册一次，撞上「用户名已存在」。
 */
export const register = (payload: RegisterPayload) =>
  request<AuthUser>("/auth/register", {
    method: "POST",
    body: JSON.stringify(payload),
  });

export const logout = () => request<void>("/auth/logout", { method: "POST" });

export const changePassword = (oldPassword: string, newPassword: string) =>
  request<void>("/auth/password", {
    method: "POST",
    body: JSON.stringify({ oldPassword, newPassword }),
  });

/* ────────────── 标签（R-05） ────────────── */

export interface TagItem {
  name: string;
  /** 有多少条记录带着这个标签 */
  count: number;
}

/**
 * 全部标签及其条数。**服务端算的**，不从本地 links 自己数。
 *
 * 标签的增删改是跨行读改写（一条 SQL 动几十行的 JSON 数组），
 * 只能由服务端做；既然管理走服务端，这份清单也从同一个地方来，
 * 免得「chips 上说 5 条、改完说动了 7 条」这种对不上。
 */
export const listTags = () => request<TagItem[]>("/tags");

/** 改名字 / 合并 / 删除的返回：这次动了多少条记录 */
export interface TagMutationResult {
  affected: number;
}

/**
 * 给标签改名。
 *
 * 服务端保证目标名不存在时才改（撞上已有名字会报错）——前端这里也拦一道，
 * 因为「合并」和「改名撞名」是两种用户意图，不该由一个错误码去猜。
 */
export const renameTag = (from: string, to: string) =>
  request<TagMutationResult>("/tags/rename", {
    method: "POST",
    body: JSON.stringify({ from, to }),
  });

/**
 * 把几个标签并成一个。
 *
 * 服务端去重后再写回，所以「合并」不会产生重复标签——这也是它必须走服务端的原因：
 * 前端一条条 PATCH 过去，中途失败一半就会留下一半改了、一半没改的数据。
 */
export const mergeTags = (sources: string[], target: string) =>
  request<TagMutationResult>("/tags/merge", {
    method: "POST",
    body: JSON.stringify({ sources, target }),
  });

/** 删掉一个标签。只摘标签，不动记录本身。 */
export const deleteTag = (name: string) =>
  request<TagMutationResult>("/tags/delete", {
    method: "POST",
    body: JSON.stringify({ name }),
  });

/* ────────────── 导出（R-07） ────────────── */

export type ExportFormat = "json" | "html";

const EXPORT_EXT: Record<ExportFormat, string> = { json: "json", html: "html" };

/**
 * 从 `Content-Disposition` 里取文件名。
 *
 * 两种写法都要认：`filename="x.json"` 和 RFC 5987 的 `filename*=UTF-8''%E6%8B%BE...`。
 * 中文文件名只能靠后者带过来，漏掉它的话用户下载下来会是一串百分号。
 * 取不到就返回 null，由调用方兜一个本地生成的名字。
 */
function filenameFromDisposition(header: string | null): string | null {
  if (!header) return null;
  const star = /filename\*=UTF-8''([^;]+)/i.exec(header);
  if (star?.[1]) {
    try {
      return decodeURIComponent(star[1]);
    } catch {
      // 解码失败就往下走，试普通的 filename=
    }
  }
  const plain = /filename="([^"]+)"/i.exec(header) ?? /filename=([^;]+)/i.exec(header);
  const name = plain?.[1]?.trim();
  return name ? name : null;
}

/** 本地兜底文件名用的时间戳，形如 20260918-2130 */
function stamp(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}`;
}

/**
 * 导出并下载。**走 blob，不是 `window.open`。**
 *
 * <p>原因是这个请求必须带会话 cookie：`window.open` 打开的是一个浏览器级的新导航，
 * 未登录时会拿到一个登录页的 HTML 并把它当成文件内容存下来——用户拿到一个
 * 打不开的「导出文件」，还以为导出坏了。走 fetch 才能读到 401/404 并如实报出来。
 *
 * <p>整个响应先在内存里成一个 blob 再交给 `<a download>`，
 * 所以几百条的量级没有问题；真到了几万条要改成服务端生成 + 预签名下载。
 *
 * @returns 最终存下来的文件名，给调用方提示用户「存到哪了」
 */
export async function downloadExport(format: ExportFormat): Promise<string> {
  let res: Response;
  try {
    res = await fetch(`${BASE}/export?format=${format}`, { credentials: "include" });
  } catch {
    throw offlineError();
  }

  if (!res.ok) {
    // 401 = 会话过期；404 = 后端对非管理员/越权的约定返回。
    // 这两种都不是「导出功能坏了」，得说清楚是哪一种，否则用户会去重启服务。
    throw new ApiError(
      res.status,
      res.status === 401
        ? "登录状态已过期，请重新登录后再导出"
        : res.status === 404
          ? "没有权限导出"
          : `导出失败（HTTP ${res.status}）`,
    );
  }

  const blob = await res.blob();
  const filename =
    filenameFromDisposition(res.headers.get("Content-Disposition")) ??
    `拾链-${stamp()}.${EXPORT_EXT[format]}`;

  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  // 不在文档里的 <a> 在部分浏览器上点不动，所以先挂上去再点
  document.body.appendChild(a);
  a.click();
  a.remove();
  /*
   * 必须释放，否则这个 blob 会一直占着内存到刷新为止。
   * 但不能紧接着同步 revoke —— 下载还没真正开始就把 URL 掐断的话，
   * 有的浏览器会存出一个 0 字节的文件。让到下一个 tick 再释放。
   */
  setTimeout(() => URL.revokeObjectURL(url), 0);

  return filename;
}

/* ────────────── 管理端（R-19 / R-20） ────────────── */

/** 分页结果的统一形状。管理端的两个列表都是它。 */
export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

export type UserStatus = "active" | "disabled";

export interface AdminUser {
  id: string;
  username: string;
  /** 昵称。选填——没填就是 null，那时候界面上退回显示用户名 */
  nickname: string | null;
  email: string | null;
  role: string;
  status: UserStatus;
  /** 注册时间。后端可能给 null（老账号没记） */
  createdAt: string | null;
  /** 最后登录。同上，从来没登录过就是 null */
  lastLoginAt: string | null;
  linkCount: number;
  /** AI 调用次数（R-15 入账之后才有意义） */
  aiCalls: number;
  /**
   * AI 用量。**分开存，不存合计**：prompt 是输入成本、completion 是输出成本，
   * 两者单价不同，合成一个数就看不出钱花在哪一边。
   * 界面要「总量」时自己相加，见 {@link AdminView} 的 tokens 列。
   */
  promptTokens: number;
  completionTokens: number;
}

/**
 * 用户列表。**非管理员会拿到 404**（沿用全站约定：403 等于确认这个资源存在，
 * 而「有没有管理端」这件事不该向普通人确认）。
 */
export const listAdminUsers = (q: string, page: number, size: number) =>
  request<Page<AdminUser>>(
    `/admin/users?q=${encodeURIComponent(q)}&page=${page}&size=${size}`,
  );

/** 禁用 / 恢复一个账号。返回改动后的用户，前端可以直接回填那一行。 */
export const setUserStatus = (id: string, status: UserStatus) =>
  request<AdminUser>(`/admin/users/${id}`, {
    method: "PATCH",
    body: JSON.stringify({ status }),
  });

export interface AuditEntry {
  id: string;
  /** 没登录的人（比如登录失败）没有 userId */
  userId: string | null;
  username: string | null;
  /** 英文枚举，见 {@link AUDIT_ACTIONS} */
  action: string;
  /** 动作的对象，比如被删的链接 id */
  target: string | null;
  /**
   * 这次操作成了没有：success / failure / denied。
   *
   * 和 {@link detail} 是两个维度，不是一个东西的两版命名：
   * result 回答「成了没有」，detail 回答「为什么」。
   * 混进 detail 里以后「把所有失败的记录筛出来」就没法做了——
   * 只能拿文本去 LIKE，而失败原因的措辞是会变的。
   */
  result: string | null;
  /** 一句话补充信息（比如失败原因）。没有就是 null */
  detail: string | null;
  ip: string | null;
  createdAt: string;
}

/**
 * 审计动作的中英文名对照。
 *
 * 存的是英文枚举（稳定、好查），**给用户看的一律走 {@link auditActionName} 翻译成中文**——
 * 直接把 `LOGIN_OK` 摆在界面上等于没记。
 *
 * <p>这张表只放**后端真的会记**的动作（R-20 的审计范围：登录成功 / 失败、改密、
 * 删链接、禁用 / 恢复账号、越权尝试；密码重置 R-08 还没做，所以也不在里面）。
 *
 * 不要往里加「以后可能会记」的动作：下拉里多一个选项，等于向看这张表的人
 * 声明后端在记这件事——而后端并没有。他想查「谁注册了」时会来这里找，找不到，
 * 再回头翻一遍代码才知道这张表在骗人。一张写着「有」但实际没有的表，比没有更糟。
 *
 * <p>认不出的动作会原样返回，不会显示成空白——后端以后加新动作时，
 * 界面至少还能看出发生了什么。
 */
export const AUDIT_ACTIONS: Record<string, string> = {
  LOGIN_OK: "登录成功",
  LOGIN_FAIL: "登录失败",
  PASSWORD_CHANGE: "改密码",
  LINK_DELETE: "删链接",
  ADMIN_DISABLE: "禁用账号",
  ADMIN_ENABLE: "恢复账号",
  ADMIN_ACCESS_DENIED: "越权访问管理端",
};

/** 审计动作 → 中文。认不出就原样返回，绝不显示空白。 */
export const auditActionName = (action: string): string =>
  AUDIT_ACTIONS[action] ?? action;

/**
 * 审计结果的取值 → 中文。
 *
 * 只有后端 {@code AuditService} 里真的会写的那三个值，和动作表一个道理：
 * 不预填「以后可能会有」的结果。
 */
export const AUDIT_RESULTS: Record<string, string> = {
  success: "成功",
  failure: "失败",
  denied: "被拒",
};

/** 审计结果 → 中文。认不出（含 null / 空）就原样返回，绝不显示空白。 */
export const auditResultName = (result: string | null): string =>
  (result && AUDIT_RESULTS[result]) || result || "";

/**
 * 审计日志。同样：非管理员 404。
 *
 * @param result 按结果筛，取值见 {@link AUDIT_RESULTS}；空串表示不筛。
 *
 * <p>结果之所以是个独立的筛选维度，而不是「在详情里搜失败两个字」：
 * 翻这张表的人十有八九在找「哪次没成」——谁在反复试密码、谁被拦在管理端外面。
 * 只能按动作筛的话，想看失败得把每个动作都翻一遍，那恰好是最费眼睛的做法。
 * 而拿详情的文案去 LIKE 匹配撑不住：失败原因的措辞是会改的。
 */
export const listAudit = (
  userId: string,
  action: string,
  result: string,
  page: number,
  size: number,
) => {
  const sp = new URLSearchParams({ page: String(page), size: String(size) });
  if (userId) sp.set("userId", userId);
  if (action) sp.set("action", action);
  if (result) sp.set("result", result);
  return request<Page<AuditEntry>>(`/admin/audit?${sp.toString()}`);
};


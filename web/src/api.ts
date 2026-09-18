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
}

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


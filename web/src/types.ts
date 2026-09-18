export type DomainKey =
  | "ai" | "dev" | "design" | "product" | "biz"
  | "learn" | "data" | "efficiency" | "life" | "other";

/**
 * 用途分类。
 *
 * <p>刻意<b>不包含</b>「已用」——从 V4 起它是 {@code LinkItem.used} 这个独立开关，
 * 不再是用途的一个选项。
 *
 * 原来混在用途里的后果不只是概念乱：它让「已用」变成单向门。
 * 用途是 AI 判的、用户可改的一组标签，而「已用」是用户自己的使用状态，
 * 两者混在一起时，取消已用只能二选一地猜该回哪个状态。
 */
export type PurposeKey =
  | "tool" | "tutorial" | "inspiration" | "news"
  | "docs" | "asset" | "toread";

export interface DomainDef {
  key: DomainKey;
  name: string;
  color: string;
}

export interface PurposeDef {
  key: PurposeKey;
  name: string;
}

export interface LinkItem {
  id: string;
  url: string;
  site: string;
  title: string;
  summary: string;
  summaryLong?: string | null;
  note: string;
  noteOptions: string[];
  domainKey: DomainKey;
  purposes: PurposeKey[];
  tags: string[];
  contentType: string;
  savedLabel: string;
  idleDays: number | null;
  starred: boolean;
  /**
   * 看过了没有 / 还要不要再推给我。只有两个取值。
   *
   * <p>V4 之前这里还有第三个值 "used"，那正是「已用」不可撤销的根源：
   * 标已用会把 status 写成 used，取消时不知道该回 unread 还是 read——
   * 回到 unread，一条他早就标过「别再推」的记录会重新冒回顾队列；
   * 保留 read，一条从没看过的记录被永久排除。库里已经丢了这个信息。
   */
  status: "unread" | "read";
  /**
   * 用没用上。和 {@code status} 是两件事，来回拨都不影响对方。
   *
   * <p>这个字段的存在就是 R-03 的全部意义：它以前挤在 status 里，
   * 于是「标错了想撤」这件事在数据上根本做不到。
   */
  used: boolean;
  confidence: number;
  needsReview?: boolean;
  /**
   * 分析进行到哪一步。`"pending"` 从没分析过，`"done"` 分析过了。
   *
   * <p>它的用处是区分两种「待补」：pending 是从没跑过分析（跳过 AI 直接存的），
   * done + needsReview 是跑过了但没抓到正文。补救动作一样，原因不同——
   * 界面上说错原因，用户会以为系统在瞎判断。
   */
  analyzeStatus?: string | null;
  monogram: string;
  createdAt?: string;
  lastOpenedAt?: string | null;
}

/**
 * 后端 `/api/analyze` 返回的分析草稿。
 *
 * 服务端已经保证 domainKey / purposes / contentType 是合法枚举值
 * （校验没过的结果会在返回前被兜底修正），所以这里可以放心用强类型。
 * 抓取与调用的实况字段（fetchOk / repairLog / token 用量）也一并带过来，
 * 目的是让用户在等待时能看到「到底卡在哪一步」，而不是干等一个转圈。
 */
export interface AnalyzeDraft {
  url: string;
  site: string;
  monogram: string;
  title: string;
  summary: string | null;
  summaryLong: string | null;
  noteOptions: string[];
  domainKey: DomainKey;
  purposes: PurposeKey[];
  tags: string[];
  contentType: string;
  confidence: number;
  needsReview: boolean;

  fetchOk: boolean;
  fetchError: string | null;
  bodyChars: number;
  metaDescription: string | null;
  /**
   * 正文来源：`"fetched"`（服务端自己抓的）或 `"pasted"`（用户贴的）。
   *
   * 必须和 fetchOk 一起看，因为两种情况「缺什么」是相反的：
   * 抓取失败缺的是正文，用户贴的则缺标题和描述。
   * 贴正文时 fetchOk 也是 true —— 它表示「拿到了可用正文」，不是「抓取成功了」。
   */
  bodySource: "fetched" | "pasted";

  attempts: number;
  validationErrors: string[];
  repairLog: string[];
  promptTokens: number;
  completionTokens: number;
  elapsedMs: number;
}

export const DOMAINS: DomainDef[] = [
  { key: "ai",         name: "AI · 机器学习", color: "indigo" },
  { key: "dev",        name: "开发工具",      color: "blue" },
  { key: "design",     name: "设计",          color: "pink" },
  { key: "product",    name: "产品",          color: "amber" },
  { key: "biz",        name: "商业 · 行业",   color: "teal" },
  { key: "learn",      name: "学习教程",      color: "green" },
  { key: "data",       name: "数据",          color: "violet" },
  { key: "efficiency", name: "效率",          color: "cyan" },
  { key: "life",       name: "生活",          color: "orange" },
  { key: "other",      name: "其他",          color: "gray" },
];

/**
 * 用途选项。没有「已用」——它是独立的开关，见 {@code PurposeKey} 的注释。
 *
 * <p>顺带修掉一个一直存在但没人发现的坏东西：这个列表以前有 "used"，
 * 于是顶栏和侧栏会渲染出一个「已用」筛选 chip。但服务端
 * {@code sanitizePurposes} 按 {@code Purpose.isValid()} 过滤，
 * "used" 从来不是合法用途，所以 {@code item.purposes} 里永远不会有它——
 * 那个 chip 点下去永远是 0 条。删掉它反而是在删一个坏掉的入口。
 */
export const PURPOSES: PurposeDef[] = [
  { key: "tool",        name: "工具" },
  { key: "tutorial",    name: "教程" },
  { key: "inspiration", name: "灵感" },
  { key: "news",        name: "资讯" },
  { key: "docs",        name: "文档" },
  { key: "asset",       name: "素材" },
  { key: "toread",      name: "待读" },
];

export const domainOf = (k: DomainKey) =>
  DOMAINS.find((d) => d.key === k) ?? DOMAINS[DOMAINS.length - 1];

export const purposeName = (k: PurposeKey) =>
  PURPOSES.find((p) => p.key === k)?.name ?? k;

export const tint = (color: string) => `var(--d-${color}-tint)`;
export const deep = (color: string) => `var(--d-${color}-deep)`;

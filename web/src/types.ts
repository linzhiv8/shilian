export type DomainKey =
  | "ai" | "dev" | "design" | "product" | "biz"
  | "learn" | "data" | "efficiency" | "life" | "other";

export type PurposeKey =
  | "tool" | "tutorial" | "inspiration" | "news"
  | "docs" | "asset" | "toread" | "used";

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
  status: "unread" | "read" | "used";
  confidence: number;
  needsReview?: boolean;
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

export const PURPOSES: PurposeDef[] = [
  { key: "tool",        name: "工具" },
  { key: "tutorial",    name: "教程" },
  { key: "inspiration", name: "灵感" },
  { key: "news",        name: "资讯" },
  { key: "docs",        name: "文档" },
  { key: "asset",       name: "素材" },
  { key: "toread",      name: "待读" },
  { key: "used",        name: "已用" },
];

export const domainOf = (k: DomainKey) =>
  DOMAINS.find((d) => d.key === k) ?? DOMAINS[DOMAINS.length - 1];

export const purposeName = (k: PurposeKey) =>
  PURPOSES.find((p) => p.key === k)?.name ?? k;

export const tint = (color: string) => `var(--d-${color}-tint)`;
export const deep = (color: string) => `var(--d-${color}-deep)`;

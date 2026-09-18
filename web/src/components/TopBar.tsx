import { useEffect, useRef, useState } from "react";
import {
  Search, Sparkles, Loader2, RefreshCw, Moon, Sun, Clock3, BarChart3,
  AlertTriangle, LogOut, Download, Tags, Shield, MoreHorizontal,
} from "lucide-react";
import { cn } from "../lib/utils";
import {
  ApiError, changePassword, downloadExport, sendEmailVerification,
  type AuthUser, type ExportFormat, type TagItem,
} from "../api";
import {
  DOMAINS, PURPOSES, deep,
  type DomainKey, type PurposeKey,
} from "../types";

export type SortKey = "recent" | "starred" | "stale";

const SORTS: { key: SortKey; label: string }[] = [
  { key: "recent", label: "最近收藏" },
  { key: "starred", label: "星标优先" },
  { key: "stale", label: "最久未看" },
];

/**
 * 标签 chips 一次显示几个，其余收进「更多」。
 *
 * 不做搜索框：标签是**点选**而不是打字的东西——他的目的是「把我记得的那个
 * 标签找出来点上」，而搜标签要他先想出准确的名字，把点选变成了打字。
 * 分频次排序 + 一个「更多」已经够，常用的那几个永远在第一排。
 */
const TAG_TOP_N = 12;

const EXPORT_OPTIONS: { format: ExportFormat; label: string; hint: string }[] = [
  { format: "json", label: "JSON", hint: "完整备份，能再导回拾链" },
  { format: "html", label: "浏览器书签", hint: "能直接导入 Chrome / Edge" },
];

interface Props {
  /* 录入 */
  onSubmitUrl: (url: string) => void;

  /* 品牌行 */
  total: number;
  reviewCount: number;
  view: "list" | "review" | "weekly" | "admin";
  onReview: () => void;
  onWeekly: () => void;
  /** 只有管理员看得到这个入口（传 false 就是不给渲染） */
  isAdmin: boolean;
  onAdmin: () => void;
  theme: "light" | "dark";
  toggleTheme: () => void;
  aiReady: boolean;

  /* 筛选与搜索，只有列表视图用得上 */
  query: string;
  setQuery: (v: string) => void;
  semantic: boolean;
  setSemantic: (v: boolean) => void;
  semanticAvailable: boolean;
  semanticReason: string | null;
  semanticLoading: boolean;
  sort: SortKey;
  setSort: (v: SortKey) => void;
  domainCounts: Record<string, number>;
  purposeCounts: Record<string, number>;
  activeDomain: DomainKey | "all";
  setActiveDomain: (k: DomainKey | "all") => void;
  activePurposes: PurposeKey[];
  togglePurpose: (k: PurposeKey) => void;
  /** 全部标签（服务端给的清单，带条数） */
  tags: TagItem[];
  activeTags: string[];
  toggleTag: (name: string) => void;
  /** 打开标签管理（改名 / 合并 / 删除） */
  onManageTags: () => void;
  onRefresh: () => void;
  refreshing: boolean;
  /** 筛选后的条数 */
  count: number;

  /* 账号 */
  user: AuthUser;
  onLogout: () => void;
  notify: (msg: string, kind?: "ok" | "warn") => void;
}

/**
 * 顶栏。
 *
 * 砍掉侧栏之后，原来侧栏的活儿全搬到这里了：录入、领域筛选、用途筛选、
 * 回顾与周报入口、主题切换。所以它比一般的顶栏高——这是有意的代价。
 *
 * 换来的是内容区全宽、不再有「左中右」三块底色互相打架。
 * 分层只剩一条：canvas（纸）→ sunken（浅凹）→ surface（卡片）。
 */
export default function TopBar({
  onSubmitUrl, total, reviewCount, view, onReview, onWeekly, isAdmin, onAdmin,
  theme, toggleTheme, aiReady,
  query, setQuery, semantic, setSemantic, semanticAvailable, semanticReason, semanticLoading,
  sort, setSort, domainCounts, purposeCounts, activeDomain, setActiveDomain,
  activePurposes, togglePurpose, tags, activeTags, toggleTag, onManageTags,
  onRefresh, refreshing, count,
  user, onLogout, notify,
}: Props) {
  const [url, setUrl] = useState("");
  /** 标签 chips 是否展开显示全部（默认只显示 Top N） */
  const [tagsExpanded, setTagsExpanded] = useState(false);

  /*
   * Top N 按条数从多到少取，名字只是同分时的稳定次序。
   * 服务端给的顺序不假设——它可能按名字排，也可能按别的。
   */
  const sortedTags = [...tags].sort((a, b) => b.count - a.count || a.name.localeCompare(b.name));
  const shownTags = tagsExpanded ? sortedTags : sortedTags.slice(0, TAG_TOP_N);

  const submit = () => {
    if (!url.trim()) return;
    onSubmitUrl(url.trim());
    setUrl("");
  };

  /*
   * 语义模式下排序失效：结果是按相似度排的，再按收藏时间排一遍
   * 等于把刚算出来的顺序丢掉。这里不藏掉排序，而是禁用并说明原因——
   * 藏掉会让人以为排序坏了。
   */
  const sortMuted = semantic && query.trim().length > 0;

  return (
    <header className="sticky top-0 z-20 border-b border-line bg-canvas/88 backdrop-blur-md">
      <div className="mx-auto w-full max-w-[880px] px-10 pb-3.5 pt-[18px]">

        {/* ── 品牌行 ── */}
        <div className="flex flex-wrap items-baseline gap-3.5">
          <span className="font-serif text-[21px] tracking-[-0.01em] text-ink">拾链</span>
          <span className="font-serif text-[11.5px] text-ink3">
            {total} 条收藏
            {reviewCount > 0 && ` · ${reviewCount} 条该回头看`}
          </span>

          {!aiReady && (
            <span
              className="flex items-center gap-1 text-[11px] text-amber-700"
              title="在 server/.env.properties 里填 DEEPSEEK_API_KEY（server/ 和 web/ 平级）"
            >
              <AlertTriangle size={11} />
              没配 API Key
            </span>
          )}

          <div className="ml-auto flex items-center gap-1.5">
            <NavBtn active={view === "review"} onClick={onReview}>
              <Clock3 size={11.5} />
              回顾{reviewCount > 0 && ` ${reviewCount}`}
            </NavBtn>
            <NavBtn active={view === "weekly"} onClick={onWeekly}>
              <BarChart3 size={11.5} />
              周报
            </NavBtn>
            {/*
              管理端入口只在管理员面前出现。
              给普通人看到一个点进去必然 404 的按钮，等于反复提醒他「你权限不够」——
              那是界面在无缘无故地羞辱人。
            */}
            {isAdmin && (
              <NavBtn active={view === "admin"} onClick={onAdmin}>
                <Shield size={11.5} />
                管理
              </NavBtn>
            )}
            <UserMenu user={user} onLogout={onLogout} notify={notify} />

            <button
              onClick={toggleTheme}
              title="切换深浅色"
              className="grid h-[26px] w-[26px] place-items-center rounded-full text-ink3 transition-colors hover:bg-sunken hover:text-ink"
            >
              {theme === "light" ? <Moon size={12.5} /> : <Sun size={12.5} />}
            </button>
          </div>
        </div>

        {/*
          录入框。整个界面最重要的一个控件，给它最大的分量。
          它在所有视图里都在——看到好东西随时能存，不用先切回列表。
        */}
        <div className="mt-4 flex items-center gap-2.5 rounded-[10px] border border-line bg-surface px-3.5 transition-all focus-within:border-accent focus-within:ring-[3px] focus-within:ring-accent/12">
          <input
            value={url}
            onChange={(e) => setUrl(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && submit()}
            placeholder="粘贴网址，回车即存 — AI 会补完标题、摘要、备注和分类"
            className="flex-1 bg-transparent py-[11px] text-[13.5px] text-ink outline-none placeholder:text-ink3"
          />
          <kbd className="shrink-0 rounded border border-line px-1.5 py-px font-sans text-[10.5px] text-ink3">
            ↵
          </kbd>
        </div>

        {/* ── 筛选：只在列表视图出现 ── */}
        {view === "list" && (
          <div className="mt-3.5 flex flex-col gap-2.5">
            <div className="flex flex-wrap items-center gap-1.5">
              <span className="w-[34px] shrink-0 text-[10.5px] uppercase tracking-[0.1em] text-ink3">
                领域
              </span>
              <Chip on={activeDomain === "all"} onClick={() => setActiveDomain("all")}>
                全部<N>{total}</N>
              </Chip>
              {DOMAINS.map((d) => {
                const n = domainCounts[d.key] ?? 0;
                const on = activeDomain === d.key;
                return (
                  <Chip
                    key={d.key}
                    on={on}
                    disabled={n === 0}
                    onClick={() => setActiveDomain(on ? "all" : d.key)}
                  >
                    <span
                      className="h-[6px] w-[6px] shrink-0 rounded-full"
                      style={{ background: deep(d.color) }}
                    />
                    {d.name}
                    <N>{n}</N>
                  </Chip>
                );
              })}

              <div className="ml-auto flex items-center gap-2">
                <div className="relative">
                  <Search
                    size={12.5}
                    className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-ink3"
                  />
                  <input
                    type="text"
                    value={query}
                    onChange={(e) => setQuery(e.target.value)}
                    placeholder={semantic ? "说说你想找什么" : "搜索"}
                    className="w-[168px] rounded-full border border-line bg-transparent py-[5px] pl-[26px] pr-3 text-[12px] text-ink outline-none transition-colors placeholder:text-ink3 focus:border-ink"
                  />
                </div>

                <button
                  disabled={!semanticAvailable}
                  onClick={() => setSemantic(!semantic)}
                  title={
                    semanticAvailable
                      ? semantic
                        ? "语义搜索已开启：按意思找，不用记关键词。再点一下关掉"
                        : "按意思找，不用记关键词"
                      : semanticReason ?? "语义搜索不可用"
                  }
                  className={cn(
                    "flex h-[27px] items-center gap-1 rounded-full border px-2 text-[11px] transition-colors",
                    !semanticAvailable
                      ? "cursor-not-allowed border-line text-ink3/50"
                      : semantic
                        ? "border-accent bg-accentsoft text-accent"
                        : "border-line text-ink2 hover:border-line-strong hover:text-ink",
                  )}
                >
                  {semanticLoading ? (
                    <Loader2 size={11} className="animate-spin" />
                  ) : (
                    <Sparkles size={11} />
                  )}
                  语义
                </button>

                <select
                  value={sort}
                  disabled={sortMuted}
                  onChange={(e) => setSort(e.target.value as SortKey)}
                  title={sortMuted ? "语义搜索时结果按相似度排，排序暂时不起作用" : undefined}
                  className={cn(
                    "cursor-pointer rounded-full border border-line bg-transparent px-2.5 py-[5px] text-[11.5px] text-ink2 outline-none transition-colors hover:border-line-strong",
                    sortMuted && "cursor-not-allowed opacity-45",
                  )}
                >
                  {SORTS.map((s) => (
                    <option key={s.key} value={s.key} className="bg-surface text-ink">
                      {s.label}
                    </option>
                  ))}
                </select>
              </div>
            </div>

            <div className="flex flex-wrap items-center gap-1.5">
              <span className="w-[34px] shrink-0 text-[10.5px] uppercase tracking-[0.1em] text-ink3">
                用途
              </span>
              {PURPOSES.map((p) => {
                const n = purposeCounts[p.key] ?? 0;
                const on = activePurposes.includes(p.key);
                return (
                  <Chip key={p.key} on={on} disabled={n === 0} onClick={() => togglePurpose(p.key)}>
                    {p.name}
                    <N>{n}</N>
                  </Chip>
                );
              })}

              <div className="ml-auto flex items-center gap-2">
                <span className="font-serif text-[11.5px] tabular-nums text-ink3">{count} 条</span>
                <ExportMenu notify={notify} />
                <button
                  onClick={onRefresh}
                  disabled={refreshing}
                  title="重新拉取列表"
                  className={cn(
                    "grid h-[24px] w-[24px] place-items-center rounded-full text-ink3 transition-colors hover:bg-sunken hover:text-ink",
                    refreshing && "cursor-wait opacity-60",
                  )}
                >
                  <RefreshCw size={12} className={refreshing ? "animate-spin" : ""} />
                </button>
              </div>
            </div>

            {/* ── 标签：和领域、用途同一套多选语义（勾几个就是「都要有」） ── */}
            {sortedTags.length > 0 && (
              <div className="flex flex-wrap items-center gap-1.5">
                <span className="w-[34px] shrink-0 text-[10.5px] uppercase tracking-[0.1em] text-ink3">
                  标签
                </span>
                {shownTags.map((t) => (
                  <Chip
                    key={t.name}
                    on={activeTags.includes(t.name)}
                    onClick={() => toggleTag(t.name)}
                  >
                    #{t.name}
                    <N>{t.count}</N>
                  </Chip>
                ))}

                {sortedTags.length > TAG_TOP_N && (
                  <button
                    onClick={() => setTagsExpanded(!tagsExpanded)}
                    className="inline-flex shrink-0 items-center gap-0.5 rounded-full px-1.5 py-[3.5px] text-[11.5px] text-ink3 transition-colors hover:bg-sunken hover:text-ink"
                  >
                    {tagsExpanded ? (
                      "收起"
                    ) : (
                      <>
                        <MoreHorizontal size={12} />
                        更多 {sortedTags.length - TAG_TOP_N}
                      </>
                    )}
                  </button>
                )}

                {/*
                  「管理标签」放在这一排的最右：它和这些 chips 是同一件事的两面
                  （看得到才能筛，筛不动了才要改），放一起比藏进账号菜单好找。
                */}
                <button
                  onClick={onManageTags}
                  className="ml-auto inline-flex shrink-0 items-center gap-1 rounded-full border border-line px-2.5 py-[3.5px] text-[11.5px] text-ink2 transition-colors hover:border-line-strong hover:text-ink"
                >
                  <Tags size={11.5} />
                  管理标签
                </button>
              </div>
            )}
          </div>
        )}
      </div>
    </header>
  );
}

/* ────────────── 小零件 ────────────── */

/**
 * 导出菜单（R-07）。
 *
 * <p>放在「N 条 + 刷新」这一组里：这两个都是**对整个列表做的事**，
 * 导出也是。放进账号菜单会让它看起来像账号设置，而它其实是数据的事。
 *
 * <p>两种格式都给，因为它们服务的是两种完全不同的目的：
 * JSON 是备份（能再导回来），浏览器书签 HTML 是**搬家**（导进 Chrome/Edge 的书签栏）。
 * 只给一种等于替用户决定他为什么导出。
 */
function ExportMenu({
  notify,
}: {
  notify: (msg: string, kind?: "ok" | "warn") => void;
}) {
  const [open, setOpen] = useState(false);
  /** 正在导出的格式。null 表示空闲——用它而不是布尔值，好让转圈只出现在被点的那一项上 */
  const [busy, setBusy] = useState<ExportFormat | null>(null);
  const boxRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (boxRef.current && !boxRef.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onDown);
    return () => document.removeEventListener("mousedown", onDown);
  }, [open]);

  const run = async (format: ExportFormat) => {
    setBusy(format);
    try {
      const name = await downloadExport(format);
      // 把文件名说出来。不然用户不知道存到哪了，尤其是浏览器设了「每次询问保存位置」的时候
      notify(`已导出 ${name}`);
      setOpen(false);
    } catch (e) {
      // 失败不关菜单：他要能立刻再点一次，而不是重新打开一遍
      notify((e as ApiError).message, "warn");
    } finally {
      setBusy(null);
    }
  };

  return (
    <div ref={boxRef} className="relative">
      <button
        onClick={() => setOpen(!open)}
        title="导出全部收藏"
        className={cn(
          "flex h-[24px] items-center gap-1 rounded-full border px-2 text-[11.5px] transition-colors",
          open
            ? "border-ink bg-ink text-canvas"
            : "border-line text-ink2 hover:border-line-strong hover:text-ink",
        )}
      >
        <Download size={11.5} />
        导出
      </button>

      {open && (
        <div className="absolute right-0 top-full z-30 mt-1.5 w-[218px] rounded-lg border border-line bg-surface p-1.5 shadow-[0_12px_32px_-12px_rgba(0,0,0,0.22)]">
          {EXPORT_OPTIONS.map((o) => (
            <button
              key={o.format}
              disabled={busy !== null}
              onClick={() => void run(o.format)}
              className={cn(
                "w-full rounded-md px-2 py-[6px] text-left transition-colors hover:bg-sunken",
                busy !== null && "cursor-wait opacity-55 hover:bg-transparent",
              )}
            >
              <span className="flex items-center gap-1.5 text-[12px] text-ink">
                {busy === o.format && <Loader2 size={11.5} className="animate-spin" />}
                {o.label}
              </span>
              <span className="mt-px block text-[11px] leading-snug text-ink3">{o.hint}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function Chip({
  on, onClick, disabled, children,
}: {
  on: boolean;
  onClick: () => void;
  disabled?: boolean;
  children: React.ReactNode;
}) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      className={cn(
        "inline-flex shrink-0 items-center gap-1.5 rounded-full border px-2.5 py-[3.5px] text-[12px] transition-colors",
        /*
          选中态用 ink 而不是 accent：紫色是这一屏唯一的冷色，
          一排十几个 chip 都上紫色，整个筛选条会被拉回「工具界面」的观感。
        */
        on
          ? "border-ink bg-ink text-canvas"
          : "border-transparent text-ink2 hover:bg-sunken hover:text-ink",
        disabled && "cursor-default opacity-35 hover:bg-transparent",
      )}
    >
      {children}
    </button>
  );
}

/**
 * 账号菜单：改密码 + 装书签 + 退出。
 *
 * <p><b>改密码放在这里而不是单独一页。</b>
 * 它是一年用一次的操作，为它开一个页面会让「设置」变成
 * 一个只有一项的列表——用户点进去还要再点一次。
 * 收在这个小面板里，一次点击就能看到全部。
 *
 * <p><b>「装书签」也收在这里，和改密码同一层级。</b>
 * 它同样是「配置一次、之后很久不用管」的设置类操作——装完那个书签之后，
 * 用户一年也不会再点这个入口。为它单开一页和改密码一样是过度设计。
 * 而它又必须有一个入口：书签小工具的安装页（/bookmarklet.html）早就写好了，
 * 之前应用里没有任何地方指向它，等于这个功能用户碰不到。
 *
 * <p><b>改完不清空已填的旧密码。</b>
 * 失败了要让人接着改，清空等于逼他重新敲一遍。
 * 成功后清空新密码即可（旧密码已经作废，留着没意义）。
 */
function UserMenu({
  user, onLogout, notify,
}: {
  user: AuthUser;
  onLogout: () => void;
  notify: (msg: string, kind?: "ok" | "warn") => void;
}) {
  const [open, setOpen] = useState(false);
  const [changing, setChanging] = useState(false);
  const [oldPw, setOldPw] = useState("");
  const [newPw, setNewPw] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [verifyBusy, setVerifyBusy] = useState(false);
  const boxRef = useRef<HTMLDivElement>(null);

  /**
   * 发一封验证邮件。
   *
   * <p>不做「已发送，60 秒后可重发」的倒计时：后端已经按人限流了（一小时 5 次），
   * 前端再做一个倒计时就是同一条规则实现两遍，而且两边迟早对不上。
   * 真被限住了，后端那句「请 N 秒后再试」会原样显示出来。
   */
  const sendVerify = async () => {
    setVerifyBusy(true);
    try {
      await sendEmailVerification();
      notify("验证邮件发出去了，去看邮箱");
    } catch (e) {
      notify(e instanceof Error ? e.message : "没发出去，稍后再试", "warn");
    } finally {
      setVerifyBusy(false);
    }
  };

  /** 点面板外面就收起来。不然它要一直挂着，挡住下面的内容。 */
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (boxRef.current && !boxRef.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onDown);
    return () => document.removeEventListener("mousedown", onDown);
  }, [open]);

  const submitChange = async (e: React.FormEvent) => {
    e.preventDefault();
    if (newPw.length < 8 || newPw.length > 72) {
      setErr("新密码长度要在 8 到 72 位之间");
      return;
    }
    setBusy(true);
    setErr(null);
    try {
      await changePassword(oldPw, newPw);
      setChanging(false);
      setOldPw("");
      setNewPw("");
      setOpen(false);
      notify("密码改好了");
    } catch (e2) {
      setErr((e2 as ApiError).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div ref={boxRef} className="relative">
      <button
        onClick={() => setOpen(!open)}
        title={user.username}
        className={cn(
          "grid h-[26px] min-w-[26px] place-items-center rounded-full border px-1.5 text-[11px] font-medium transition-colors",
          open
            ? "border-ink bg-ink text-canvas"
            : "border-line text-ink2 hover:border-line-strong hover:text-ink",
        )}
      >
        {user.displayName.slice(0, 2)}
      </button>

      {open && (
        <div className="absolute right-0 top-full z-30 mt-1.5 w-[252px] rounded-lg border border-line bg-surface p-3 shadow-[0_12px_32px_-12px_rgba(0,0,0,0.22)]">
          <p className="text-[12.5px] font-medium text-ink">{user.displayName}</p>
          <p className="mt-0.5 text-[11px] text-ink3">@{user.username}</p>

          {/*
            没验证邮箱时给一条提示 + 一个发送按钮。
            验证过了就什么都不显示——不用一个绿色「已验证」去占用这个 252px 的面板，
            好事不需要报备，只有「还没做」才需要提醒。

            说清「不验证也能用」，否则这句话看起来像一条没完成的任务，
            而它其实是个可选项。
          */}
          {user.email && !user.emailVerified && (
            <div className="mt-2.5 rounded-md border border-line bg-sunken px-2.5 py-2">
              <p className="text-[11px] leading-relaxed text-ink2">
                邮箱还没验证。不验证也能正常用，只是没法用它找回密码。
              </p>
              <button
                onClick={() => void sendVerify()}
                disabled={verifyBusy}
                className="mt-1.5 text-[11.5px] text-accent transition-opacity hover:opacity-80 disabled:opacity-50"
              >
                {verifyBusy ? "发着…" : "发一封验证邮件"}
              </button>
            </div>
          )}

          {!changing ? (
            <div className="mt-3 flex flex-col gap-1">
              {/*
                装书签放在改密码上面，同一层级。
                点开新标签页去安装页，而不是在菜单里直接展开说明——
                装书签要在浏览器书栏上拖拽/右键，那件事在别处的独立页面里说更清楚，
                也免得这个 252px 的小面板塞下一整篇教程。
              */}
              <button
                onClick={() => { setOpen(false); window.open("/bookmarklet.html", "_blank"); }}
                className="rounded-md px-2 py-[6px] text-left text-[12px] text-ink2 transition-colors hover:bg-sunken hover:text-ink"
              >
                把拾链装成书签
              </button>
              <button
                onClick={() => { setChanging(true); setErr(null); }}
                className="rounded-md px-2 py-[6px] text-left text-[12px] text-ink2 transition-colors hover:bg-sunken hover:text-ink"
              >
                修改密码
              </button>
              <button
                onClick={() => { setOpen(false); onLogout(); }}
                className="flex items-center gap-1.5 rounded-md px-2 py-[6px] text-left text-[12px] text-ink2 transition-colors hover:bg-sunken hover:text-ink"
              >
                <LogOut size={11.5} />
                退出登录
              </button>
            </div>
          ) : (
            <form onSubmit={submitChange} className="mt-3 space-y-2">
              <input
                type="password"
                value={oldPw}
                autoFocus
                onChange={(e) => setOldPw(e.target.value)}
                placeholder="当前密码"
                autoComplete="current-password"
                className="w-full rounded-md border border-line bg-canvas px-2.5 py-[6px] text-[12px] text-ink outline-none transition-colors placeholder:text-ink3/60 focus:border-accent"
              />
              <input
                type="password"
                value={newPw}
                onChange={(e) => setNewPw(e.target.value)}
                placeholder="新密码（至少 8 位）"
                autoComplete="new-password"
                className="w-full rounded-md border border-line bg-canvas px-2.5 py-[6px] text-[12px] text-ink outline-none transition-colors placeholder:text-ink3/60 focus:border-accent"
              />

              {err && <p className="text-[11px] leading-relaxed text-amber-700">{err}</p>}

              <div className="flex gap-1.5 pt-0.5">
                <button
                  type="submit"
                  disabled={busy}
                  className={cn(
                    "flex-1 rounded-md py-[6px] text-[12px] font-medium text-white transition-opacity",
                    busy ? "cursor-not-allowed bg-ink3/40" : "bg-accent hover:opacity-90",
                  )}
                >
                  {busy ? "请稍等…" : "保存"}
                </button>
                <button
                  type="button"
                  onClick={() => { setChanging(false); setErr(null); setOldPw(""); setNewPw(""); }}
                  className="rounded-md border border-line px-2.5 py-[6px] text-[12px] text-ink2 transition-colors hover:border-line-strong hover:text-ink"
                >
                  取消
                </button>
              </div>
            </form>
          )}
        </div>
      )}
    </div>
  );
}

function N({ children }: { children: React.ReactNode }) {
  return <span className="font-serif text-[11px] opacity-55">{children}</span>;
}

function NavBtn({
  active, onClick, children,
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      onClick={onClick}
      className={cn(
        "flex items-center gap-1.5 rounded-full border px-2.5 py-[3.5px] text-[11.5px] transition-colors",
        active
          ? "border-ink bg-ink text-canvas"
          : "border-line text-ink2 hover:border-line-strong hover:text-ink",
      )}
    >
      {children}
    </button>
  );
}

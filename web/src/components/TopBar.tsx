import { useEffect, useRef, useState } from "react";
import {
  Search, Sparkles, Loader2, RefreshCw, Moon, Sun, Clock3, BarChart3,
  AlertTriangle, LogOut,
} from "lucide-react";
import { cn } from "../lib/utils";
import { ApiError, changePassword, type AuthUser } from "../api";
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

interface Props {
  /* 录入 */
  onSubmitUrl: (url: string) => void;

  /* 品牌行 */
  total: number;
  reviewCount: number;
  view: "list" | "review" | "weekly";
  onReview: () => void;
  onWeekly: () => void;
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
  onSubmitUrl, total, reviewCount, view, onReview, onWeekly, theme, toggleTheme, aiReady,
  query, setQuery, semantic, setSemantic, semanticAvailable, semanticReason, semanticLoading,
  sort, setSort, domainCounts, purposeCounts, activeDomain, setActiveDomain,
  activePurposes, togglePurpose, onRefresh, refreshing, count,
  user, onLogout, notify,
}: Props) {
  const [url, setUrl] = useState("");

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
          </div>
        )}
      </div>
    </header>
  );
}

/* ────────────── 小零件 ────────────── */

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
 * 账号菜单：改密码 + 退出。
 *
 * <p><b>改密码放在这里而不是单独一页。</b>
 * 它是一年用一次的操作，为它开一个页面会让「设置」变成
 * 一个只有一项的列表——用户点进去还要再点一次。
 * 收在这个小面板里，一次点击就能看到全部。
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
  const boxRef = useRef<HTMLDivElement>(null);

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

          {!changing ? (
            <div className="mt-3 flex flex-col gap-1">
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

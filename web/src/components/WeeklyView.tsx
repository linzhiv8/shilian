import { useCallback, useEffect, useState } from "react";
import {
  Loader2, RefreshCw, ArrowLeft, AlertTriangle, Sparkles, Quote,
} from "lucide-react";
import { cn } from "../lib/utils";
import { domainOf, deep, tint, type DomainKey, type LinkItem } from "../types";
import { ApiError, getWeeklyDigest, type WeeklyDigest } from "../api";

interface Props {
  onOpen: (item: LinkItem) => void;
  onExit: () => void;
  notify: (msg: string, kind?: "ok" | "warn") => void;
}

/**
 * 周报。
 *
 * <p>需求里写的是「每周摘要（AI 写的，不是列表）」——所以这里的主体是那段话，
 * 不是统计数字。数字放在下面当佐证：用户想看的时候能核对，
 * 但他真正需要的是「我这周在关注什么」这个他自己看不出来的结论。
 *
 * <p>摘要按周缓存在服务端，正常打开不花 token。「重写」按钮才强制重调。
 */
export default function WeeklyView({ onOpen, onExit, notify }: Props) {
  const [data, setData] = useState<WeeklyDigest | null>(null);
  const [loading, setLoading] = useState(true);
  const [rewriting, setRewriting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (refresh = false) => {
    if (refresh) setRewriting(true);
    else setLoading(true);
    setError(null);
    try {
      setData(await getWeeklyDigest(refresh));
      if (refresh) notify("周报已重写");
    } catch (e) {
      setError((e as ApiError).message);
    } finally {
      setLoading(false);
      setRewriting(false);
    }
  }, [notify]);

  useEffect(() => {
    void load();
  }, [load]);

  const s = data?.stats;

  return (
    <div className="mx-auto w-full max-w-[880px] px-10 pb-20 pt-10">
      <div className="mb-5 flex items-center gap-3">
        <button
          onClick={onExit}
          className="grid h-7 w-7 place-items-center rounded-md text-ink3 transition-colors hover:bg-sunken hover:text-ink"
          title="回列表"
        >
          <ArrowLeft size={14} />
        </button>
        <h1 className="font-serif text-[26px] leading-none tracking-[-0.015em] text-ink">周报</h1>
        {data && (
          <span className="text-[11.5px] text-ink3">
            {data.weekKey}
            {data.cached && " · 读的缓存"}
          </span>
        )}
        <button
          onClick={() => void load(true)}
          disabled={rewriting || loading}
          className="ml-auto flex items-center gap-1.5 rounded-md px-2 py-1 text-[11.5px] text-ink3 transition-colors hover:bg-sunken hover:text-ink disabled:opacity-40"
          title="重新生成这段摘要（会花一次 token）"
        >
          <RefreshCw size={11.5} className={rewriting ? "animate-spin" : undefined} />
          重写
        </button>
      </div>

      {loading && (
        <>
          <div className="flex items-center gap-2 text-[12.5px] text-ink3">
            <Loader2 size={13} className="animate-spin" />
            正在汇总…
          </div>
          <div className="mt-5 h-[140px] rounded-2xl border border-line bg-surface pulse-soft" />
        </>
      )}

      {error && !loading && (
        <div className="rounded-xl border border-amber-200 bg-amber-50/60 p-4 dark:border-amber-900 dark:bg-amber-950/40">
          <div className="flex items-center gap-2">
            <AlertTriangle size={13} className="text-amber-700" />
            <span className="text-[12.5px] font-medium text-amber-800 dark:text-amber-300">
              没能把周报取回来
            </span>
          </div>
          <p className="mt-2 text-[12px] leading-relaxed text-amber-800/90 dark:text-amber-300/90">
            {error}
          </p>
          <button
            onClick={() => void load()}
            className="mt-3 flex items-center gap-1.5 rounded-lg border border-line px-3 py-[6px] text-[12px] text-ink2 transition-colors hover:border-line-strong hover:text-ink"
          >
            <RefreshCw size={11.5} />
            再试一次
          </button>
        </div>
      )}

      {data && !loading && s && (
        <div className="rise space-y-4">
          {/* ── AI 写的那段 ── */}
          <div className="rounded-2xl border border-line bg-surface p-5">
            {data.degraded ? (
              <>
                <div className="flex items-start gap-2">
                  <AlertTriangle size={13} className="mt-[2px] shrink-0 text-amber-700" />
                  <p className="text-[12.5px] leading-relaxed text-amber-800 dark:text-amber-300">
                    {data.degraded}
                  </p>
                </div>
                <p className="mt-3 text-[11.5px] text-ink3">
                  点右上角「重写」可以再试一次。
                </p>
              </>
            ) : (
              <>
                <div className="flex items-center gap-1.5 text-[11px] text-ink3">
                  {data.aiWritten ? (
                    <>
                      <Sparkles size={11} />
                      AI 写的
                    </>
                  ) : (
                    <>这周没什么可说的</>
                  )}
                </div>

                {data.headline && (
                  <h2 className="mt-3 font-serif text-[24px] leading-[1.3] tracking-[-0.01em] text-ink">
                    {data.headline}
                  </h2>
                )}
                {/*
                  AI 写的这段话是这一页的主体（数字只是佐证），
                  所以给它衬线 + 15px + 1.8 行高，并且用 ink 而不是 ink2——
                  ink2 是「次要说明」的颜色，主体文字用它等于自己把自己降级了。

                  限宽 660px：容器 880 是为了和列表左边缘对齐，但正文一行超过
                  40 个中文字，眼睛回行就要找位置了。
                */}
                {data.body && (
                  <p className="mt-3 max-w-[660px] font-serif text-[15px] leading-[1.8] text-ink">
                    {data.body}
                  </p>
                )}
                {data.observation && (
                  <div className="mt-5 flex items-start gap-2.5 border-t border-line pt-4">
                    <Quote size={12} className="mt-[4px] shrink-0 text-ink3" />
                    <p className="font-serif text-[13.5px] italic leading-[1.75] text-ink2">
                      {data.observation}
                    </p>
                  </div>
                )}
              </>
            )}
          </div>

          {/* ── 数字。放在摘要下面当佐证，不是主体 ── */}
          <div className="grid grid-cols-4 gap-2.5">
            <Stat label="这周新增" value={s.saved} />
            <Stat label="打开过" value={s.opened} tone={s.saved > 0 && s.opened === 0 ? "warn" : undefined} />
            <Stat label="标为已用" value={s.used} />
            <Stat label="库里总共" value={s.total} />
          </div>

          {/* ── 领域分布 ── */}
          {s.domains.length > 0 && (
            <div className="rounded-2xl border border-line bg-surface p-5">
              <p className="text-[12.5px] font-medium text-ink">这周的方向</p>
              <div className="mt-3 space-y-2">
                {s.domains.map((dc) => {
                  const d = domainOf(dc.domain as DomainKey);
                  const pct = s.saved > 0 ? Math.round((dc.count / s.saved) * 100) : 0;
                  return (
                    <div key={dc.domain} className="flex items-center gap-3">
                      <span className="w-[92px] shrink-0 truncate text-[11.5px] text-ink2">
                        {d.name}
                      </span>
                      <div className="h-[6px] flex-1 overflow-hidden rounded-full bg-sunken">
                        <div
                          className="h-full rounded-full"
                          style={{ width: `${Math.max(pct, 6)}%`, background: deep(d.color) }}
                        />
                      </div>
                      <span className="w-[46px] shrink-0 text-right text-[11px] tabular-nums text-ink3">
                        {dc.count} 条
                      </span>
                    </div>
                  );
                })}
              </div>
            </div>
          )}

          {/* ── 这周存了什么 ── */}
          {s.newLinks.length > 0 && (
            <div className="rounded-2xl border border-line bg-surface p-5">
              <p className="text-[12.5px] font-medium text-ink">这周存的</p>
              <div className="mt-2.5 divide-y divide-line">
                {s.newLinks.map((l) => {
                  const d = domainOf(l.domainKey);
                  return (
                    <button
                      key={l.id}
                      onClick={() => onOpen(l)}
                      className="flex w-full items-center gap-3 py-2.5 text-left transition-opacity hover:opacity-70"
                    >
                      <span
                        className="grid h-6 w-6 shrink-0 place-items-center rounded-md text-[10px] font-semibold"
                        style={{ background: tint(d.color), color: deep(d.color) }}
                      >
                        {l.monogram}
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-[12.5px] text-ink">{l.title}</span>
                        <span className="block truncate text-[11px] text-ink3">
                          {l.site} · {l.savedLabel}
                        </span>
                      </span>
                      {l.starred && (
                        <span className="shrink-0 text-[10.5px] text-ink3">已加星</span>
                      )}
                    </button>
                  );
                })}
              </div>
            </div>
          )}

          {/* ── 最该提醒的那句 ── */}
          {s.neverOpenedTotal > 0 && (
            <p className="px-1 text-[11.5px] leading-relaxed text-ink3">
              库里还有 {s.neverOpenedTotal} 条从存下来就没打开过
              {s.dueTotal > 0 && <>，其中 {s.dueTotal} 条已经到了该回顾的时候</>}。
            </p>
          )}
        </div>
      )}
    </div>
  );
}

function Stat({
  label, value, tone,
}: {
  label: string;
  value: number;
  tone?: "warn";
}) {
  return (
    <div className="rounded-xl border border-line bg-surface px-3 py-3">
      <p className="text-[10.5px] text-ink3">{label}</p>
      <p
        className={cn(
          "mt-1.5 font-serif text-[24px] tabular-nums leading-none",
          tone === "warn" ? "text-amber-700 dark:text-amber-400" : "text-ink",
        )}
      >
        {value}
      </p>
    </div>
  );
}

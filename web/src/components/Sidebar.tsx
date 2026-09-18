import { useState } from "react";
import {
  Layers, Moon, Sun, CornerDownLeft, Clock3, Sparkles, ArrowRight, AlertTriangle,
} from "lucide-react";
import { cn } from "../lib/utils";
import {
  DOMAINS, PURPOSES, deep,
  type DomainKey, type PurposeKey,
} from "../types";
import { REVIEW_BATCH, REVIEW_DAYS } from "../api";

interface Props {
  domainCounts: Record<string, number>;
  purposeCounts: Record<string, number>;
  total: number;
  reviewCount: number;
  /** 当前在哪个视图。侧栏据此决定高亮谁——不在列表视图时，领域选中态要撤掉 */
  view: "list" | "review" | "weekly";
  activeDomain: DomainKey | "all";
  setActiveDomain: (k: DomainKey | "all") => void;
  activePurposes: PurposeKey[];
  togglePurpose: (k: PurposeKey) => void;
  onSubmitUrl: (url: string) => void;
  theme: "light" | "dark";
  toggleTheme: () => void;
  aiReady: boolean;
  model: string;
  onReview: () => void;
  onWeekly: () => void;
}

export default function Sidebar({
  domainCounts, purposeCounts, total, reviewCount,
  view, activeDomain, setActiveDomain,
  activePurposes, togglePurpose,
  onSubmitUrl, theme, toggleTheme,
  aiReady, model, onReview, onWeekly,
}: Props) {
  const [url, setUrl] = useState("");

  const submit = () => {
    if (!url.trim()) return;
    onSubmitUrl(url.trim());
    setUrl("");
  };

  return (
    <aside className="flex h-full w-[236px] shrink-0 flex-col border-r border-line">
      {/*
        侧栏刻意不铺底色，直接落在和主区同一张 canvas「纸」上。

        原来这里是 bg-surface（纯白），和主区的暖白 canvas 差了四个色阶、
        色相也不同，两块并排一眼就能看出是「拼起来的」。
        更要紧的是层级错了：surface 是卡片用的底色，侧栏也用 surface，
        等于它自己在跟几百张卡片抢同一层——谁都不像浮在纸上。

        正确关系是 canvas（纸）→ sunken（浅凹，比如输入框）→ surface（卡片，最高）。
        侧栏属于「纸」这一层，靠 border-r 和内容排版分栏就够了。
      */}
      <div className="flex h-[58px] shrink-0 items-center gap-2 px-4">
        <span className="grid h-[22px] w-[22px] place-items-center rounded-[6px] bg-accent">
          <Layers size={13} className="text-white" strokeWidth={2.2} />
        </span>
        <span className="font-serif text-[17px] leading-none tracking-[-0.01em] text-ink">
          拾链
        </span>
        <span className="ml-auto font-serif text-[12px] tabular-nums text-ink3">{total}</span>
      </div>

      <div className="px-3.5">
        <div className="group relative">
          <input
            value={url}
            onChange={(e) => setUrl(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && submit()}
            placeholder="粘贴网址，回车即存"
            className="w-full rounded-lg border border-line bg-sunken py-2 pl-2.5 pr-8 text-[12.5px] text-ink outline-none transition-colors placeholder:text-ink3 focus:border-accent focus:bg-surface"
          />
          <CornerDownLeft
            size={12}
            className="pointer-events-none absolute right-2.5 top-1/2 -translate-y-1/2 text-ink3 transition-colors group-focus-within:text-accent"
          />
        </div>
      </div>

      <nav className="mt-6 flex-1 overflow-y-auto px-2.5 pb-4">
        <p className="px-2 pb-2 text-[11px] uppercase tracking-[0.08em] text-ink3">
          领域
        </p>
        {/*
          选中态从「填充色块」改成「左侧竖线 + 文字加深」。
          色块在列表里一多就像一排按钮在抢注意力，而侧栏在这里是目录，
          目录该安静——竖线只占 2px，扫一眼就能定位，但不打断整列的灰度节奏。
        */}
        <button
          onClick={() => setActiveDomain("all")}
          className={cn(
            "relative flex w-full items-center gap-2 rounded-md py-[5px] pl-3.5 pr-2 text-left text-[12.5px] transition-colors",
            view === "list" && activeDomain === "all"
              ? "font-medium text-ink"
              : "text-ink2 hover:text-ink",
          )}
        >
          {view === "list" && activeDomain === "all" && (
            <span className="absolute left-0 top-1/2 h-[13px] w-[2px] -translate-y-1/2 rounded-full bg-ink" />
          )}
          <span className="flex-1">全部</span>
          <span className={cn("font-serif text-[11.5px] tabular-nums", view === "list" && activeDomain === "all" ? "text-ink2" : "text-ink3")}>
            {total}
          </span>
        </button>

        {DOMAINS.map((d) => {
          const n = domainCounts[d.key] ?? 0;
          const on = view === "list" && activeDomain === d.key;
          return (
            <button
              key={d.key}
              onClick={() => setActiveDomain(on ? "all" : d.key)}
              disabled={n === 0}
              className={cn(
                "relative flex w-full items-center gap-2 rounded-md py-[5px] pl-3.5 pr-2 text-left text-[12.5px] transition-colors",
                on ? "font-medium text-ink" : "text-ink2 hover:text-ink",
                n === 0 && "cursor-default opacity-35",
              )}
            >
              {on && (
                <span
                  className="absolute left-0 top-1/2 h-[13px] w-[2px] -translate-y-1/2 rounded-full"
                  style={{ background: deep(d.color) }}
                />
              )}
              <span
                className="h-[6px] w-[6px] shrink-0 rounded-full"
                style={{ background: deep(d.color) }}
              />
              <span className="flex-1 truncate">{d.name}</span>
              <span className={cn("font-serif text-[11.5px] tabular-nums", on ? "text-ink2" : "text-ink3")}>
                {n}
              </span>
            </button>
          );
        })}

        <p className="px-2 pb-2 pt-6 text-[11px] uppercase tracking-[0.08em] text-ink3">
          用途
        </p>
        <div className="flex flex-wrap gap-1.5 px-1">
          {PURPOSES.map((p) => {
            const n = purposeCounts[p.key] ?? 0;
            const on = activePurposes.includes(p.key);
            return (
              <button
                key={p.key}
                onClick={() => togglePurpose(p.key)}
                disabled={n === 0}
                className={cn(
                  "rounded-full border px-2 py-[3px] text-[11px] transition-colors",
                  /*
                    选中态用 ink 而不是 accent。
                    紫色在这个暖调纸面上是唯一的冷色，一旦小面积高频出现
                    （这里一排最多八个）就会把整个侧栏拉回「工具界面」的感觉。
                    accent 留给真正需要它跳出来的地方：主按钮和链接 hover。
                  */
                  on
                    ? "border-ink bg-ink text-white"
                    : "border-line text-ink2 hover:border-line-strong hover:text-ink",
                  n === 0 && "cursor-default opacity-35 hover:border-line",
                )}
              >
                {p.name}
                <span className={cn("ml-1 font-serif text-[10.5px] tabular-nums", on ? "text-white/60" : "text-ink3")}>{n}</span>
              </button>
            );
          })}
        </div>
      </nav>

      <div className="border-t border-line px-3.5 py-3.5">
        {/*
          原来是扣在 sunken 底上的一张小卡片。去掉那个底之后这一块直接落在纸面上，
          只靠一条上边线和上面隔开——侧栏底部本来就是全栏最窄的地方，
          再往里嵌一层框会显得挤。
        */}
        <div>
          <div className="flex items-center gap-1.5">
            <Clock3 size={11.5} className="text-ink3" />
            <span className="text-[11px] uppercase tracking-[0.08em] text-ink3">
              该回头看了
            </span>
            {reviewCount > 0 && (
              <span className="ml-auto font-serif text-[16px] leading-none tabular-nums text-ink">
                {reviewCount}
              </span>
            )}
          </div>

          <p className="mt-1.5 text-[12px] leading-snug text-ink2">
            {reviewCount > 0 ? (
              <>
                有 <span className="font-medium text-ink">{reviewCount} 条</span>超过{" "}
                {REVIEW_DAYS} 天没打开
              </>
            ) : total === 0 ? (
              "还没有收藏"
            ) : (
              `没有放了 ${REVIEW_DAYS} 天以上没动的`
            )}
          </p>

          {/*
            计数为 0 时按钮照样留着。以前是藏起来，但那等于把入口一起藏了——
            用户永远不知道有这么个东西。回顾页自己有「暂时没有要回顾的」空状态，
            点进去看一眼，比看不见入口好。
          */}
          <div className="mt-2.5 flex items-center gap-3">
            <button
              onClick={onReview}
              className={cn(
                "flex items-center gap-1 text-[11.5px] font-medium transition-opacity hover:opacity-70",
                view === "review" ? "text-ink" : "text-accent",
              )}
            >
              {view === "review" && <span className="h-[5px] w-[5px] rounded-full bg-ink" />}
              抽 {REVIEW_BATCH} 张看看
              {view !== "review" && <ArrowRight size={11} />}
            </button>

            <span className="h-3 w-px shrink-0 bg-line-strong" />

            <button
              onClick={onWeekly}
              className={cn(
                "flex items-center gap-1 text-[11.5px] font-medium transition-opacity hover:opacity-70",
                view === "weekly" ? "text-ink" : "text-ink3",
              )}
            >
              {view === "weekly" && <span className="h-[5px] w-[5px] rounded-full bg-ink" />}
              周报
            </button>
          </div>
        </div>

        <div className="mt-3 flex items-center justify-between">
          {aiReady ? (
            <span className="flex items-center gap-1.5 text-[11px] text-ink3">
              <Sparkles size={11} />
              {model || "DeepSeek"} 已连接
            </span>
          ) : (
            <span
              className="flex items-center gap-1.5 text-[11px] text-amber-700"
              title="在 server/.env.properties 里填 DEEPSEEK_API_KEY（server/ 和 web/ 平级）"
            >
              <AlertTriangle size={11} />
              没配 API Key
            </span>
          )}
          <button
            onClick={toggleTheme}
            title="切换深浅色"
            className="grid h-6 w-6 place-items-center rounded-md text-ink3 transition-colors hover:bg-sunken hover:text-ink"
          >
            {theme === "light" ? <Moon size={13} /> : <Sun size={13} />}
          </button>
        </div>
      </div>
    </aside>
  );
}

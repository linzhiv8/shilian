import { useState } from "react";
import { ExternalLink, Star, AlertTriangle, Clock, Trash2, ClipboardPaste, Pencil } from "lucide-react";
import { cn } from "../lib/utils";
import { domainOf, deep, tint, purposeName, type LinkItem } from "../types";
import { REVIEW_DAYS } from "../api";

interface Props {
  item: LinkItem;
  onToggleStar: (id: string) => void;
  onOpen: (item: LinkItem) => void;
  onDelete: (id: string) => void;
  /** 打开「补正文」抽屉，给这条待补的卡片重跑分析。 */
  onFix: (item: LinkItem) => void;
  /** 打开「编辑」抽屉，改这条的标题/摘要/备注/分类。不调模型。 */
  onEdit: (item: LinkItem) => void;
}

export default function LinkCard({ item, onToggleStar, onOpen, onDelete, onFix, onEdit }: Props) {
  const d = domainOf(item.domainKey);
  const [confirming, setConfirming] = useState(false);

  return (
    <article
      className={cn(
        "group relative flex flex-col overflow-hidden rounded-xl border bg-surface",
        "transition-colors duration-200 ease-out",
        "hover:border-line-strong",
        item.needsReview ? "border-amber-300/70" : "border-line",
      )}
    >
      {/*
        封面。

        改完一版「16:9 居左角标」之后发现一个反效果：16:9 在卡片高度里占了 60% 的
        视觉重量，但只输出两个字母——大半张图都是空的，反而比原来的 100px 等高
        色块更难看，看着像没加载完。

        改回 h-[112px] 等高（仍是杂志的"刊头"高度，比例不靠 aspect-ratio 锁——
        卡片整体等高由父网格管，这里只需要给一个合理的高度），字母 44px 衬线
        居中当「刊头大字」——这是真正杂志封面的字模排法，不是 logo 角落。

        字母下方一行是领域名（uppercase + tracking），做"封面副标"。
        这里就把领域说完了，下面元信息里就不要再重复。
      */}
      <div
        className="relative h-[112px] shrink-0 overflow-hidden"
        style={{ background: tint(d.color) }}
      >
        <span
          className="absolute inset-x-0 top-[44%] -translate-y-1/2 select-none text-center font-serif text-[44px] leading-none tracking-[0.05em] opacity-85 transition-transform duration-300 ease-out group-hover:scale-[1.04]"
          style={{ color: deep(d.color) }}
        >
          {item.monogram}
        </span>
        <span
          className="absolute inset-x-0 bottom-2.5 select-none text-center text-[10px] uppercase tracking-[0.14em] opacity-70"
          style={{ color: deep(d.color) }}
        >
          {d.name}
        </span>

        <div className="absolute right-2 top-2 flex gap-1 opacity-0 transition-opacity duration-200 group-hover:opacity-100">
          {/*
            编辑放在最左。顺序是有意的：删除是破坏性的、还要点两次确认，
            所以它永远在最右边——手滑点错时至少不会落在它上面。
          */}
          <button
            onClick={() => onEdit(item)}
            title="改标题、摘要、备注和分类"
            className="grid h-6 w-6 place-items-center rounded-md bg-surface/85 text-ink2 backdrop-blur-sm transition-colors hover:text-ink"
          >
            <Pencil size={12} />
          </button>
          <button
            onClick={() => onToggleStar(item.id)}
            title={item.starred ? "取消星标" : "加星标"}
            className="grid h-6 w-6 place-items-center rounded-md bg-surface/85 text-ink2 backdrop-blur-sm transition-colors hover:text-ink"
          >
            <Star
              size={12.5}
              className={item.starred ? "fill-current" : ""}
              style={item.starred ? { color: deep(d.color) } : undefined}
            />
          </button>
          <button
            onClick={() => {
              if (confirming) {
                onDelete(item.id);
              } else {
                setConfirming(true);
                // 3 秒内没有第二次点击就复位，避免一直停在待确认状态
                setTimeout(() => setConfirming(false), 3000);
              }
            }}
            title={confirming ? "再点一次确认删除" : "删除"}
            className={cn(
              "grid h-6 place-items-center rounded-md backdrop-blur-sm transition-all",
              confirming
                ? "w-auto gap-1 bg-red-600 px-1.5 text-white"
                : "w-6 bg-surface/85 text-ink2 hover:text-red-600",
            )}
          >
            <Trash2 size={12.5} />
            {confirming && <span className="text-[10.5px] font-medium">确认</span>}
          </button>
          <a
            href={item.url}
            target="_blank"
            rel="noreferrer"
            onClick={() => onOpen(item)}
            title="打开原站"
            className="grid h-6 w-6 place-items-center rounded-md bg-surface/85 text-ink2 backdrop-blur-sm transition-colors hover:text-ink"
          >
            <ExternalLink size={12.5} />
          </a>
        </div>

        {item.needsReview && (
          <span
            className="absolute left-2 top-2 flex items-center gap-1 rounded-md bg-amber-100/95 px-1.5 py-[3px] text-[10.5px] font-medium text-amber-800"
            title="抓取失败或 AI 判断存疑，建议复核"
          >
            <AlertTriangle size={10} />
            待补
          </span>
        )}
      </div>

      <div className="flex flex-1 flex-col px-4 pb-4 pt-3.5">
        {/*
          用途行。封面副标已经把领域说完了，这里只放「用途」和「时间」。

          不再用色点 + uppercase + tracking 的"刊头小标"形态——
          那是给分组类别用的（领域），到了「这条具体是什么用途」就该回到
          正文层级：纯文字、ink2 颜色、不强字距。「已用」走 sunken 小角标，
          区别于其他用途——它是状态不是属性。
        */}
        <div className="flex items-center gap-2 text-[11.5px] text-ink2">
          <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5">
            {item.purposes.slice(0, 2).map((p) => (
              <span key={p}>{purposeName(p)}</span>
            ))}
            {item.used && (
              /*
               * 「已用」是独立的 used 字段，不是用途也不是状态。
               *
               * V4 之前它挂在 status 上，那时候这个角标意味着
               * 「这条记录被锁进了某个状态，撤不回来」；现在它只是一个开关的当前值，
               * 用户可以随手拨回去——角标该跟着消失，也确实会。
               */
              <span className="rounded-[3px] bg-sunken px-1.5 py-[1px] text-ink2">已用</span>
            )}
          </div>
          <span className="ml-auto flex shrink-0 items-center gap-1 text-ink3">
            {item.starred && (
              <Star size={10} className="fill-current text-amber-500" />
            )}
            {item.savedLabel}
          </span>
        </div>

        <a
          href={item.url}
          target="_blank"
          rel="noreferrer"
          onClick={() => onOpen(item)}
          className="mt-2 line-clamp-2 font-serif text-[16px] leading-[1.35] tracking-[-0.01em] text-ink transition-colors hover:text-accent"
        >
          {item.title}
        </a>

        <p className="mt-2 line-clamp-3 text-[12.5px] leading-[1.7] text-ink2">
          {item.summary}
        </p>

        {item.note ? (
          /*
            备注用衬线 + 左右细线夹住，做成「引言块」。
            它是这条收藏里唯一由用户亲手写的文字，视觉上要区别于 AI 生成的摘要——
            斜体和上下分隔线都在说「这句不是上面那段话的续集」。
          */
          <p className="mt-3 border-y border-line py-2 font-serif text-[12.5px] italic leading-[1.65] text-ink">
            {item.note}
          </p>
        ) : (
          <p className="mt-3 border-y border-dashed border-line py-2 text-[11.5px] leading-[1.65] text-ink3">
            还没有备注
          </p>
        )}

        <div className="flex flex-wrap items-center gap-x-2 gap-y-1 pt-3 text-[11px] text-ink2">
          <span className="truncate">{item.site}</span>
          {/*
            标签走 sunken 小角标。原本是裸的 "#xxx"，11px + ink3 在浅色底上
            只有 2.7:1 对比度，远低于小字要的 4.5:1——读起来要在屏幕上
            凑近看才知道是标签还是错别字。sunken 底把它从「文字」变成「元数据」，
            ink2 文字在 sunken 上 7:1，清清楚楚。
          */}
          {item.tags.slice(0, 2).map((t) => (
            <span key={t} className="rounded-[3px] bg-sunken px-1.5 py-[1px] text-ink2">
              #{t}
            </span>
          ))}
          {item.idleDays !== null && item.idleDays >= REVIEW_DAYS && (
            <span
              className="ml-auto flex items-center gap-1 text-ink3"
              title={`${REVIEW_DAYS} 天以上没打开`}
            >
              <Clock size={9.5} />
              {item.idleDays} 天未打开
            </span>
          )}
        </div>

        {/*
          「待补」这个角标本身只是标记，不解决任何问题。
          真正缺的是一个入口——抓取失败是常态（SPA、需登录、Cloudflare），
          而他自己的浏览器里明明读得到内容。以前只有新收藏时能贴正文，
          卡片存下来之后就没救了。

          放在卡片底部而不是做成角标可点：角标太小，而且「点标记会开面板」
          不是个自然的预期。底部是卡片元信息的位置，眼睛会扫到，按钮也够大。
        */}
        {item.needsReview && (
          <button
            onClick={() => onFix(item)}
            title="把页面正文贴进来，重跑一次分析"
            className="mt-2.5 flex w-full items-center justify-center gap-1.5 rounded-lg border border-dashed border-amber-300 bg-amber-50/50 py-[6px] text-[11.5px] font-medium text-amber-800 transition-colors hover:border-amber-400 hover:bg-amber-50 dark:border-amber-900 dark:bg-amber-950/30 dark:text-amber-300 dark:hover:bg-amber-950/60"
          >
            <ClipboardPaste size={11.5} />
            正文没抓到，贴进来补上
          </button>
        )}
      </div>
    </article>
  );
}

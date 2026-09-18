import { useState } from "react";
import { Star, Pencil, ExternalLink, Trash2, ClipboardPaste, Clock } from "lucide-react";
import { cn } from "../lib/utils";
import { domainOf, deep, purposeName, type LinkItem } from "../types";
import { REVIEW_DAYS } from "../api";

interface Props {
  item: LinkItem;
  onToggleStar: (id: string) => void;
  onOpen: (item: LinkItem) => void;
  onDelete: (id: string) => void;
  /** 打开「补正文」抽屉，给这条待补的记录重跑分析。 */
  onFix: (item: LinkItem) => void;
  /** 打开「编辑」抽屉，改这条的标题/摘要/备注/分类。不调模型。 */
  onEdit: (item: LinkItem) => void;
}

/**
 * 一条收藏。
 *
 * 这是新界面的主体，也是和上一版「卡片」最大的分歧点：
 *
 * 1. **没有封面。** 网址没有图，16:9 只能填色块和字母——为了维持卡片感
 *    去填一个假封面，是拿内容迁就版式。这里标题摘要本身就是内容，直接排。
 * 2. **领域只是一条 3px 竖线。** 上一版是 112px 的大色块，十个领域色
 *    每张卡片都在喊。这里退回成索引：扫一眼知道是哪一类，但不抢戏。
 * 3. **衬线只给标题和备注。** 备注还额外加了斜体和左侧细线——
 *    它是用户亲手写的，必须和上面那段 AI 生成的摘要一眼分得开。
 */
export default function LinkEntry({
  item, onToggleStar, onOpen, onDelete, onFix, onEdit,
}: Props) {
  const d = domainOf(item.domainKey);
  const [confirming, setConfirming] = useState(false);

  return (
    <article
      className={cn(
        "group grid grid-cols-[3px_1fr_auto] gap-x-4 border-b border-linesoft py-[18px] pr-4 transition-colors last:border-b-0",
        "hover:bg-sunken/55",
      )}
    >
      {/* 领域竖线。整条里唯一的色块，3px 宽 */}
      <div className="rounded-[2px]" style={{ background: deep(d.color) }} />

      <div className="min-w-0">
        <a
          href={item.url}
          target="_blank"
          rel="noreferrer"
          onClick={() => onOpen(item)}
          className="font-serif text-[17px] leading-[1.35] tracking-[-0.01em] text-ink transition-colors hover:text-accent"
        >
          {item.title}
        </a>

        <p className="mt-1 text-[13px] leading-[1.65] text-ink2">{item.summary}</p>

        {item.note ? (
          /*
            备注：斜体衬线 + 左侧细线。
            它是这条收藏里唯一由用户亲手写的文字，视觉上要区别于 AI 摘要——
            斜体和左侧细线都在说「这句不是上面那段话的续集」。
          */
          <p className="mt-[7px] border-l-2 border-line pl-[11px] font-serif text-[13px] italic leading-[1.6] text-ink">
            {item.note}
          </p>
        ) : (
          <p className="mt-[7px] border-l-2 border-dashed border-line pl-[11px] text-[12.5px] italic leading-[1.6] text-ink3">
            还没有备注
          </p>
        )}

        <div className="mt-2 flex flex-wrap items-center gap-x-2 gap-y-1 text-[11.5px]">
          <span className="text-ink2">{item.site}</span>
          {item.tags.slice(0, 3).map((t) => (
            <span key={t} className="rounded-[3px] bg-sunken px-1.5 py-px text-ink2">
              #{t}
            </span>
          ))}
          {item.purposes.map((p) => (
            <span key={p} className="text-ink3">· {purposeName(p)}</span>
          ))}
          {/*
            「已用」在服务端是 status 而不是 purpose（两者并存会出现
            「用途显示已用但状态还是未读」的自相矛盾数据），所以这里单独渲染。
          */}
          {item.status === "used" && (
            <span className="rounded-[3px] bg-sunken px-1.5 py-px text-ink2">已用</span>
          )}
          {item.idleDays !== null && item.idleDays >= REVIEW_DAYS && (
            <span className="flex items-center gap-1 text-ink3" title={`${REVIEW_DAYS} 天以上没打开`}>
              <Clock size={10} />
              {item.idleDays} 天未打开
            </span>
          )}
          {/*
            补正文的入口。「待补」只是标记，真正缺的是入口——抓取失败是常态
            （SPA、需登录、Cloudflare），而用户自己的浏览器里明明读得到内容。
          */}
          {item.needsReview && (
            <button
              onClick={() => onFix(item)}
              title="把页面正文贴进来，重跑一次分析"
              className="flex items-center gap-1 text-amber-700 transition-opacity hover:opacity-70 dark:text-amber-500"
            >
              <ClipboardPaste size={10.5} />
              正文没抓到，补上
            </button>
          )}
        </div>
      </div>

      <div className="flex flex-col items-end gap-2 pt-[2px]">
        <span className="whitespace-nowrap text-[11.5px] text-ink3">{item.savedLabel}</span>

        {/* 操作只在悬停时出现：平时列表要安静，操作不是内容 */}
        <div className="flex items-center gap-0.5 opacity-0 transition-opacity group-hover:opacity-100">
          <IconBtn
            onClick={() => onToggleStar(item.id)}
            title={item.starred ? "取消星标" : "加星标"}
          >
            <Star
              size={12.5}
              className={item.starred ? "fill-current" : ""}
              style={item.starred ? { color: "#d97706" } : undefined}
            />
          </IconBtn>

          <IconBtn onClick={() => onEdit(item)} title="改标题、摘要、备注和分类">
            <Pencil size={12.5} />
          </IconBtn>

          <a
            href={item.url}
            target="_blank"
            rel="noreferrer"
            onClick={() => onOpen(item)}
            title="打开原站"
            className="grid h-[24px] w-[24px] place-items-center rounded-md text-ink3 transition-colors hover:bg-sunken hover:text-ink"
          >
            <ExternalLink size={12.5} />
          </a>

          <IconBtn
            danger={confirming}
            title={confirming ? "再点一次确认删除" : "删除"}
            onClick={() => {
              if (confirming) {
                onDelete(item.id);
              } else {
                setConfirming(true);
                // 3 秒内没有第二次点击就复位，避免一直停在待确认状态
                setTimeout(() => setConfirming(false), 3000);
              }
            }}
          >
            <Trash2 size={12.5} />
          </IconBtn>
        </div>
      </div>
    </article>
  );
}

function IconBtn({
  onClick, title, danger, children,
}: {
  onClick: () => void;
  title: string;
  danger?: boolean;
  children: React.ReactNode;
}) {
  return (
    <button
      onClick={onClick}
      title={title}
      className={cn(
        "grid h-[24px] w-[24px] place-items-center rounded-md transition-colors",
        danger
          ? "bg-red-600 text-white hover:bg-red-700"
          : "text-ink3 hover:bg-sunken hover:text-ink",
      )}
    >
      {children}
    </button>
  );
}

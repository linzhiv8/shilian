import { useCallback, useEffect, useState } from "react";
import {
  Loader2, ExternalLink, Star, Check, Trash2, RefreshCw,
  Clock3, PartyPopper, AlertTriangle, ArrowLeft, ClipboardPaste,
} from "lucide-react";
import { cn } from "../lib/utils";
import { domainOf, deep, type LinkItem } from "../types";
import { ApiError, getReviewQueue, REVIEW_BATCH, REVIEW_DAYS } from "../api";

interface Props {
  /** 打开原站。App 那边会顺手记一次「已打开」 */
  onOpen: (item: LinkItem) => void;
  onToggleStar: (id: string) => void;
  onDelete: (id: string) => void;
  /** 标记「看过了」，之后不再推给用户 */
  onMarkRead: (id: string) => void;
  /** 就地补正文：打开同一个抽屉的「补正文」模式，补完回到这里而不是列表 */
  onFix: (item: LinkItem) => void;
  /**
   * 补正文完成后回塞回来的那条记录。带一个 seq 是为了让同一个 id
   * 连补两次也能触发——React 的 effect 比较的是引用，
   * 只传 item 的话第二次拿到的是同一个对象引用时会漏掉。
   */
  fixedItem: { item: LinkItem; seq: number } | null;
  onExit: () => void;
  notify: (msg: string, kind?: "ok" | "warn") => void;
}

/**
 * 回顾模式。
 *
 * <p>针对的是这个产品最大的隐性风险：**收藏了却再也不看**。
 * 绝大多数书签工具最后都变成数字坟场，因为用户永远在存、从不回顾。
 *
 * <p><b>为什么做成「一次一张」而不是列出来。</b>
 * 需求里写的是「像抽卡」。一次给三张卡片列表，用户会当成又一个列表划过去——
 * 而列表正是问题本身。一次只给一张，注意力被强制放在这一条上，
 * 卡片也能做大，把「你当时写的备注」摆在最显眼的位置。
 * 备注才是唤醒记忆的东西，不是标题。
 *
 * <p><b>为什么动作要给到四个。</b> 回顾的死穴是「看完了不知道干什么」。
 * 如果只能打开看看，用户扫一眼关掉，这条下次还在队列里——
 * 那就变成反复提醒同一件事，很快就会连这个模式都不进了。
 * 四个动作对应四种真实态度：想用（打开）、想留（加星）、
 * 处理过了（看过了）、不想要（删掉）。每一个都会让它离开队列。
 */
export default function ReviewView({
  onOpen, onToggleStar, onDelete, onMarkRead, onFix, fixedItem, onExit, notify,
}: Props) {
  const [items, setItems] = useState<LinkItem[]>([]);
  const [dueTotal, setDueTotal] = useState(0);
  const [idx, setIdx] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  /** 本次已经处理掉几张。用于收尾时给个「你处理了 N 条」的正反馈 */
  const [handled, setHandled] = useState(0);
  const [leaving, setLeaving] = useState(false);

  const draw = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const q = await getReviewQueue();
      setItems(q.items);
      setDueTotal(q.dueTotal);
      setIdx(0);
    } catch (e) {
      setError((e as ApiError).message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void draw();
  }, [draw]);

  /*
   * 补完正文，把新结果换进当前这张卡片。
   *
   * 刻意<b>不重新抽队列</b>：draw() 会把 idx 归零，
   * 于是用户已经处理过的那几张会重新冒出来一遍——
   * 表现为「我明明处理了五条，补完一条又从第一条开始了」。
   * 也不推进到下一条：补完就该让他看到补成了什么样，
   * 立刻翻走等于没给反馈，他还得回列表去找这条确认。
   */
  useEffect(() => {
    if (!fixedItem) return;
    const { item } = fixedItem;
    setItems((prev) => prev.map((x) => (x.id === item.id ? item : x)));
    // 只认 seq：item 对象本身每次都是新的，拿它当依赖会在别的 state 变化时误触发
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fixedItem?.seq]);

  /** 处理完一张：先让它带走场动效，再推进到下一条。 */
  const advance = () => {
    setLeaving(true);
    setTimeout(() => {
      setLeaving(false);
      setIdx((n) => n + 1);
      setHandled((n) => n + 1);
      setDueTotal((n) => Math.max(0, n - 1));
    }, 180);
  };

  const current = items[idx];

  const act = (label: string, run: (item: LinkItem) => void) => {
    if (!current) return;
    run(current);
    notify(label);
    advance();
  };

  /* ── 加载中 ── */
  if (loading) {
    return (
      <Shell onExit={onExit} progress={null}>
        <div className="flex items-center gap-2 text-[12.5px] text-ink3">
          <Loader2 size={13} className="animate-spin" />
          正在抽卡…
        </div>
        <div className="mt-5 h-[280px] rounded-2xl border border-line bg-surface pulse-soft" />
      </Shell>
    );
  }

  /* ── 出错 ── */
  if (error) {
    return (
      <Shell onExit={onExit} progress={null}>
        <div className="rounded-xl border border-amber-200 bg-amber-50/60 p-4 dark:border-amber-900 dark:bg-amber-950/40">
          <div className="flex items-center gap-2">
            <AlertTriangle size={13} className="text-amber-700" />
            <span className="text-[12.5px] font-medium text-amber-800 dark:text-amber-300">
              没能把队列取回来
            </span>
          </div>
          <p className="mt-2 text-[12px] leading-relaxed text-amber-800/90 dark:text-amber-300/90">
            {error}
          </p>
        </div>
        <button
          onClick={() => void draw()}
          className="mt-4 flex items-center gap-1.5 rounded-lg border border-line px-3.5 py-[7px] text-[12.5px] text-ink2 transition-colors hover:border-line-strong hover:text-ink"
        >
          <RefreshCw size={12} />
          再试一次
        </button>
      </Shell>
    );
  }

  /* ── 这一批处理完了 ── */
  if (!current) {
    const nothingAtAll = dueTotal === 0 && handled === 0;
    return (
      <Shell onExit={onExit} progress={null}>
        <div className="rise rounded-2xl border border-line bg-surface px-6 py-10 text-center">
          <PartyPopper
            size={26}
            className={nothingAtAll ? "mx-auto text-ink3/50" : "mx-auto text-accent"}
            strokeWidth={1.5}
          />
          <p className="mt-4 text-[14px] font-medium text-ink">
            {nothingAtAll ? "暂时没有要回顾的" : "这批看完了"}
          </p>
          <p className="mx-auto mt-1.5 max-w-[360px] text-[12.5px] leading-relaxed text-ink2">
            {nothingAtAll
              ? `没有放了 ${REVIEW_DAYS} 天以上、又没处理过的东西。等库里攒出旧账再来看。`
              : handled > 0
                ? `这次处理了 ${handled} 条。${dueTotal > 0 ? `库里还欠着 ${dueTotal} 条。` : "队列清空了。"}`
                : "这一批都处理过了。"}
          </p>
          <div className="mt-5 flex items-center justify-center gap-2.5">
            {dueTotal > 0 && (
              <button
                onClick={() => void draw()}
                className="flex items-center gap-1.5 rounded-lg bg-accent px-4 py-[7px] text-[12.5px] font-medium text-white transition-opacity hover:opacity-90"
              >
                <RefreshCw size={12} />
                再抽 {REVIEW_BATCH} 张
              </button>
            )}
            <button
              onClick={onExit}
              className="rounded-lg border border-line px-4 py-[7px] text-[12.5px] text-ink2 transition-colors hover:border-line-strong hover:text-ink"
            >
              回列表
            </button>
          </div>
        </div>
      </Shell>
    );
  }

  /* ── 当前这一张 ── */
  const d = domainOf(current.domainKey);
  const idle = current.idleDays ?? 0;

  return (
    <Shell onExit={onExit} progress={{ at: idx + 1, of: items.length, dueTotal }}>
      <div
        className={cn(
          "rounded-2xl border border-line bg-surface px-4 py-5 transition-all duration-150 sm:px-7 sm:py-7",
          leaving && "translate-y-[-6px] scale-[0.985] opacity-0",
        )}
      >
        {/*
          一次一张，不做封面。

          上一版做成 190px 的封面栏（领域色底 + 54px 字母），占了三分之一版面
          却只输出两个字母——和列表里那个假封面是同一个毛病。
          这里字母退回成一个 30px 的印记，整块版面留给标题和备注：
          回顾真正要唤醒记忆的是这两样，不是那个色块。
        */}
        <div>
          {/* 窄屏下这行会挤：领域、站点、闲置天数三段横排放不下，允许换行 */}
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-[11px] uppercase tracking-[0.08em] text-ink3">
            <span style={{ color: deep(d.color) }}>{d.name}</span>
            <span className="tracking-normal">{current.site}</span>
            <span className="ml-auto flex shrink-0 items-center gap-1 tracking-normal">
              <Clock3 size={10.5} />
              放了 {idle} 天没动
            </span>
          </div>

          {/* 字母只是一个印记，不再伪装成封面 */}
          <div
            className="mt-4 select-none font-serif text-[30px] leading-none opacity-55"
            style={{ color: deep(d.color) }}
          >
            {current.monogram}
          </div>

          {/* 32px 在 375px 宽的屏上一行放不下几个字，窄屏收到 26px */}
          <h2 className="mt-2 font-serif text-[26px] leading-[1.2] tracking-[-0.015em] text-ink sm:text-[32px]">
            {current.title}
          </h2>
          <p className="mt-3 text-[14px] leading-[1.75] text-ink2">
            {current.summary}
          </p>

            {/*
              备注放在最显眼的位置——它才是唤醒记忆的东西。
              用户看到「下次画架构草图可以直接用它」会立刻想起当初为什么存，
              而看到标题只会想「嗯，好像存过」。

              斜体 + 上下细线，把它和上面那段 AI 写的摘要在视觉上切开：
              这句是你自己写的，不是上面那段话的续集。
            */}
            {current.note && (
              <div className="mt-6 border-y border-line py-4">
                <p className="text-[11px] uppercase tracking-[0.08em] text-ink3">
                  你当时写的
                </p>
                <p className="mt-2 font-serif text-[16px] italic leading-[1.6] text-ink">
                  {current.note}
                </p>
              </div>
            )}

          {/*
            这条当初没抓到正文，判断可能不准。

            以前这里只有一句「回列表在这条上点补正文」——那是个死胡同：
            他是翻回顾翻到一半撞上这条的，为了补正文退出回顾去列表里重新找一遍，
            等于让产品自己打断自己。找到之后还得再进回顾，刚才翻到哪了也忘了。

            当初不给按钮的理由是「补完这一屏的数据是旧的，还得处理刷新」，
            这个理由现在不成立了：App 补完会把新结果回塞进这张卡片
            （见下面的 fixedItem），原地就能看到补完的样子，不用重抽队列。
          */}
          {current.needsReview && (
            <div className="mt-3 flex items-start gap-1.5 text-[11.5px] leading-relaxed text-amber-700 dark:text-amber-400">
              <AlertTriangle size={11.5} className="mt-[2px] shrink-0" />
              <span className="flex-1">
                {/*
                  和列表里那个补正文入口同一个道理（见 LinkEntry）：
                  pending 是从没分析过，不是「没抓到正文」。
                  在回顾里说错原因更糟——这一屏就一张卡，这句话是他唯一的上下文。
                */}
                {current.analyzeStatus === "pending"
                  ? "这条还没分析过，判断可能不准。"
                  : "这条当时没抓到正文，判断可能不准。"}
              </span>
              <button
                onClick={() => onFix(current)}
                className="flex shrink-0 items-center gap-1 rounded-md border border-amber-300 px-2 py-[3px] font-medium text-amber-800 transition-colors hover:bg-amber-50 dark:border-amber-800 dark:text-amber-300 dark:hover:bg-amber-950/40"
              >
                <ClipboardPaste size={11} />
                就在这里补
              </button>
            </div>
          )}
        </div>
      </div>

      {/* ── 四个动作 ── */}
      <div className="mt-5">
        <button
          onClick={() => act("已打开，这条先出队", (i) => onOpen(i))}
          className="flex w-full items-center justify-center gap-2 rounded-full bg-accent px-6 py-[12px] text-[13px] font-medium text-white transition-opacity hover:opacity-90"
        >
          <ExternalLink size={13.5} />
          打开看看
        </button>

        <div className="mt-2.5 grid grid-cols-3 gap-2">
          <ActionButton
            icon={<Star size={12.5} />}
            label="留下"
            hint="加星，不再推"
            onClick={() => act("已加星", (i) => onToggleStar(i.id))}
          />
          <ActionButton
            icon={<Check size={12.5} />}
            label="看过了"
            hint="不再推给我"
            onClick={() => act("已标为看过", (i) => onMarkRead(i.id))}
          />
          <ActionButton
            icon={<Trash2 size={12.5} />}
            label="删掉"
            hint="不想要了"
            danger
            onClick={() => act("已删除", (i) => onDelete(i.id))}
          />
        </div>
      </div>

      <p className="mt-3.5 text-center text-[11px] leading-relaxed text-ink3">
        四个动作都会让这条离开队列。只「打开看看」的话，
        {REVIEW_DAYS} 天后它还会回来。
      </p>
    </Shell>
  );
}

/* ────────────── 外壳 ────────────── */

function Shell({
  children, onExit, progress,
}: {
  children: React.ReactNode;
  onExit: () => void;
  progress: { at: number; of: number; dueTotal: number } | null;
}) {
  return (
    /*
     * 窄屏（375px）下把左右留白从 40px 收到 16px。
     *
     * 这是工程里第一处响应式断点——之前所有视图都按桌面写的。
     * 先只改回顾：它是唯一一个「用户会专门在手机上打开」的视图
     * （存链接多在电脑上顺手存，回顾是零碎时间翻的）。
     * 其余视图等真的在手机上用过再说，别为了一致提前铺开。
     */
    <div className="mx-auto flex min-h-full w-full max-w-[880px] flex-col px-4 pb-12 pt-6 sm:px-10 sm:pb-20 sm:pt-10">
      <div className="mb-6 flex items-center gap-3">
        <button
          onClick={onExit}
          className="grid h-7 w-7 place-items-center rounded-md text-ink3 transition-colors hover:bg-sunken hover:text-ink"
          title="回列表"
        >
          <ArrowLeft size={14} />
        </button>

        <h1 className="font-serif text-[18px] leading-none tracking-[-0.01em] text-ink">
          该回头看了
        </h1>

        {progress && (
          <>
            <span className="text-[12px] text-ink3">
              第 {progress.at} / {progress.of} 张
            </span>
            {/*
              这里说的是「还没处理的」总数，不是「这一批还剩几张」——
              和左边的「第 N / M 张」是两回事，所以措辞要区分开。
              用户每处理一张它就减一，是实时的。
            */}
            <span className="ml-auto text-[11.5px] text-ink3">
              {progress.dueTotal > 0 ? `队列里还欠着 ${progress.dueTotal} 条` : "这是最后一张"}
            </span>
          </>
        )}
      </div>

      {/*
        卡片在剩余空间里垂直居中。一次只看一张，居中的卡片才像「抽到的这张」；
        顶到最上面会显得像个没写完的列表。内容比视口高时 flex 会自然撑开，
        不会把顶部裁掉。
      */}
      <div className="mx-auto flex w-full max-w-[560px] flex-1 flex-col justify-center">
        {children}
      </div>
    </div>
  );
}

function ActionButton({
  icon, label, hint, onClick, danger,
}: {
  icon: React.ReactNode;
  label: string;
  hint: string;
  onClick: () => void;
  danger?: boolean;
}) {
  return (
    <button
      onClick={onClick}
      title={hint}
      className={cn(
        /*
          去掉边框，改成 hover 时才浮出底色。
          三个次动作各带一个框的话，这一排就是四个等重的方块，
          主按钮「打开看看」反而被压下去；去掉框之后层次才出来——
          一个实心主按钮，三个安静的备选。
        */
        "flex flex-col items-center gap-1 rounded-lg px-2 py-2.5 transition-colors",
        danger
          ? "text-ink2 hover:bg-red-50/70 hover:text-red-700 dark:hover:bg-red-950/40"
          : "text-ink2 hover:bg-sunken hover:text-ink",
      )}
    >
      {icon}
      <span className="text-[12px] font-medium">{label}</span>
    </button>
  );
}

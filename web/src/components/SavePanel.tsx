import { useEffect, useMemo, useRef, useState } from "react";
import {
  X, Check, Loader2, Globe, FileText, Sparkles, Plus, AlertTriangle,
  RotateCcw, WifiOff, ClipboardPaste,
} from "lucide-react";
import { cn } from "../lib/utils";
import {
  DOMAINS, PURPOSES, domainOf, deep, tint,
  type DomainKey, type PurposeKey, type LinkItem, type AnalyzeDraft,
} from "../types";
import { ApiError, analyze, saveLink, reanalyzeLink, applyReanalysis, patchLink } from "../api";

const STEPS = [
  { icon: Globe, label: "抓取网页" },
  { icon: FileText, label: "提取正文与元信息" },
  { icon: Sparkles, label: "AI 结构化分析" },
];

/**
 * 贴进来的正文至少要这么长。**必须和后端 AnalyzeService.MIN_PASTED_CHARS 一致。**
 *
 * 前端拦一道是为了让用户立刻看到反馈，而不是等一个来回才被告知太短；
 * 但后端那一道不能省——绕过前端直接打接口的情况总是有的。
 */
const MIN_PASTE_CHARS = 50;

/**
 * 分析抽屉。三种模式共用一套界面：
 *
 * <ul>
 *   <li><b>新建</b>（两个 link 都为空）—— 粘一个网址进来，分析，确认，入库。</li>
 *   <li><b>补正文</b>（`fixLink` 非空）—— 给一条已存的记录重跑分析，覆盖它的 AI 判断部分。</li>
 *   <li><b>编辑</b>（`editLink` 非空）—— 改一条已存记录的内容，<b>不调模型</b>。</li>
 * </ul>
 *
 * <p>为什么不拆成三个组件：草稿审核那一整块（标题/摘要/备注三选一/分类/标签）
 * 是<b>一模一样</b>的。复制一份出来，两份迟早会漂——
 * 到时候「新建时能改标签、编辑时改不了」这种 bug 没人会发现。
 * 所以只把「内容从哪儿来」和「结果往哪儿写」这两处分叉。
 *
 * <p>编辑模式的做法是<b>把一条已存记录表示成草稿的形状</b>（见 {@link draftFromLink}），
 * 从而原样复用这一块。代价是草稿里那些只属于分析过程的字段（耗时、token、重试次数）
 * 只能填占位值——**凡是显示它们的区块都要按 `editing` 显式挡掉**，
 * 否则界面会安静地显示「耗时 0.0s、调用 0 次」这种假信息。
 */
type Phase = "choose" | "analyzing" | "ready" | "error";
export type PanelMode = "new" | "fix" | "edit";

interface Props {
  open: boolean;
  url: string;
  /**
   * 非空表示「给这条已存的记录补正文」，而不是新建。
   *
   * <p>面板内部只认它的 `id`（见 `fixId`）。因为 App 那边的 `links` 每次改动
   * 都会重建数组，对象引用一直在变——直接进依赖会让「打字时清空输入框」这种事发生。
   */
  fixLink?: LinkItem | null;
  /** 非空表示「编辑这条已存记录」，同样只认 id。和 `fixLink` 互斥。 */
  editLink?: LinkItem | null;
  onClose: () => void;
  /** mode 由面板告诉 App：新建要插到最前，另外两种都是就地替换。 */
  onSave: (item: LinkItem, mode: PanelMode) => void;
  onNotify: (msg: string, kind?: "ok" | "warn") => void;
}

/**
 * 把一条已存记录表示成「草稿」的形状，好让同一套审核界面复用。
 *
 * <p>只有编辑模式用得上。里面那些分析过程字段填的是占位值——记录里根本没有这些信息，
 * 分析早就结束了，`ai_raw` 里存的也只是当时的输出。所以：
 *
 * <ul>
 *   <li>`bodyChars: 0` —— 让「正文 N 字」那行自己消失（`> 0` 才显示）</li>
 *   <li>`fetchOk: true` + `bodySource: "fetched"` —— 让两条补救提示都不出现。
 *       它们说的是「这次分析怎么样」，而这次没有分析。</li>
 *   <li>`attempts` / token / `elapsedMs` 全 0 —— 用它们的那块折叠面板必须挡掉</li>
 * </ul>
 */
function draftFromLink(link: LinkItem): AnalyzeDraft {
  return {
    url: link.url,
    site: link.site,
    monogram: link.monogram,
    title: link.title,
    summary: link.summary,
    // 记录里这两个是可选的（后端可能没给），草稿里是必填——补上默认值
    summaryLong: link.summaryLong ?? null,
    noteOptions: link.noteOptions,
    domainKey: link.domainKey,
    purposes: link.purposes,
    tags: link.tags,
    contentType: link.contentType,
    confidence: link.confidence,
    needsReview: link.needsReview ?? false,
    fetchOk: true,
    fetchError: null,
    bodyChars: 0,
    metaDescription: null,
    bodySource: "fetched",
    attempts: 0,
    validationErrors: [],
    repairLog: [],
    promptTokens: 0,
    completionTokens: 0,
    elapsedMs: 0,
  };
}

export default function SavePanel({
  open, url, fixLink, editLink, onClose, onSave, onNotify,
}: Props) {
  const [phase, setPhase] = useState<Phase>("analyzing");
  const [step, setStep] = useState(0);
  const [elapsed, setElapsed] = useState(0);
  const [draftId, setDraftId] = useState<string | null>(null);
  const [draft, setDraft] = useState<AnalyzeDraft | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [errorOffline, setErrorOffline] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const [title, setTitle] = useState("");
  const [summary, setSummary] = useState("");
  const [noteIdx, setNoteIdx] = useState(1);
  const [domainKey, setDomainKey] = useState<DomainKey>("other");
  const [purposes, setPurposes] = useState<PurposeKey[]>([]);
  const [tags, setTags] = useState<string[]>([]);
  const [tagInput, setTagInput] = useState("");

  const [attempt, setAttempt] = useState(0);

  /* ── 贴正文补救 ── */
  const [pasteOpen, setPasteOpen] = useState(false);
  /** 输入框里的内容，随打字变化 */
  const [pasteText, setPasteText] = useState("");
  /**
   * 本次分析实际用到的正文。
   *
   * 用 ref 而不是 state，是为了让它<b>不参与 effect 依赖</b>：
   * 如果它是 state 并且列进依赖，那用户每打一个字都会重新触发分析。
   * 现在只有点「用这段正文分析」时才会写它，然后手动 bump attempt 触发重跑。
   */
  const runTextRef = useRef<string | null>(null);
  /** 只用于界面显示（比如进度步骤要不要跳过「抓取网页」） */
  const [usingPaste, setUsingPaste] = useState(false);
  /**
   * 补正文模式下，用户还没决定正文从哪来。
   *
   * 打开就先花一次调用去重新抓取是错的——他很可能是「已经知道抓不到、
   * 手里正拿着正文」才点进来的。让他先选，省一次没意义的调用。
   */
  const [choosing, setChoosing] = useState(false);

  const scrollRef = useRef<HTMLDivElement>(null);

  const fixId = fixLink?.id ?? null;
  const fixing = fixId !== null;
  const editId = editLink?.id ?? null;
  const editing = editId !== null;
  /**
   * 面板正在处理的那条已存记录（补正文或编辑）。新建时为 null。
   *
   * 合成一个变量是因为「备注候选要不要把他原来那句补进去」对两种模式是同一件事，
   * 各写一遍迟早会漏掉一处。
   */
  const target = fixLink ?? editLink ?? null;
  const ready = phase === "ready";
  const pasteLen = pasteText.trim().length;
  const pasteTooShort = pasteLen > 0 && pasteLen < MIN_PASTE_CHARS;

  /**
   * 备注候选。改一条已存记录时，把用户原来挑的那句也列进去。
   *
   * 新分析未必给出更好的备注，而他原来那句是<b>他选过的</b>。
   * 不列出来就等于替他做了「换掉」的决定，而他只会发现「备注怎么变了」。
   *
   * 编辑模式下还有更要紧的一层：他当初改写过的备注根本不在 `noteOptions` 里
   * （那是 AI 给的三句原文）。不补进去的话，一打开面板他看到的是三句别人的话，
   * 自己写的那句已经不见了——而且一保存就真的没了。
   */
  const noteOptions = useMemo(() => {
    const opts = draft?.noteOptions ?? [];
    if (target?.note && !opts.includes(target.note)) {
      return [target.note, ...opts];
    }
    return opts;
  }, [draft, target]);

  /** 「他原来挑的那句」被插到了候选最前面（当前候选里没有同样的一句）。 */
  const oldNotePrepended =
    !!target?.note && noteOptions.length > (draft?.noteOptions.length ?? 0);

  /**
   * 换一个网址（或重新打开面板）时，把上一次用的正文清掉。
   *
   * 必须声明在下面那个分析 effect <b>之前</b>：React 按声明顺序跑 effect，
   * 先清空 ref 再让分析 effect 去读，它拿到的才是 null。
   */
  useEffect(() => {
    runTextRef.current = null;
    setUsingPaste(false);
    setPasteText("");
    setPasteOpen(false);
    // 补正文模式：先让用户选正文从哪来，不要一打开就花掉一次调用
    setChoosing(fixId !== null);
    // 依赖里放 fixId 而不是 fixLink 对象：App 每次改动都会重建 links 数组，
    // 用对象会让这个 effect 在打字时反复触发，把用户贴了一半的正文清掉。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [url, open, fixId]);

  /** 用（或不用）一段正文重新分析。传 null 表示改回自动抓取。 */
  const reanalyzeWith = (text: string | null) => {
    runTextRef.current = text;
    setUsingPaste(text !== null);
    setPasteOpen(false);
    setChoosing(false);
    setAttempt((n) => n + 1);
  };

  /** 补正文时选「先重新抓一次」——抓取失败有时只是当时对方站点在抽风。 */
  const refetch = () => {
    runTextRef.current = null;
    setUsingPaste(false);
    setChoosing(false);
    setAttempt((n) => n + 1);
  };

  /**
   * 贴正文的输入区（按钮 → 输入框 → 提交）。
   *
   * 抽出来是因为它在两个地方用，而两处的场景是同一个：
   *   1. 抓取失败但分析勉强成功 —— 提示「可能不准」，给出口；
   *   2. 分析彻底失败 —— 原来只给「重新分析一次」，是条死路。
   *      模型连续返回坏 JSON 时，贴一段更完整的正文往往就能过。
   *
   * 两处各写一遍的话，字数门槛、取消行为、按钮文案迟早会漂。
   */
  const pastePicker = (idleLabel: string, submitLabel: string) =>
    !pasteOpen ? (
      <button
        onClick={() => setPasteOpen(true)}
        className="mt-2 flex items-center gap-1.5 rounded-md border border-amber-300 bg-amber-100/60 px-2 py-[4px] text-[11.5px] font-medium text-amber-900 transition-colors hover:bg-amber-100 dark:border-amber-800 dark:bg-amber-900/40 dark:text-amber-200 dark:hover:bg-amber-900/70"
      >
        <ClipboardPaste size={11.5} />
        {idleLabel}
      </button>
    ) : (
      <div className="mt-2">
        <textarea
          value={pasteText}
          onChange={(e) => setPasteText(e.target.value)}
          rows={5}
          autoFocus
          placeholder="把页面的主要内容复制到这里。在这个网页上全选正文、复制，再粘进来就行。"
          className="w-full resize-y rounded-md border border-amber-200 bg-surface px-2 py-1.5 text-[12px] leading-[1.6] text-ink outline-none placeholder:text-ink3 focus:border-accent dark:border-amber-900"
        />
        <div className="mt-1.5 flex items-center gap-2">
          <button
            onClick={() => reanalyzeWith(pasteText.trim())}
            disabled={pasteLen < MIN_PASTE_CHARS}
            className={cn(
              "flex items-center gap-1.5 rounded-md px-2.5 py-[5px] text-[11.5px] font-medium text-white transition-opacity",
              pasteLen >= MIN_PASTE_CHARS
                ? "bg-accent hover:opacity-90"
                : "cursor-not-allowed bg-ink3/40",
            )}
          >
            <Sparkles size={11} />
            {submitLabel}
          </button>
          <button
            onClick={() => setPasteOpen(false)}
            className="text-[11.5px] text-ink3 transition-colors hover:text-ink"
          >
            取消
          </button>
          <span
            className={cn(
              "ml-auto text-[11px] tabular-nums",
              pasteTooShort ? "text-amber-700 dark:text-amber-400" : "text-ink3",
            )}
          >
            {pasteLen} / {MIN_PASTE_CHARS} 字
          </span>
        </div>
        <p className="mt-1.5 text-[11px] leading-relaxed text-ink3">
          贴了正文就不会再去抓这个页面——能抓到的话刚才就抓到了，不用再等一遍。
        </p>
      </div>
    );

  /* ── 打开即分析 ── */

  useEffect(() => {
    if (!open) return;

    /*
     * 编辑模式：不调模型，也不该走「分析中」那套动画。
     * 内容就在记录里，直接摆出来让用户改。
     *
     * 这一步是「把记录表示成草稿」的全部代价所在——草稿里那些分析过程字段
     * 只能填占位值，所以下面凡是显示它们的区块都要按 editing 挡掉。
     */
    if (editing && editLink) {
      const d0 = draftFromLink(editLink);
      setPhase("ready");
      setStep(STEPS.length);
      setElapsed(0);
      setDraft(d0);
      setDraftId(null);
      setError(null);
      setErrorOffline(false);
      setSaveError(null);
      setTitle(d0.title);
      setSummary(d0.summary ?? "");
      /*
       * 默认选他原来那句。
       *
       * 编辑模式的默认行为必须是<b>保持原样</b>——他只是来改一两个字段的，
       * 顺手把备注换成第二句，就成了「改了他没想改的东西」。
       * 找不到就落回 0，而 0 正好是 noteOptions 补进去的「他原来写的」那句。
       */
      const opts = d0.noteOptions;
      const mine = editLink.note ?? "";
      setNoteIdx(opts.includes(mine) ? opts.indexOf(mine) : 0);
      setDomainKey(d0.domainKey);
      setPurposes(d0.purposes);
      setTags(d0.tags);
      return;
    }

    // 补正文模式下用户还没选正文从哪来：停在这一步，不要替他花一次调用。
    if (fixing && choosing) {
      setPhase("choose");
      setStep(0);
      setElapsed(0);
      setDraft(null);
      setDraftId(null);
      setError(null);
      setErrorOffline(false);
      setSaveError(null);
      return;
    }

    let cancelled = false;

    setPhase("analyzing");
    setStep(0);
    setElapsed(0);
    setDraft(null);
    setDraftId(null);
    setError(null);
    setErrorOffline(false);
    setSaveError(null);

    const startedAt = Date.now();
    const tick = setInterval(
      () => setElapsed(Math.floor((Date.now() - startedAt) / 1000)),
      250,
    );

    /*
     * 下面两个定时器只是「进度感」，不是真实进度。
     *
     * 后端的 /api/analyze 是一次阻塞调用，中途不吐进度，所以拿不到真实阶段。
     * 折中做法：让前三步按大致节奏往前走，但**永远不会自动走到完成**——
     * 最后一步会一直转圈，直到响应真的回来。这样既不会假装提前完成，
     * 用户也能看到它确实在动。旁边还有真实秒数，超时的时候他能判断。
     *
     * 抓取实测 0.5–20s，AI 2–15s，所以第二步之后基本就停在「AI 分析」了，
     * 这个停留恰好和真实耗时最长的阶段对得上。
     *
     * 贴正文时没有抓取这一步，直接从前两步开始，否则会看到一个假的「抓取网页」在转。
     */
    const skipFetch = runTextRef.current !== null;
    const s1 = setTimeout(() => setStep(skipFetch ? 2 : 1), skipFetch ? 200 : 1100);
    const s2 = setTimeout(() => setStep(2), skipFetch ? 300 : 2600);

    (async () => {
      try {
        // 补正文走另一条接口：网址从服务端记录里取，结果也只回草稿、不落库。
        const res = fixing
          ? await reanalyzeLink(fixId, runTextRef.current ?? undefined)
          : await analyze(url, runTextRef.current ?? undefined);
        if (cancelled) return;
        const d = res.draft;
        setDraftId(res.draftId);
        setDraft(d);
        setTitle(d.title);
        setSummary(d.summary ?? "");
        /*
         * 默认选中哪一句。
         *
         * 补正文时如果新候选里没有他原来那句（我们会把它插在最前面），
         * 就默认选回原来那句——「修正文」这个动作的默认行为应该是<b>保留</b>，
         * 而不是顺手把他的备注换掉。
         */
        const prepended = fixing && !!fixLink?.note && !d.noteOptions.includes(fixLink.note);
        setNoteIdx(prepended ? 0 : d.noteOptions.length >= 2 ? 1 : 0);
        setDomainKey(d.domainKey);
        setPurposes(d.purposes);
        setTags(d.tags);
        setStep(STEPS.length);
        setPhase("ready");
      } catch (e) {
        if (cancelled) return;
        const err = e as ApiError;
        setError(err.message);
        setErrorOffline(err.offline);
        setPhase("error");
      } finally {
        clearInterval(tick);
        clearTimeout(s1);
        clearTimeout(s2);
      }
    })();

    return () => {
      cancelled = true;
      clearInterval(tick);
      clearTimeout(s1);
      clearTimeout(s2);
    };
  }, [open, url, attempt, fixing, choosing, fixId, fixLink?.note, editing, editId]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    if (open) window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: 0 });
  }, [phase]);

  if (!open) return null;

  const d = domainOf(domainKey);

  /* ── 保存 ── */

  const save = async () => {
    if (!draft || saving) return;
    // 编辑模式不调模型，所以没有草稿 id；另外两种必须有
    if (!editing && !draftId) return;
    setSaving(true);
    setSaveError(null);

    /*
     * 编辑模式走 PATCH，不走 /apply。
     *
     * 这个分叉是这次改动里最要紧的一处。两种模式看起来都是「改一条已存记录」，
     * 但性质相反：
     *
     *   - 补正文 = AI 拿到更好的输入重新判了一次。用户没有表达偏好，
     *     所以**不能**往 correction 表记东西（记了就是让模型照着自己的影子学习）。
     *   - 编辑 = 用户亲手把 AI 的判断改成了他要的。这正是 correction 表
     *     存在的理由，必须记下来。
     *
     * 走 PATCH 就自动拿到了后者：白名单里 title / summary_short / note /
     * 领域 / 用途都在 TRACKED 里，改动会留痕。
     */
    if (editing && editId) {
      try {
        const item = await patchLink(editId, {
          title: title.trim() || draft.title,
          summaryShort: summary.trim(),
          note: noteOptions[noteIdx] ?? noteOptions[0],
          domainKey,
          purposes,
          tags,
        });
        onSave(item, "edit");
      } catch (e) {
        setSaveError((e as ApiError).message);
      } finally {
        setSaving(false);
      }
      return;
    }

    // 走到这里说明不是编辑模式，那就必须有草稿 id（上面已经挡过一次，这里是为了收窄类型）
    if (!draftId) return;

    const payload = {
      draftId,
      title: title.trim() || draft.title,
      summary: summary.trim(),
      summaryLong: draft.summaryLong,
      noteOptions,
      note: noteOptions[noteIdx] ?? noteOptions[0] ?? null,
      domainKey,
      purposes,
      tags,
      contentType: draft.contentType,
      confidence: draft.confidence,
      needsReview: draft.needsReview,
    };
    try {
      const item = fixing
        ? await applyReanalysis(fixId, payload)
        : await saveLink(payload);
      onSave(item, fixing ? "fix" : "new");
    } catch (e) {
      const err = e as ApiError;
      if (err.status === 409) {
        setSaveError("这个网址已经存过了，不用再存一遍");
        onNotify("这个网址已经存过了", "warn");
      } else if (err.status === 410) {
        // 草稿在服务端过期了（进程重启或超过 50 条被挤掉）
        setSaveError("分析草稿已过期，重新分析一次就好");
      } else {
        setSaveError(err.message);
      }
    } finally {
      setSaving(false);
    }
  };

  const addTag = () => {
    const t = tagInput.trim();
    if (t && !tags.includes(t)) setTags([...tags, t]);
    setTagInput("");
  };

  return (
    <div className="fixed inset-0 z-50 flex justify-end">
      <div
        className="fadein absolute inset-0 bg-black/25 backdrop-blur-[2px]"
        onClick={onClose}
      />

      <div className="slidein relative flex h-full w-[468px] flex-col border-l border-line bg-surface shadow-[-24px_0_60px_-30px_rgba(0,0,0,0.3)]">
        <header className="flex items-center gap-2 border-b border-line px-5 py-3.5">
          <span className="text-[13.5px] font-medium text-ink">
            {editing ? "编辑" : fixing ? "补正文" : "新收藏"}
          </span>

          {phase === "choose" && (
            <span className="flex items-center gap-1 rounded-md bg-amber-50 px-1.5 py-[2px] text-[11px] font-medium text-amber-700 dark:bg-amber-950 dark:text-amber-400">
              <AlertTriangle size={10.5} />
              待补
            </span>
          )}
          {phase === "analyzing" && !editing && (
            <span className="flex items-center gap-1.5 text-[11.5px] text-ink3">
              <Loader2 size={11} className="animate-spin" />
              分析中 {elapsed > 0 && `· ${elapsed}s`}
            </span>
          )}
          {phase === "ready" && draft && !editing && (
            <>
              <span className="flex items-center gap-1 rounded-md bg-emerald-50 px-1.5 py-[2px] text-[11px] font-medium text-emerald-700 dark:bg-emerald-950 dark:text-emerald-400">
                <Check size={10.5} />
                分析完成
              </span>
              {draft.attempts > 1 && (
                <span
                  className="rounded-md bg-sunken px-1.5 py-[2px] text-[11px] text-ink2"
                  title={draft.repairLog.join("\n")}
                >
                  自动修正过
                </span>
              )}
            </>
          )}
          {phase === "error" && (
            <span className="flex items-center gap-1 rounded-md bg-amber-50 px-1.5 py-[2px] text-[11px] font-medium text-amber-700 dark:bg-amber-950 dark:text-amber-400">
              <AlertTriangle size={10.5} />
              没成功
            </span>
          )}

          <button
            onClick={onClose}
            className="ml-auto grid h-6 w-6 place-items-center rounded-md text-ink3 transition-colors hover:bg-sunken hover:text-ink"
          >
            <X size={14} />
          </button>
        </header>

        <div ref={scrollRef} className="flex-1 overflow-y-auto px-5 py-4">
          <div className="flex items-center gap-2 text-[11.5px] text-ink3">
            <Globe size={11.5} />
            <span className="truncate">{url}</span>
          </div>

          {/* ── 分析中 ── */}
          {/*
            编辑模式要挡掉。phase 的初始值是 "analyzing"，而 effect 在首帧渲染之后
            才跑——所以编辑模式会先闪一下骨架屏再跳到内容。挡掉之后首帧是空的，
            一帧的空白比一帧的「分析中…」诚实。
          */}
          {phase === "analyzing" && !editing && (
            <>
              <div className="mt-4 space-y-2">
                {STEPS.map((s, i) => {
                  // 贴正文时没有「抓取网页」这一步，但也不该把它藏掉——
                  // 藏掉会让步骤数从 3 变 2，看起来像出错了。改成显示「用了你贴的正文」并直接打勾。
                  const skipped = i === 0 && usingPaste;
                  const done = skipped || i < step;
                  return (
                    <div
                      key={s.label}
                      className={cn(
                        "flex items-center gap-2 text-[12px] transition-colors",
                        done ? "text-ink2" : i === step ? "text-ink" : "text-ink3/60",
                      )}
                    >
                      {done ? (
                        <Check size={12} className="text-emerald-600" />
                      ) : i === step ? (
                        <Loader2 size={12} className="animate-spin text-accent" />
                      ) : (
                        <span className="h-3 w-3 rounded-full border border-line-strong" />
                      )}
                      {skipped ? "用你粘贴的正文（跳过抓取）" : s.label}
                    </div>
                  );
                })}
              </div>

              <div className="mt-5 h-[112px] rounded-lg bg-sunken pulse-soft" />
              <div className="mt-4 h-4 w-2/3 rounded bg-sunken pulse-soft" />
              <div className="mt-2.5 h-3 w-full rounded bg-sunken pulse-soft" />
              <div className="mt-2 h-3 w-4/5 rounded bg-sunken pulse-soft" />

              {elapsed >= 15 && (
                <p className="mt-4 text-[11.5px] leading-relaxed text-ink3">
                  有点慢——大概率是目标站点响应慢或抓取超时。
                  后端会在 {20}s 左右放弃抓取，改用页面描述继续判断，不用管它。
                </p>
              )}
            </>
          )}

          {/* ── 补正文：先选正文从哪来 ── */}
          {phase === "choose" && (
            <div className="rise mt-4">
              <div className="rounded-lg border border-amber-200 bg-amber-50/60 px-3 py-2.5 dark:border-amber-900 dark:bg-amber-950/40">
                <div className="flex items-start gap-2">
                  <AlertTriangle size={12} className="mt-[2px] shrink-0 text-amber-700" />
                  <p className="text-[11.5px] leading-relaxed text-amber-800 dark:text-amber-300">
                    这条当时没抓到正文，判断是照着页面描述推测的。
                    把正文补上重跑一次，分类和备注会准很多。
                  </p>
                </div>
              </div>

              <p className="mt-4 text-[12.5px] font-medium text-ink">把页面正文贴进来</p>
              <p className="mt-0.5 text-[11px] leading-relaxed text-ink3">
                在那个网页上全选正文、复制，再粘到这里。贴了就不会再去抓——能抓到的话当时就抓到了。
              </p>

              <textarea
                value={pasteText}
                onChange={(e) => setPasteText(e.target.value)}
                rows={7}
                autoFocus
                placeholder="把页面的主要内容复制到这里…"
                className="mt-2.5 w-full resize-y rounded-md border border-line bg-surface px-2.5 py-2 text-[12px] leading-[1.65] text-ink outline-none transition-colors placeholder:text-ink3 focus:border-accent"
              />
              <div className="mt-1.5 flex items-center gap-2.5">
                <button
                  onClick={() => reanalyzeWith(pasteText.trim())}
                  disabled={pasteLen < MIN_PASTE_CHARS}
                  className={cn(
                    "flex items-center gap-1.5 rounded-lg px-3.5 py-[7px] text-[12.5px] font-medium text-white transition-opacity",
                    pasteLen >= MIN_PASTE_CHARS
                      ? "bg-accent hover:opacity-90"
                      : "cursor-not-allowed bg-ink3/40",
                  )}
                >
                  <Sparkles size={11.5} />
                  用这段正文重跑
                </button>
                <span
                  className={cn(
                    "ml-auto text-[11px] tabular-nums",
                    pasteTooShort ? "text-amber-700 dark:text-amber-400" : "text-ink3",
                  )}
                >
                  {pasteLen} / {MIN_PASTE_CHARS} 字
                </span>
              </div>

              <div className="mt-4 border-t border-line pt-3.5">
                <button
                  onClick={refetch}
                  className="flex items-center gap-1.5 text-[11.5px] font-medium text-accent transition-opacity hover:opacity-70"
                >
                  <RotateCcw size={11.5} />
                  手里没有正文，先重新抓一次
                </button>
                <p className="mt-1.5 text-[11px] leading-relaxed text-ink3">
                  抓取失败有时只是暂时的（对方站点当时在抽风），隔几天再试可能就好了。
                </p>
              </div>
            </div>
          )}

          {/* ── 失败 ── */}
          {phase === "error" && (
            <div className="mt-6">
              <div className="rounded-lg border border-amber-200 bg-amber-50/60 p-4 dark:border-amber-900 dark:bg-amber-950/40">
                <div className="flex items-center gap-2">
                  {errorOffline ? (
                    <WifiOff size={13} className="text-amber-700" />
                  ) : (
                    <AlertTriangle size={13} className="text-amber-700" />
                  )}
                  <span className="text-[12.5px] font-medium text-amber-800 dark:text-amber-300">
                    {errorOffline ? "连不上后端" : "分析没成功"}
                  </span>
                </div>
                <p className="mt-2 text-[12px] leading-relaxed text-amber-800/90 dark:text-amber-300/90">
                  {error}
                </p>
                {errorOffline && (
                  <p className="mt-2 text-[11.5px] leading-relaxed text-amber-700/80 dark:text-amber-400/80">
                    在 server/ 目录（和 web/ 平级）跑 <code className="rounded bg-amber-100 px-1 dark:bg-amber-900">./mvn.sh spring-boot:run</code>
                  </p>
                )}
              </div>

              <button
                onClick={() =>
                  fixing ? setChoosing(true) : setAttempt((n) => n + 1)
                }
                className="mt-4 flex items-center gap-1.5 rounded-lg border border-line px-3.5 py-[7px] text-[12.5px] text-ink2 transition-colors hover:border-line-strong hover:text-ink"
              >
                {fixing ? <ClipboardPaste size={12} /> : <RotateCcw size={12} />}
                {fixing ? "换贴正文试试" : "重新分析一次"}
              </button>

              {/*
                新收藏分析失败时，除了重试还得给「贴正文」这条出口。
                重试大概率是同样的结果——同一个网址、同一份提示词，
                模型这次返回坏 JSON，下次多半也是。而用户手里那段正文是新信息，
                能真正改变结果。

                后端连不上时不给这个入口：贴了也发不出去，只会让他白忙一次。
              */}
              {!fixing && !errorOffline && (
                <div className="mt-3 rounded-lg border border-amber-200 bg-amber-50/60 px-2.5 py-2 dark:border-amber-900 dark:bg-amber-950/40">
                  <p className="text-[11.5px] leading-relaxed text-amber-800 dark:text-amber-300">
                    也可以把页面正文贴进来再试一次。有些站点（要登录的、纯前端渲染的）
                    抓不到正文，而你自己的浏览器里明明能读到。
                  </p>
                  {pastePicker("贴一段正文再试一次", "用这段正文分析")}
                </div>
              )}
            </div>
          )}

          {/* ── 成功 ── */}
          {phase === "ready" && draft && (
            <div className="rise">
              {/*
                补正文时先说清楚这次会动到什么。
                用户点「补正文」是来修正文的，不是来让 AI 重写整张卡片的。
                不说明就应用，等于把他之前手动改过的分类无声覆盖掉——
                而他只会发现「分类怎么变了」，找不到原因。
                「加星、已用、收藏时间都不动」这句是真正的安抚：那些是用户和这条记录的关系，
                不该被一次重分析碰到。
              */}
              {fixing && (
                <p className="mt-4 rounded-lg bg-sunken px-2.5 py-2 text-[11.5px] leading-relaxed text-ink2">
                  应用后会重写这条的标题、摘要、备注、分类和标签；
                  <span className="font-medium text-ink">加星、已用、收藏时间都不动</span>。
                  有想改的地方，下面直接改。
                </p>
              )}

              {/*
                编辑模式先说清边界。用户点「编辑」多半是想改点东西，
                其中一定有人是想改网址——不说的话他会找一圈输入框，最后以为功能坏了。
                顺带给出正确的做法（重新存一条），而不是只留一句「不支持」。

                写短：这段每次编辑都会出现，看过两遍就是噪音了。
                完整理由在 README 里。
              */}
              {editing && (
                <p className="mt-4 rounded-lg bg-sunken px-2.5 py-2 text-[11.5px] leading-relaxed text-ink2">
                  改的是标题、摘要、备注、分类和标签。
                  <span className="font-medium text-ink">网址改不了</span>——
                  它决定当时抓的是哪个页面。真要换，重新存一条、把这条删掉。
                </p>
              )}

              <div
                className="mt-4 grid h-[112px] place-items-center rounded-lg"
                style={{ background: tint(d.color) }}
              >
                <span
                  className="text-[30px] font-semibold tracking-tight opacity-80"
                  style={{ color: deep(d.color) }}
                >
                  {draft.monogram}
                </span>
              </div>

              {/*
                抓取失败时给的是补救入口，而不是一句「可能不准」。
                这是全应用唯一一个「用户遇到问题却无解」的场景：
                SPA、需登录、Cloudflare 的站点抓不到正文是常态，而他自己的浏览器里
                明明能读到内容。让他贴进来，我们就能照常分析。
              */}
              {draft.bodySource === "fetched" && !draft.fetchOk && (
                <div className="mt-3 rounded-lg border border-amber-200 bg-amber-50/60 px-2.5 py-2 dark:border-amber-900 dark:bg-amber-950/40">
                  <div className="flex items-start gap-2">
                    <AlertTriangle size={12} className="mt-[2px] shrink-0 text-amber-700" />
                    <p className="flex-1 text-[11.5px] leading-relaxed text-amber-800 dark:text-amber-300">
                      正文没抓到（{draft.fetchError}），下面的判断是基于页面描述推测的，可能不准。
                    </p>
                  </div>

                  {pastePicker("贴一段正文重新分析", "用这段正文分析")}
                </div>
              )}

              {/* 正文是用户贴的：说清楚现状，并留一个改回自动抓取的出口 */}
              {draft.bodySource === "pasted" && (
                <div className="mt-3 flex items-start gap-2 rounded-lg bg-sunken px-2.5 py-2">
                  <ClipboardPaste size={12} className="mt-[2px] shrink-0 text-ink3" />
                  <p className="text-[11.5px] leading-relaxed text-ink2">
                    正文用的是你粘贴的内容（{draft.bodyChars} 字），没有去抓页面。
                    <button
                      onClick={() => reanalyzeWith(null)}
                      className="ml-1 text-accent underline decoration-dotted underline-offset-2 transition-opacity hover:opacity-75"
                    >
                      改回自动抓取
                    </button>
                  </p>
                </div>
              )}

              <input
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                className="mt-3.5 w-full rounded-md border border-transparent bg-transparent font-serif text-[16px] leading-snug text-ink outline-none transition-colors hover:border-line focus:border-accent focus:bg-surface"
              />

              <textarea
                value={summary}
                onChange={(e) => setSummary(e.target.value)}
                rows={2}
                className="mt-1.5 w-full resize-none rounded-md border border-transparent bg-transparent text-[12.5px] leading-[1.65] text-ink2 outline-none transition-colors hover:border-line focus:border-accent focus:bg-surface"
              />

              <div className="mt-2 flex items-center gap-2 text-[11px] text-ink3">
                <span>{draft.contentType}</span>
                <span>·</span>
                <span>置信度 {Math.round(draft.confidence * 100)}%</span>
                {draft.bodyChars > 0 && (
                  <>
                    <span>·</span>
                    <span>正文 {draft.bodyChars} 字</span>
                  </>
                )}
              </div>

              {noteOptions.length > 0 && (
                <div className="mt-5 border-t border-line pt-4">
                  <p className="text-[12.5px] font-medium text-ink">挑一句当备注</p>
                  <p className="mt-0.5 text-[11px] text-ink3">
                    {oldNotePrepended
                      ? "第一句是你原来选的，后面三句是这次新写的"
                      : editing
                        ? "这几句是存的时候 AI 写的，换一句就行"
                        : "AI 写好了三句，选一个就行，不用自己想"}
                  </p>

                  <div className="mt-2.5 space-y-1.5">
                    {noteOptions.map((opt, i) => {
                      const on = i === noteIdx;
                      const mine = oldNotePrepended && i === 0;
                      return (
                        <button
                          key={opt}
                          onClick={() => setNoteIdx(i)}
                          className={cn(
                            "flex w-full items-start gap-2.5 rounded-lg border p-2.5 text-left transition-all duration-150",
                            on
                              ? "border-accent bg-accentsoft"
                              : "border-line hover:border-line-strong",
                          )}
                        >
                          <span
                            className={cn(
                              "mt-[3px] grid h-[13px] w-[13px] shrink-0 place-items-center rounded-full border transition-colors",
                              on ? "border-accent bg-accent" : "border-line-strong",
                            )}
                          >
                            {on && <Check size={8.5} className="text-white" strokeWidth={3} />}
                          </span>
                          <span
                            className={cn(
                              "text-[12.5px] leading-[1.6]",
                              on ? "text-accent" : "text-ink2",
                            )}
                          >
                            {mine && (
                              /*
                               * ink2 而不是 ink3：见 LinkEntry 里「已用」角标的说明。
                               * 这个角标是「你那句话被保住了」的可见证据，10px 上必须读得清。
                               */
                              <span className="mr-1.5 rounded-[4px] bg-sunken px-1 py-[1px] align-[1px] text-[10px] text-ink2">
                                你原来写的
                              </span>
                            )}
                            {opt}
                          </span>
                        </button>
                      );
                    })}
                  </div>
                </div>
              )}

              <div className="mt-5 border-t border-line pt-4">
                <p className="text-[12.5px] font-medium text-ink">分类</p>

                <div className="mt-2.5 space-y-3">
                  <div className="flex items-start gap-3">
                    <span className="w-9 shrink-0 pt-[3px] text-[11.5px] text-ink3">领域</span>
                    <div className="flex flex-wrap gap-1.5">
                      {DOMAINS.map((dm) => {
                        const on = dm.key === domainKey;
                        return (
                          <button
                            key={dm.key}
                            onClick={() => setDomainKey(dm.key)}
                            className={cn(
                              "rounded-md border px-2 py-[3px] text-[11.5px] transition-colors",
                              on ? "border-transparent font-medium" : "border-line text-ink2 hover:border-line-strong",
                            )}
                            style={on ? { background: tint(dm.color), color: deep(dm.color) } : undefined}
                          >
                            {dm.name}
                          </button>
                        );
                      })}
                    </div>
                  </div>

                  <div className="flex items-start gap-3">
                    <span className="w-9 shrink-0 pt-[3px] text-[11.5px] text-ink3">用途</span>
                    <div className="flex flex-wrap gap-1.5">
                      {PURPOSES.map((p) => {
                        const on = purposes.includes(p.key);
                        return (
                          <button
                            key={p.key}
                            onClick={() =>
                              setPurposes(
                                on ? purposes.filter((x) => x !== p.key) : [...purposes, p.key],
                              )
                            }
                            className={cn(
                              "rounded-md border px-2 py-[3px] text-[11.5px] transition-colors",
                              on
                                ? "border-accent bg-accentsoft font-medium text-accent"
                                : "border-line text-ink2 hover:border-line-strong",
                            )}
                          >
                            {p.name}
                          </button>
                        );
                      })}
                    </div>
                  </div>

                  <div className="flex items-start gap-3">
                    <span className="w-9 shrink-0 pt-[3px] text-[11.5px] text-ink3">标签</span>
                    <div className="flex flex-1 flex-wrap items-center gap-1.5">
                      {tags.map((t) => (
                        <span
                          key={t}
                          className="group flex items-center gap-1 rounded-md bg-sunken px-2 py-[3px] text-[11.5px] text-ink2"
                        >
                          {t}
                          <button
                            onClick={() => setTags(tags.filter((x) => x !== t))}
                            className="text-ink3 transition-colors hover:text-ink"
                          >
                            <X size={9.5} />
                          </button>
                        </span>
                      ))}
                      <span className="flex items-center gap-1">
                        <input
                          value={tagInput}
                          onChange={(e) => setTagInput(e.target.value)}
                          onKeyDown={(e) => e.key === "Enter" && addTag()}
                          placeholder="加标签"
                          className="w-[68px] rounded-md border border-dashed border-line-strong bg-transparent px-1.5 py-[3px] text-[11.5px] text-ink outline-none placeholder:text-ink3 focus:border-accent"
                        />
                        <button
                          onClick={addTag}
                          className="grid h-[22px] w-[22px] place-items-center rounded-md border border-dashed border-line-strong text-ink3 transition-colors hover:border-accent hover:text-accent"
                        >
                          <Plus size={11} />
                        </button>
                      </span>
                    </div>
                  </div>
                </div>
              </div>

              {/*
                分析实况。放成折叠的，平时不占地方；
                但用户想知道「这次为什么慢」「花了多少钱」时能翻出来。

                编辑模式必须挡掉：这次没有分析，草稿里那几个字段是占位值，
                展开会看到「耗时 0.0s、模型调用 0 次、token 0」——
                界面安静地显示假信息，比不显示更糟。
              */}
              {!editing && (
              <details className="mt-5 border-t border-line pt-3.5">
                <summary className="cursor-pointer text-[11.5px] text-ink3 transition-colors hover:text-ink2">
                  分析详情
                </summary>
                <dl className="mt-2.5 space-y-1 text-[11.5px] text-ink3">
                  <Row label="耗时" value={`${(draft.elapsedMs / 1000).toFixed(1)}s`} />
                  <Row
                    label="正文来源"
                    value={
                      draft.bodySource === "pasted"
                        ? `你粘贴的 ${draft.bodyChars} 字`
                        : draft.fetchOk
                          ? `自动抓取 ${draft.bodyChars} 字`
                          : "没抓到"
                    }
                  />
                  <Row label="模型调用" value={`${draft.attempts} 次`} />
                  <Row label="token" value={`输入 ${draft.promptTokens} · 输出 ${draft.completionTokens}`} />
                  {draft.needsReview && draft.validationErrors.length > 0 && (
                    <div className="pt-1">
                      <dt className="text-ink3">仍存在的问题</dt>
                      <dd className="mt-0.5 space-y-0.5">
                        {draft.validationErrors.map((v) => (
                          <p key={v} className="leading-relaxed text-amber-700 dark:text-amber-400">
                            {v}
                          </p>
                        ))}
                      </dd>
                    </div>
                  )}
                  {draft.repairLog.length > 0 && (
                    <div className="pt-1">
                      <dt className="text-ink3">自动修正原因</dt>
                      <dd className="mt-0.5 space-y-0.5">
                        {draft.repairLog.map((r) => (
                          <p key={r} className="leading-relaxed text-amber-700 dark:text-amber-400">
                            {r}
                          </p>
                        ))}
                      </dd>
                    </div>
                  )}
                </dl>
              </details>
              )}
            </div>
          )}
        </div>

        <footer className="border-t border-line px-5 py-3.5">
          {saveError && (
            <p className="mb-2.5 flex items-start gap-1.5 text-[11.5px] leading-relaxed text-amber-700 dark:text-amber-400">
              <AlertTriangle size={11.5} className="mt-[2px] shrink-0" />
              {saveError}
            </p>
          )}
          <div className="flex items-center gap-2.5">
            <button
              onClick={() => void save()}
              disabled={!ready || saving}
              className={cn(
                "flex items-center gap-1.5 rounded-lg px-4 py-[7px] text-[12.5px] font-medium text-white transition-opacity",
                ready && !saving ? "bg-accent hover:opacity-90" : "cursor-not-allowed bg-ink3/40",
              )}
            >
              {saving && <Loader2 size={11.5} className="animate-spin" />}
              {saving ? "处理中" : editing ? "保存修改" : fixing ? "用它替换" : "就这样存"}
            </button>
            <button
              onClick={onClose}
              className="ml-auto text-[12px] text-ink3 transition-colors hover:text-ink"
            >
              {fixing || editing ? "算了" : "全部跳过"}
            </button>
          </div>
        </footer>
      </div>
    </div>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex gap-2">
      <dt className="w-16 shrink-0">{label}</dt>
      <dd className="text-ink2">{value}</dd>
    </div>
  );
}

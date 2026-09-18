import { useEffect, useState } from "react";
import { X, Pencil, Trash2, Loader2, Check, Merge } from "lucide-react";
import { cn } from "../lib/utils";
import {
  ApiError, deleteTag, mergeTags, renameTag,
  type TagItem, type TagMutationResult,
} from "../api";

interface Props {
  /** 全部标签（服务端给的清单，带条数） */
  tags: TagItem[];
  onClose: () => void;
  /**
   * 改完之后通知调用方。**调用方要整体重拉列表和标签**，不要在这里局部改。
   *
   * 改名 / 合并 / 删除动的都是「几十条记录的 tags 数组」这一类跨行数据，
   * 服务端一条 SQL 改完，前端若要在本地模仿同一套改动，等于把那套逻辑抄第二遍——
   * 必然漂，而且漂了之后用户看到的是「明明改了，列表上还是旧的」。
   * 重拉一次列表对个人库的量级来说是零成本。
   */
  onChanged: (message: string) => void;
}

/**
 * 标签管理：改名 / 合并 / 删除（R-05 的下半截）。
 *
 * <p>三件事都是**跨行读改写**，只能由服务端做，所以这里只负责收集意图、
 * 把服务端的「动了多少条」翻译成一句人话。
 *
 * <p>合并和删除分开给，不合并成一个「整理」操作：它们的后果完全不同——
 * 合并是「这些名字其实是一回事」，删掉是「这个标签没用了」。
 * 合成一个按钮的话，用户每次都要先想清楚自己在做哪一种。
 */
export default function TagManager({ tags, onClose, onChanged }: Props) {
  /** 正在改名的标签（同时只改一个：两个输入框各改各的，容易点错保存） */
  const [renaming, setRenaming] = useState<string | null>(null);
  const [renameValue, setRenameValue] = useState("");

  /** 勾选待合并的标签 */
  const [selected, setSelected] = useState<string[]>([]);
  const [mergeTarget, setMergeTarget] = useState("");

  /** 待确认删除的标签。删除不可撤销，所以要两下 */
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null);

  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  /** Esc = 关掉。抽屉里的所有面板都该这样，不然只能去找那个叉。 */
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);

  /* 一次异步动作的统一外壳：置忙、清错、抓错。三个操作共用，免得抄三遍 try/catch。 */
  const run = async (
    action: () => Promise<TagMutationResult>,
    ok: (affected: number) => string,
  ) => {
    setBusy(true);
    setErr(null);
    try {
      const r = await action();
      onChanged(ok(r.affected));
      onClose();
    } catch (e) {
      // 失败不关面板：他要能就着这份清单再试一次，而不是从头勾一遍
      setErr((e as ApiError).message);
    } finally {
      setBusy(false);
    }
  };

  const submitRename = (from: string) => {
    const to = renameValue.trim();
    if (!to) {
      setErr("新名字不能为空");
      return;
    }
    if (to === from) {
      setRenaming(null);
      return;
    }
    /*
     * 撞上已有名字要拦住，而且**提示去用合并**。
     * 这两种意图服务端都能表达，但后果不同：改名是「这一批都属于同一个新名字」，
     * 合并是「两个名字并成一个（并完去重）」。让用户自己选，别替他猜。
     */
    if (tags.some((t) => t.name === to)) {
      setErr(`已经有「${to}」了。要把两个合成一个，请用上面的「合并」`);
      return;
    }
    void run(() => renameTag(from, to), (n) => `已改名为「${to}」，${n} 条记录跟着改了`);
  };

  const submitMerge = () => {
    const target = mergeTarget.trim();
    if (!target) {
      setErr("先填一个要合并到的名字");
      return;
    }
    if (selected.includes(target)) {
      setErr("合并到的那个不能同时是被合并的，先取消勾选它");
      return;
    }
    void run(
      () => mergeTags(selected, target),
      (n) => `已把 ${selected.length} 个标签并到「${target}」，动了 ${n} 条记录`,
    );
  };

  const submitDelete = (name: string) => {
    void run(() => deleteTag(name), (n) => `已删掉标签「${name}」，${n} 条记录不再带它`);
  };

  /** 勾选框。合并是唯一需要多选的操作，所以勾选只在「准备合并」这件事里有意义。 */
  const toggleSelect = (name: string) => {
    setErr(null);
    setSelected((prev) =>
      prev.includes(name) ? prev.filter((x) => x !== name) : [...prev, name],
    );
  };

  /** 点删除：第一下只是进入确认态，3 秒没第二下就退出来——和列表里删记录一个规矩 */
  const askDelete = (name: string) => {
    if (confirmDelete === name) {
      setConfirmDelete(null);
      submitDelete(name);
      return;
    }
    setConfirmDelete(name);
    setTimeout(() => setConfirmDelete((cur) => (cur === name ? null : cur)), 3000);
  };

  return (
    <div
      className="fadein fixed inset-0 z-[55] flex items-center justify-center bg-black/25 px-4"
      onMouseDown={(e) => {
        // 点遮罩关掉，但点面板里不能关——否则改一半被关掉很气人
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div className="slidein w-full max-w-[480px] overflow-hidden rounded-xl border border-line bg-surface shadow-[0_24px_60px_-20px_rgba(0,0,0,0.35)]">
        {/* ── 头 ── */}
        <div className="flex items-start gap-3 border-b border-line px-4 py-3">
          <div className="min-w-0 flex-1">
            <h2 className="text-[14px] font-medium text-ink">管理标签</h2>
            <p className="mt-0.5 text-[11.5px] leading-relaxed text-ink3">
              改名 / 合并 / 删除。三个都只动标签，不动收藏本身。
            </p>
          </div>
          <button
            onClick={onClose}
            title="关闭"
            className="grid h-[24px] w-[24px] shrink-0 place-items-center rounded-md text-ink3 transition-colors hover:bg-sunken hover:text-ink"
          >
            <X size={13} />
          </button>
        </div>

        {/* ── 合并条：勾了东西才出现 ── */}
        {selected.length > 0 && (
          <div className="border-b border-line bg-sunken px-4 py-2.5">
            <p className="text-[11.5px] text-ink2">
              已选 {selected.length} 个：
              <span className="text-ink">{selected.map((s) => `#${s}`).join("、")}</span>
            </p>
            <div className="mt-2 flex items-center gap-1.5">
              {/*
                用 input + datalist 而不是 select：
                合并的目标既可能是已有的标签，也可能是一个全新的名字。
                select 只能前者，而且要先加一个「新建…」选项再切出输入框——两步。
                datalist 一个控件两种用法都覆盖。
              */}
              <input
                list="shilian-tag-names"
                value={mergeTarget}
                onChange={(e) => setMergeTarget(e.target.value)}
                placeholder="合并到哪个标签"
                className="min-w-0 flex-1 rounded-md border border-line bg-canvas px-2.5 py-[6px] text-[12px] text-ink outline-none transition-colors placeholder:text-ink3/60 focus:border-accent"
              />
              <datalist id="shilian-tag-names">
                {tags
                  .filter((t) => !selected.includes(t.name))
                  .map((t) => (
                    <option key={t.name} value={t.name} />
                  ))}
              </datalist>
              <button
                onClick={submitMerge}
                disabled={busy}
                className={cn(
                  "flex shrink-0 items-center gap-1 rounded-md px-2.5 py-[6px] text-[12px] text-white transition-opacity",
                  busy ? "cursor-wait bg-ink3/40" : "bg-accent hover:opacity-90",
                )}
              >
                {busy ? <Loader2 size={11.5} className="animate-spin" /> : <Merge size={11.5} />}
                合并
              </button>
              <button
                onClick={() => {
                  setSelected([]);
                  setMergeTarget("");
                }}
                className="shrink-0 rounded-md border border-line px-2 py-[6px] text-[12px] text-ink2 transition-colors hover:border-line-strong hover:text-ink"
              >
                取消
              </button>
            </div>
          </div>
        )}

        {/* ── 清单 ── */}
        <div className="max-h-[46vh] overflow-y-auto">
          {tags.length === 0 ? (
            <p className="px-4 py-8 text-center text-[12.5px] text-ink3">
              还没有标签。存网址时 AI 会顺手给几个。
            </p>
          ) : (
            tags.map((t) => (
              <div
                key={t.name}
                className="flex items-center gap-2 border-b border-linesoft px-4 py-2 last:border-b-0"
              >
                <input
                  type="checkbox"
                  checked={selected.includes(t.name)}
                  onChange={() => toggleSelect(t.name)}
                  title="勾选后可以合并"
                  className="h-[13px] w-[13px] shrink-0 cursor-pointer accent-[var(--accent)]"
                />

                {renaming === t.name ? (
                  <>
                    <input
                      autoFocus
                      value={renameValue}
                      onChange={(e) => setRenameValue(e.target.value)}
                      onKeyDown={(e) => {
                        if (e.key === "Enter") submitRename(t.name);
                        if (e.key === "Escape") {
                          setRenaming(null);
                          setErr(null);
                        }
                      }}
                      className="min-w-0 flex-1 rounded-md border border-line bg-canvas px-2 py-[5px] text-[12.5px] text-ink outline-none transition-colors focus:border-accent"
                    />
                    <button
                      onClick={() => submitRename(t.name)}
                      disabled={busy}
                      className="grid h-[24px] w-[24px] shrink-0 place-items-center rounded-md text-emerald-600 transition-colors hover:bg-sunken"
                      title="保存"
                    >
                      <Check size={13} />
                    </button>
                    <button
                      onClick={() => {
                        setRenaming(null);
                        setErr(null);
                      }}
                      className="grid h-[24px] w-[24px] shrink-0 place-items-center rounded-md text-ink3 transition-colors hover:bg-sunken"
                      title="取消"
                    >
                      <X size={13} />
                    </button>
                  </>
                ) : (
                  <>
                    <span className="min-w-0 flex-1 truncate text-[13px] text-ink">
                      #{t.name}
                    </span>
                    <span className="shrink-0 font-serif text-[11px] tabular-nums text-ink3">
                      {t.count}
                    </span>
                    <button
                      onClick={() => {
                        setRenaming(t.name);
                        setRenameValue(t.name);
                        setErr(null);
                      }}
                      title="改名"
                      className="grid h-[24px] w-[24px] shrink-0 place-items-center rounded-md text-ink3 transition-colors hover:bg-sunken hover:text-ink"
                    >
                      <Pencil size={12} />
                    </button>
                    <button
                      onClick={() => askDelete(t.name)}
                      title={confirmDelete === t.name ? "再点一次确认删除" : "删除这个标签"}
                      className={cn(
                        "grid h-[24px] w-[24px] shrink-0 place-items-center rounded-md transition-colors",
                        confirmDelete === t.name
                          ? "bg-red-600 text-white hover:bg-red-700"
                          : "text-ink3 hover:bg-sunken hover:text-ink",
                      )}
                    >
                      <Trash2 size={12} />
                    </button>
                  </>
                )}
              </div>
            ))
          )}
        </div>

        {/* ── 底：错误与说明 ── */}
        {(err || tags.length > 0) && (
          <div className="border-t border-line px-4 py-2.5">
            {err ? (
              <p className="text-[11.5px] leading-relaxed text-amber-700">{err}</p>
            ) : (
              <p className="text-[11.5px] leading-relaxed text-ink3">
                勾选几个 → 填一个名字 → 合并。合并后重复的会自动去掉。
              </p>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

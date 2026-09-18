import { useCallback, useEffect, useState } from "react";
import {
  ShieldAlert, Search, Loader2, ChevronLeft, ChevronRight,
  Ban, Undo2, ArrowLeft, ScrollText,
} from "lucide-react";
import { cn } from "../lib/utils";
import {
  ApiError, auditActionName, auditResultName, listAdminUsers, listAudit, setUserStatus,
  AUDIT_ACTIONS, AUDIT_RESULTS,
  type AdminUser, type AuditEntry, type Page, type UserStatus,
} from "../api";

/** 用户列表一页几条。和后端默认对齐（20），前端显式传是为了让「一页多少条」只有一处定义。 */
const USERS_PAGE_SIZE = 20;
const AUDIT_PAGE_SIZE = 20;

/**
 * 审计页「按用户筛选」那个下拉要拉多少人。
 *
 * 不是一个分页参数，而是「下拉里要列出多少人」——这是个位数规模的产品，
 * 一次取 100 个足够把所有人列出来，省得为一个下拉再引入一套异步搜索。
 * 真到了几百人，这个下拉该换成输入用户名搜索，那时候管理接口也得加参数。
 */
const USER_OPTIONS_SIZE = 100;

interface Props {
  /** 当前登录管理员自己的 id。用来拦住「把自己禁掉」这一步 */
  myId: string;
  onExit: () => void;
  notify: (msg: string, kind?: "ok" | "warn") => void;
}

/**
 * 管理端（R-19）＋ 审计日志（R-20）。
 *
 * <p>做成整屏视图而不是弹窗，和回顾 / 周报一个道理：进来就是要停下来看一张表。
 * 顶栏始终在，随时能切回列表。
 *
 * <p>两个页签而不是两个页面：它们回答的是同一个问题（「这个账号怎么了」）的
 * 两个阶段——先在用户页找到人，再看他做过什么。分成两个入口的话，
 * 第二步要重新把人找一遍。
 */
export default function AdminView({ myId, onExit, notify }: Props) {
  const [tab, setTab] = useState<"users" | "audit">("users");
  /** 从某个用户那行点「看审计」过来时，预先选好的人 */
  const [auditUserId, setAuditUserId] = useState("");
  /**
   * 后端拒绝了这个请求（非管理员会拿到 404）。
   *
   * 兜底要显式：入口虽然只对管理员显示，但会话可能在半路被降级
   * （管理员身份被撤掉、换了账号），那时候不能白屏。
   */
  const [denied, setDenied] = useState(false);
  /** 「按用户筛选」下拉的选项。拉一次就够，不跟着翻页变 */
  const [userOptions, setUserOptions] = useState<AdminUser[]>([]);

  useEffect(() => {
    let alive = true;
    listAdminUsers("", 0, USER_OPTIONS_SIZE)
      .then((p) => { if (alive) setUserOptions(p.items); })
      // 下拉少几个选项不影响查日志，静默即可
      .catch(() => {});
    return () => { alive = false; };
  }, []);

  const openAudit = (userId: string) => {
    setAuditUserId(userId);
    setTab("audit");
  };

  /* ── 没有权限：一整屏说清楚，并给退路 ── */
  if (denied) {
    return (
      <div className="mx-auto flex w-full max-w-[420px] flex-col items-center px-6 py-24 text-center">
        <ShieldAlert size={26} className="text-ink3/60" strokeWidth={1.5} />
        <h1 className="mt-4 text-[15px] font-medium text-ink">没有权限</h1>
        <p className="mt-2 text-[13px] leading-relaxed text-ink2">
          这个页面只有管理员能看。入口没出现，说明当前这个账号不是管理员。
        </p>
        <button
          onClick={onExit}
          className="mt-5 inline-flex items-center gap-1.5 rounded-lg border border-line px-3.5 py-[7px] text-[12.5px] text-ink2 transition-colors hover:border-line-strong hover:text-ink"
        >
          <ArrowLeft size={12.5} />
          返回列表
        </button>
      </div>
    );
  }

  return (
    <div className="mx-auto w-full max-w-[1040px] px-10 pb-20 pt-4">
      <div className="flex items-center gap-3">
        <button
          onClick={onExit}
          className="flex items-center gap-1 text-[12.5px] text-ink3 transition-colors hover:text-ink"
        >
          <ArrowLeft size={12.5} />
          返回列表
        </button>

        <div className="ml-2 flex items-center gap-1">
          <TabBtn active={tab === "users"} onClick={() => setTab("users")}>
            用户
          </TabBtn>
          <TabBtn active={tab === "audit"} onClick={() => setTab("audit")}>
            <ScrollText size={11.5} />
            审计日志
          </TabBtn>
        </div>
      </div>

      {tab === "users" ? (
        <UsersTab
          myId={myId}
          onDenied={() => setDenied(true)}
          onAudit={openAudit}
          notify={notify}
        />
      ) : (
        <AuditTab
          key={auditUserId}
          initialUserId={auditUserId}
          userOptions={userOptions}
          onDenied={() => setDenied(true)}
        />
      )}
    </div>
  );
}

/* ────────────── 用户页 ────────────── */

interface UsersTabProps {
  myId: string;
  /** 后端回了 404 —— 让外层切到「没有权限」那一屏 */
  onDenied: () => void;
  /** 跳到审计页并预先筛这个人 */
  onAudit: (userId: string) => void;
  notify: (msg: string, kind?: "ok" | "warn") => void;
}

function UsersTab({ myId, onDenied, onAudit, notify }: UsersTabProps) {
  /** 输入框里的原文 */
  const [input, setInput] = useState("");
  /** 真正发出去的查询词（防抖之后才等于 input） */
  const [q, setQ] = useState("");
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<AdminUser> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  /** 正在改状态的那一行，防止连点 */
  const [pendingId, setPendingId] = useState<string | null>(null);

  /*
   * 搜索防抖 300ms。
   * 和列表页那套关键词筛不同：这个是真的要打后端的，边打边发会连着发出十几个请求，
   * 而每个请求都是一次带 JOIN 的分页查询。
   */
  useEffect(() => {
    const t = setTimeout(() => {
      setQ(input.trim());
      setPage(0);
    }, 300);
    return () => clearTimeout(t);
  }, [input]);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await listAdminUsers(q, page, USERS_PAGE_SIZE));
    } catch (e) {
      describeError(e, onDenied, setError);
    } finally {
      setLoading(false);
    }
  }, [q, page, onDenied]);

  useEffect(() => {
    void load();
  }, [load]);

  /**
   * 禁用 / 恢复。
   *
   * 乐观更新 + 失败回滚，和列表里加星一个套路：这类「点一下就该生效」的开关，
   * 等服务端往返再更新会让人怀疑自己点没点上。
   */
  const toggleStatus = async (u: AdminUser) => {
    const next: UserStatus = u.status === "disabled" ? "active" : "disabled";
    const backup = data;
    setPendingId(u.id);
    setData((d) =>
      d ? { ...d, items: d.items.map((x) => (x.id === u.id ? { ...x, status: next } : x)) } : d,
    );
    try {
      const updated = await setUserStatus(u.id, next);
      setData((d) =>
        d ? { ...d, items: d.items.map((x) => (x.id === u.id ? updated : x)) } : d,
      );
      notify(next === "disabled" ? `已禁用 ${userLabel(updated)}` : `已恢复 ${userLabel(updated)}`);
    } catch (e) {
      setData(backup);
      const err = e as ApiError;
      if (err.status === 404 || err.status === 403) {
        onDenied();
        return;
      }
      notify(err.message, "warn");
    } finally {
      setPendingId(null);
    }
  };

  const totalPages = data && data.size > 0 ? Math.max(1, Math.ceil(data.total / data.size)) : 1;

  return (
    <div className="mt-4">
      <div className="flex flex-wrap items-center gap-2">
        <div className="relative">
          <Search
            size={12.5}
            className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-ink3"
          />
          <input
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder="按邮箱或昵称找人"
            className="w-[220px] rounded-full border border-line bg-transparent py-[5px] pl-[26px] pr-3 text-[12px] text-ink outline-none transition-colors placeholder:text-ink3 focus:border-ink"
          />
        </div>
        <span className="font-serif text-[11.5px] tabular-nums text-ink3">
          共 {data?.total ?? 0} 人
        </span>
        {loading && <Loader2 size={12} className="animate-spin text-ink3" />}
      </div>

      {error && (
        <p className="mt-3 rounded-lg border border-line bg-sunken px-3 py-2.5 text-[12px] text-amber-700">
          {error}
        </p>
      )}

      <div className="mt-3 overflow-x-auto rounded-lg border border-line">
        <table className="w-full min-w-[880px] border-collapse text-left">
          <thead>
            <tr className="border-b border-line bg-sunken text-[11px] uppercase tracking-[0.06em] text-ink3">
              <Th>用户</Th>
              <Th>邮箱</Th>
              <Th className="text-right">收藏</Th>
              <Th className="text-right">AI 调用</Th>
              <Th className="text-right">tokens</Th>
              <Th>状态</Th>
              <Th>注册</Th>
              <Th>最后登录</Th>
              <Th className="text-right">操作</Th>
            </tr>
          </thead>
          <tbody>
            {!data || data.items.length === 0 ? (
              <tr>
                <td colSpan={9} className="px-3 py-10 text-center text-[12.5px] text-ink3">
                  {loading ? "加载中…" : q ? `没有匹配「${q}」的人` : "还没有用户"}
                </td>
              </tr>
            ) : (
              data.items.map((u) => {
                const disabled = u.status === "disabled";
                const isMe = u.id === myId;
                return (
                  <tr
                    key={u.id}
                    className={cn(
                      "border-b border-linesoft text-[12.5px] last:border-b-0",
                      disabled && "opacity-55",
                    )}
                  >
                    <Td>
                      <span className="text-ink">{userLabel(u)}</span>
                      <span className="ml-1.5 text-[11px] text-ink3">@{u.username}</span>
                      {u.role === "admin" && (
                        <span className="ml-1.5 rounded-[3px] bg-accentsoft px-1 py-px text-[10.5px] text-accent">
                          管理员
                        </span>
                      )}
                    </Td>
                    <Td className="text-ink2">{u.email ?? "—"}</Td>
                    <Td className="text-right font-serif tabular-nums text-ink2">{u.linkCount ?? 0}</Td>
                    <Td className="text-right font-serif tabular-nums text-ink2">{u.aiCalls ?? 0}</Td>
                    {/* 显示合计：这一列回答的是「这个人花了多少」，
                        不是「花在哪」——要看构成得点开用量明细，那是另一处。 */}
                    <Td className="text-right font-serif tabular-nums text-ink2">
                      {((u.promptTokens ?? 0) + (u.completionTokens ?? 0)).toLocaleString("zh-CN")}
                    </Td>
                    <Td>
                      <span
                        className={cn(
                          "rounded-[3px] px-1.5 py-px text-[11px]",
                          disabled ? "bg-red-600/12 text-red-600" : "bg-sunken text-ink2",
                        )}
                      >
                        {disabled ? "已禁用" : "正常"}
                      </span>
                    </Td>
                    <Td className="whitespace-nowrap text-ink3">{fmtTime(u.createdAt)}</Td>
                    <Td className="whitespace-nowrap text-ink3">{fmtTime(u.lastLoginAt)}</Td>
                    <Td className="text-right">
                      <div className="flex items-center justify-end gap-1">
                        <button
                          onClick={() => onAudit(u.id)}
                          title="只看这个人的审计记录"
                          className="rounded-md border border-line px-2 py-[3px] text-[11.5px] text-ink2 transition-colors hover:border-line-strong hover:text-ink"
                        >
                          审计
                        </button>
                        <button
                          onClick={() => void toggleStatus(u)}
                          disabled={isMe || pendingId === u.id}
                          /*
                            不能禁自己：那一下之后这个账号立刻被逐出，
                            界面会卡在一个全都 404 的状态里，而没有人能把它改回来
                            （只能开数据库）。宁可不给这个按钮。
                          */
                          title={
                            isMe
                              ? "不能禁用自己——禁用后没人能再改回来"
                              : disabled
                                ? "恢复这个账号"
                                : "禁用这个账号"
                          }
                          className={cn(
                            "flex items-center gap-1 rounded-md border px-2 py-[3px] text-[11.5px] transition-colors",
                            isMe || pendingId === u.id
                              ? "cursor-not-allowed border-line text-ink3/50"
                              : disabled
                                ? "border-line text-ink2 hover:border-line-strong hover:text-ink"
                                : "border-red-500/40 text-red-600 hover:bg-red-600 hover:text-white",
                          )}
                        >
                          {pendingId === u.id ? (
                            <Loader2 size={11} className="animate-spin" />
                          ) : disabled ? (
                            <Undo2 size={11} />
                          ) : (
                            <Ban size={11} />
                          )}
                          {disabled ? "恢复" : "禁用"}
                        </button>
                      </div>
                    </Td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>

      {data && data.total > 0 && (
        <div className="mt-3 flex items-center justify-end gap-2 text-[12px] text-ink3">
          <span className="font-serif tabular-nums">
            第 {data.page + 1} / {totalPages} 页
          </span>
          <PageBtn disabled={data.page <= 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>
            <ChevronLeft size={13} />
            上一页
          </PageBtn>
          <PageBtn
            disabled={data.page + 1 >= totalPages}
            onClick={() => setPage((p) => p + 1)}
          >
            下一页
            <ChevronRight size={13} />
          </PageBtn>
        </div>
      )}
    </div>
  );
}

/* ────────────── 审计页 ────────────── */

interface AuditTabProps {
  /** 从用户页点「审计」带过来的人 */
  initialUserId: string;
  userOptions: AdminUser[];
  onDenied: () => void;
}

function AuditTab({ initialUserId, userOptions, onDenied }: AuditTabProps) {
  const [userId, setUserId] = useState(initialUserId);
  const [action, setAction] = useState("");
  const [result, setResult] = useState("");
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<AuditEntry> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await listAudit(userId, action, result, page, AUDIT_PAGE_SIZE));
    } catch (e) {
      describeError(e, onDenied, setError);
    } finally {
      setLoading(false);
    }
  }, [userId, action, result, page, onDenied]);

  useEffect(() => {
    void load();
  }, [load]);

  const totalPages = data && data.size > 0 ? Math.max(1, Math.ceil(data.total / data.size)) : 1;

  return (
    <div className="mt-4">
      <div className="flex flex-wrap items-center gap-2">
        {/*
          按用户筛选用下拉而不是输入框：人是个位数到几十位，
          列出名字比让管理员背 id 好用得多。id 那东西只有机器记得住。
        */}
        <select
          value={userId}
          onChange={(e) => {
            setUserId(e.target.value);
            setPage(0);
          }}
          className="cursor-pointer rounded-full border border-line bg-transparent px-2.5 py-[5px] text-[12px] text-ink2 outline-none transition-colors hover:border-line-strong"
        >
          <option value="" className="bg-surface text-ink">全部用户</option>
          {userOptions.map((u) => (
            <option key={u.id} value={u.id} className="bg-surface text-ink">
              {userLabel(u)}（@{u.username}）
            </option>
          ))}
        </select>

        <select
          value={action}
          onChange={(e) => {
            setAction(e.target.value);
            setPage(0);
          }}
          className="cursor-pointer rounded-full border border-line bg-transparent px-2.5 py-[5px] text-[12px] text-ink2 outline-none transition-colors hover:border-line-strong"
        >
          <option value="" className="bg-surface text-ink">全部动作</option>
          {/*
            选项用的是英文枚举（后端存的也是它），显示的是中文。
            不能把中文当 value 发出去——那是拿展示文案当协议用，改一个字就查不到。
          */}
          {Object.keys(AUDIT_ACTIONS).map((a) => (
            <option key={a} value={a} className="bg-surface text-ink">
              {auditActionName(a)}
            </option>
          ))}
        </select>

        {/*
          按结果筛：成功 / 失败 / 被拒。
          用法和动作那个下拉一致——value 发英文枚举，显示中文。
        */}
        <select
          value={result}
          onChange={(e) => {
            setResult(e.target.value);
            setPage(0);
          }}
          className="cursor-pointer rounded-full border border-line bg-transparent px-2.5 py-[5px] text-[12px] text-ink2 outline-none transition-colors hover:border-line-strong"
        >
          <option value="" className="bg-surface text-ink">全部结果</option>
          {Object.keys(AUDIT_RESULTS).map((r) => (
            <option key={r} value={r} className="bg-surface text-ink">
              {auditResultName(r)}
            </option>
          ))}
        </select>

        <span className="font-serif text-[11.5px] tabular-nums text-ink3">
          共 {data?.total ?? 0} 条
        </span>
        {loading && <Loader2 size={12} className="animate-spin text-ink3" />}
      </div>

      {error && (
        <p className="mt-3 rounded-lg border border-line bg-sunken px-3 py-2.5 text-[12px] text-amber-700">
          {error}
        </p>
      )}

      <div className="mt-3 overflow-x-auto rounded-lg border border-line">
        {/*
          「结果」和「详情」是两列，不是同一件事的两版说法：
          结果回答「成了没有」（成功 / 失败 / 被拒，能单独筛），
          详情回答「为什么」（密码错误之类的一句话）。
          合成一列之后，「把失败的记录都找出来」只能去匹配文案——
          而文案是会改的。
        */}
        <table className="w-full min-w-[880px] border-collapse text-left">
          <thead>
            <tr className="border-b border-line bg-sunken text-[11px] uppercase tracking-[0.06em] text-ink3">
              <Th>时间</Th>
              <Th>用户</Th>
              <Th>动作</Th>
              <Th>对象</Th>
              <Th>结果</Th>
              <Th>详情</Th>
              <Th>来源 IP</Th>
            </tr>
          </thead>
          <tbody>
            {!data || data.items.length === 0 ? (
              <tr>
                <td colSpan={7} className="px-3 py-10 text-center text-[12.5px] text-ink3">
                  {loading ? "加载中…" : "这段时间没有记录"}
                </td>
              </tr>
            ) : (
              data.items.map((e) => (
                <tr key={e.id} className="border-b border-linesoft text-[12.5px] last:border-b-0">
                  <Td className="whitespace-nowrap text-ink3">{fmtTime(e.createdAt)}</Td>
                  <Td className="text-ink2">
                    {e.username ? `@${e.username}` : "—"}
                  </Td>
                  {/*
                    动作一律翻译成中文再显示。
                    直接把 LOGIN_OK 摆上去，等于记了但没人看得懂——
                    审计日志的价值取决于出事那一刻能不能读懂它。
                  */}
                  <Td className="text-ink">{auditActionName(e.action)}</Td>
                  <Td className="max-w-[220px] truncate text-ink3">{e.target ?? "—"}</Td>
                  {/*
                    结果单独一列，且失败 / 被拒标成警示色：
                    翻这张表的人通常是在找「哪次没成」，
                    让没成的那几行自己跳出来，比让人逐行读字快。
                  */}
                  <Td
                    className={cn(
                      "whitespace-nowrap",
                      e.result === "failure" || e.result === "denied"
                        ? "text-amber-700"
                        : "text-ink2",
                    )}
                  >
                    {auditResultName(e.result) || "—"}
                  </Td>
                  <Td className="max-w-[240px] truncate text-ink3">{e.detail ?? "—"}</Td>
                  <Td className="font-serif text-[11.5px] text-ink3">{e.ip ?? "—"}</Td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {data && data.total > 0 && (
        <div className="mt-3 flex items-center justify-end gap-2 text-[12px] text-ink3">
          <span className="font-serif tabular-nums">
            第 {data.page + 1} / {totalPages} 页
          </span>
          <PageBtn disabled={data.page <= 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>
            <ChevronLeft size={13} />
            上一页
          </PageBtn>
          <PageBtn
            disabled={data.page + 1 >= totalPages}
            onClick={() => setPage((p) => p + 1)}
          >
            下一页
            <ChevronRight size={13} />
          </PageBtn>
        </div>
      )}
    </div>
  );
}

/* ────────────── 小零件 ────────────── */

/**
 * 一个人叫什么。**显示名的规则只此一处**：有昵称用昵称，没填就用用户名。
 *
 * 不退回「—」：昵称是选填的，把它当成必填会让一大批正常账号在表里显示成
 * 一个横杠，而横杠在这个界面里是「没记到」的意思——那是两种完全不同的情况。
 */
function userLabel(u: AdminUser): string {
  return u.nickname || u.username;
}

/**
 * 统一的错误处理：**越权返回 404 是这一块的约定，不是错误**。
 *
 * 403 也照 404 处理，理由和后端一致——它等于向对方确认「这个东西存在」，
 * 而「有没有管理端」这件事不该向普通人确认。
 *
 * @returns true 表示已经当成普通错误写进 error 状态；false 表示它是越权，调用方该收手
 */
function describeError(
  e: unknown,
  onDenied: () => void,
  setError: (msg: string) => void,
): boolean {
  const err = e as ApiError;
  if (err.status === 404 || err.status === 403) {
    onDenied();
    return false;
  }
  setError(err.offline ? "连不上后端服务" : err.message);
  return true;
}

/**
 * 时间显示成 `2026-09-18 21:03`。
 *
 * 秒没有意义——审计是用来回答「那天下午谁动了我的数据」，
 * 精确到分钟足够定位，多两位只会让列变宽。
 * 解析不出来就原样返回：显示一串奇怪的字符也好过显示一个「—」，
 * 后者会让人以为这条记录没记时间。
 */
function fmtTime(s: string | null | undefined): string {
  if (!s) return "—";
  const d = new Date(s);
  if (Number.isNaN(d.getTime())) return s;
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

function Th({
  className, children,
}: {
  className?: string;
  children?: React.ReactNode;
}) {
  return <th className={cn("px-3 py-2 font-normal", className)}>{children}</th>;
}

function Td({
  className, children,
}: {
  className?: string;
  children?: React.ReactNode;
}) {
  return <td className={cn("px-3 py-[9px] align-middle", className)}>{children}</td>;
}

function TabBtn({
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

function PageBtn({
  disabled, onClick, children,
}: {
  disabled: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      className={cn(
        "flex items-center gap-0.5 rounded-md border border-line px-2 py-[3px] text-[11.5px] transition-colors",
        disabled
          ? "cursor-not-allowed text-ink3/45"
          : "text-ink2 hover:border-line-strong hover:text-ink",
      )}
    >
      {children}
    </button>
  );
}

import { useEffect, useState } from "react";
import { AlertCircle, CheckCircle2, Link2, Loader2 } from "lucide-react";
import { Field } from "./AuthView";
import { confirmEmailVerification, getMe, resetPassword, type AuthUser } from "../api";

/*
 * 从邮件链接进来的两个页面：设置新密码（reset）和确认邮箱（verify）。
 *
 * 它们不是一个「页面」加一个开关，而是两个组件，因为两者的节奏完全不同：
 * reset 要等用户输入、要校验两次输入一致；verify 是打开就自己跑完。
 * 硬合在一起会变成一个「有时候要表单有时候不要」的组件，
 * 而那种组件的状态机向来是 bug 的窝。
 */

/** 外壳：和登录页同一个居中卡片，让人知道这还是拾链，不是点进了什么奇怪的地方。 */
function Shell({ title, sub, children }: {
  title: string;
  sub: string;
  children: React.ReactNode;
}) {
  return (
    <div className="flex h-full w-full items-center justify-center bg-canvas px-6">
      <div className="w-full max-w-[340px]">
        <div className="mb-7 text-center">
          <div className="mx-auto flex h-9 w-9 items-center justify-center rounded-lg bg-accent">
            <Link2 size={15} className="text-white" strokeWidth={2} />
          </div>
          <h1 className="mt-3.5 text-[16px] font-medium text-ink">{title}</h1>
          <p className="mt-1 text-[12.5px] text-ink3">{sub}</p>
        </div>
        {children}
      </div>
    </div>
  );
}

function Notice({ kind, children }: { kind: "error" | "done"; children: React.ReactNode }) {
  return (
    <div className="flex items-start gap-2 rounded-lg border border-line bg-sunken px-3 py-2.5">
      {kind === "error" ? (
        <AlertCircle size={13} className="mt-[2px] shrink-0 text-ink3" />
      ) : (
        <CheckCircle2 size={13} className="mt-[2px] shrink-0 text-ink3" />
      )}
      <p className="text-[12px] leading-relaxed text-ink2">{children}</p>
    </div>
  );
}

/**
 * 设置新密码。
 *
 * <p>不要求登录——走这条路径的人恰恰是登不进去的那个，
 * 由 App.tsx 在未登录时也直接渲染这一页。令牌本身就是凭据。
 */
export function ResetView({ token, onDone }: { token: string | null; onDone: () => void }) {
  const [pw, setPw] = useState("");
  const [pw2, setPw2] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!token) {
      setError("链接里没有令牌，回到邮件再点一次那个链接");
      return;
    }
    /*
     * 两次输入要一致——不是为了「更安全」，是因为密码框是遮蔽的，
     * 打错一个字符他自己看不出来，而等到登录时才发现就得再走一遍找回流程。
     */
    if (pw !== pw2) {
      setError("两次输入的不一样");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await resetPassword(token, pw);
      setDone(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : "没成功，稍后再试");
    } finally {
      setBusy(false);
    }
  };

  if (done) {
    return (
      <Shell title="改好了" sub="用新密码登录吧">
        <Notice kind="done">密码已经换掉了。之前那封邮件里的链接现在都没用了。</Notice>
        <button
          onClick={onDone}
          className="mt-4 w-full rounded-lg bg-accent py-[7px] text-[12.5px] text-white transition-opacity hover:opacity-90"
        >
          去登录
        </button>
      </Shell>
    );
  }

  return (
    <Shell title="设一个新密码" sub="这个链接用一次就作废">
      <form onSubmit={submit} className="space-y-2.5">
        <Field
          label="新密码"
          value={pw}
          onChange={setPw}
          type="password"
          autoComplete="new-password"
          autoFocus
          hint="至少 8 位"
        />
        <Field
          label="再输一次"
          value={pw2}
          onChange={setPw2}
          type="password"
          autoComplete="new-password"
        />

        {error && <Notice kind="error">{error}</Notice>}

        <button
          type="submit"
          disabled={busy}
          className="mt-1 flex w-full items-center justify-center gap-1.5 rounded-lg bg-accent py-[7px] text-[12.5px] text-white transition-opacity hover:opacity-90 disabled:opacity-50"
        >
          {busy && <Loader2 size={12.5} className="animate-spin" />}
          {busy ? "改着…" : "改密码"}
        </button>
      </form>
    </Shell>
  );
}

/**
 * 确认邮箱。打开就自动跑，不用点按钮。
 *
 * <p><b>为什么自动跑而不放个按钮。</b>
 * 用户刚从邮件点过来，他的意图已经表达完了——再让他点一次「确认」是多余的一步，
 * 而且很多人会以为那个按钮是「返回网站」，点了之后一头雾水。
 * 自动跑失败时再给按钮重试。
 */
export function VerifyView({ token, onDone, notify, onUserChange }: {
  token: string | null;
  onDone: () => void;
  notify: (m: string) => void;
  /** 验证完把新的用户信息交回去，顶栏那句「还没验证」才能跟着消失 */
  onUserChange: (u: AuthUser) => void;
}) {
  const [state, setState] = useState<"running" | "done" | "failed">("running");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    (async () => {
      if (!token) {
        setState("failed");
        setError("链接里没有令牌，回到邮件再点一次那个链接");
        return;
      }
      try {
        await confirmEmailVerification(token);
        if (!alive) return;
        setState("done");
        notify("邮箱验证好了");
        /*
         * 拉一次 /auth/me：验证状态变了，顶栏那句「还没验证」得跟着消失。
         * 不刷的话用户会看到「刚验证完，界面还说我没验证」——
         * 那种自相矛盾的界面比没有提示更让人不放心。
         */
        try {
          onUserChange(await getMe());
        } catch {
          // 拿不到就先不管：验证本身已经成了，下次进应用自然会拿到新状态
        }
      } catch (err) {
        if (!alive) return;
        setState("failed");
        setError(err instanceof Error ? err.message : "没成功，稍后再试");
      }
    })();
    // 令牌是这一次进来的凭据，中途不会变；变了就该重新走一遍链接
    // eslint-disable-next-line react-hooks/exhaustive-deps
    return () => {
      alive = false;
    };
  }, []);

  if (state === "running") {
    return (
      <Shell title="验证中" sub="就一下">
        <div className="flex items-center justify-center gap-2 py-6 text-ink3">
          <Loader2 size={13} className="animate-spin" />
          <span className="text-[12.5px]">确认这个邮箱…</span>
        </div>
      </Shell>
    );
  }

  if (state === "done") {
    return (
      <Shell title="验证好了" sub="以后可以用它找回密码">
        <Notice kind="done">
          这个邮箱确认过了。没验证也能正常用拾链，只是没法自助找回密码。
        </Notice>
        <button
          onClick={onDone}
          className="mt-4 w-full rounded-lg bg-accent py-[7px] text-[12.5px] text-white transition-opacity hover:opacity-90"
        >
          回到网址库
        </button>
      </Shell>
    );
  }

  return (
    <Shell title="没验证成" sub="链接可能过期了">
      <Notice kind="error">{error ?? "链接失效了"}</Notice>
      <button
        onClick={onDone}
        className="mt-4 w-full rounded-lg border border-line px-3.5 py-[7px] text-[12.5px] text-ink2 transition-colors hover:border-line-strong hover:text-ink"
      >
        回到网址库
      </button>
    </Shell>
  );
}

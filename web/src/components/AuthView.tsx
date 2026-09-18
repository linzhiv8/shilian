import { useEffect, useState } from "react";
import { Link2, AlertCircle, Moon, Sun, CheckCircle2 } from "lucide-react";
import { ApiError, login, register, requestPasswordReset, type AuthUser } from "../api";

/**
 * 登录 / 注册 / 找回密码。
 *
 * <p><b>为什么做成一个组件三个模式，而不是三个页面。</b>
 * 这三个表单共用同一套校验提示、错误条、提交态；分成多个文件的话，
 * 迟早出现「登录页改了错误提示的样式，注册页没跟着改」。
 * 而它们之间的差异只有字段多少，用一个 mode 就够表达。
 *
 * <p><b>校验规则在这里重写一遍而不是只信后端。</b>
 * 等到提交后才发现「用户名只能是字母数字下划线」，
 * 用户已经填完了整个表单——那一下的挫败感很实在。
 * 前端这条校验不替代后端（后端才是真正的防线），
 * 它只是让用户不必为一个必然失败的请求等一次往返。
 */
export default function AuthView({
  onAuthed,
  theme,
  toggleTheme,
}: {
  onAuthed: (u: AuthUser) => void;
  theme: "light" | "dark";
  toggleTheme: () => void;
}) {
  const [mode, setMode] = useState<"login" | "register" | "forgot">("login");
  const [username, setUsername] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [nickname, setNickname] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** 找回密码那封信发出去之后停在结果页，不退回表单——见下面 sent 分支的注释 */
  const [sentTo, setSentTo] = useState<string | null>(null);

  /** 切模式时清掉上一个模式留下的报错，否则会看到「邮箱已注册」挂在登录页上 */
  useEffect(() => {
    setError(null);
  }, [mode]);

  /** 本地校验。返回 null 表示可以提交。规则和后端 DTO 一致。 */
  const localProblem = (): string | null => {
    if (mode === "register") {
      if (!/^[a-zA-Z0-9_]{3,32}$/.test(username.trim())) {
        return "用户名只能是字母、数字或下划线，长度 3 到 32 位";
      }
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) {
        return "邮箱格式不对，检查一下";
      }
    } else if (mode === "forgot") {
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) {
        return "邮箱格式不对，检查一下";
      }
      return null;
    } else if (!username.trim()) {
      return "请填用户名或邮箱";
    }
    // 上限 72 位不是随便定的：bcrypt 只取前 72 字节，超出的会被静默丢弃
    if (password.length < 8 || password.length > 72) {
      return "密码长度要在 8 到 72 位之间";
    }
    return null;
  };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const problem = localProblem();
    if (problem) {
      setError(problem);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      if (mode === "login") {
        onAuthed(await login(username.trim(), password));
      } else if (mode === "forgot") {
        await requestPasswordReset(email.trim());
        setSentTo(email.trim());
      } else {
        await register({
          username: username.trim(),
          email: email.trim(),
          password,
          nickname: nickname.trim() || undefined,
        });
        // 注册不自动登录，这里补一次——省得用户刚填完再敲一遍
        onAuthed(await login(username.trim(), password));
      }
    } catch (err) {
      const e2 = err as ApiError;
      setError(e2.offline ? "连不上后端服务，确认它已经启动" : e2.message);
    } finally {
      setBusy(false);
    }
  };

  /*
   * 找回密码的结果页。
   *
   * <b>为什么文案里没有「如果这个邮箱注册过」这类话。</b>
   * 后端对这个接口无论邮箱存不存在都返回同一句话，
   * 是故意的——否则它就成了「谁注册过拾链」的查询机。
   * 前端要是补一句「如果已注册，我们会发一封」，
   * 等于把后端费劲藏起来的信息又写在界面上。所以这里只说发出去了。
   */
  if (sentTo) {
    return (
      <div className="relative flex h-full w-full items-center justify-center bg-canvas px-6">
        <div className="w-full max-w-[340px]">
          <div className="mb-7 text-center">
            <div className="mx-auto flex h-9 w-9 items-center justify-center rounded-lg bg-accent">
              <Link2 size={15} className="text-white" strokeWidth={2} />
            </div>
            <h1 className="mt-3.5 text-[16px] font-medium text-ink">去看邮件</h1>
          </div>

          <div className="flex items-start gap-2 rounded-lg border border-line bg-sunken px-3 py-2.5">
            <CheckCircle2 size={13} className="mt-[2px] shrink-0 text-ink3" />
            <p className="text-[12px] leading-relaxed text-ink2">
              已经往 {sentTo} 发了一封，里面有设置新密码的链接，30 分钟内有效。
            </p>
          </div>

          <p className="mt-3 text-[11.5px] leading-relaxed text-ink3">
            没收到的话看看垃圾箱。邮箱没验证过是收不到重置邮件的。
          </p>

          <button
            onClick={() => {
              setSentTo(null);
              setMode("login");
            }}
            className="mt-4 w-full rounded-lg border border-line px-3.5 py-[7px] text-[12.5px] text-ink2 transition-colors hover:border-line-strong hover:text-ink"
          >
            回登录
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="relative flex h-full w-full items-center justify-center bg-canvas px-6">
      <button
        onClick={toggleTheme}
        title={theme === "light" ? "切换到深色" : "切换到浅色"}
        className="absolute right-5 top-5 rounded-lg border border-line p-2 text-ink3 transition-colors hover:border-line-strong hover:text-ink2"
      >
        {theme === "light" ? <Moon size={13} /> : <Sun size={13} />}
      </button>

      <div className="w-full max-w-[340px]">
        <div className="mb-7 text-center">
          <div className="mx-auto flex h-9 w-9 items-center justify-center rounded-lg bg-accent">
            <Link2 size={15} className="text-white" strokeWidth={2} />
          </div>
          <h1 className="mt-3.5 text-[16px] font-medium text-ink">拾链</h1>
          <p className="mt-1 text-[12.5px] text-ink3">
            {mode === "login"
              ? "登录后回到你的网址库"
              : mode === "register"
                ? "建一个账号，开始收集"
                : "填注册时用的邮箱"}
          </p>
        </div>

        <div className="mb-4 grid grid-cols-3 gap-1 rounded-lg border border-line bg-sunken p-1">
          {(
            [
              ["login", "登录"],
              ["register", "注册"],
              ["forgot", "找回"],
            ] as const
          ).map(([m, text]) => (
            <button
              key={m}
              onClick={() => setMode(m)}
              className={`rounded-md py-[6px] text-[12.5px] transition-colors ${
                mode === m
                  ? "bg-surface text-ink shadow-[0_1px_2px_rgba(0,0,0,0.06)]"
                  : "text-ink3 hover:text-ink2"
              }`}
            >
              {text}
            </button>
          ))}
        </div>

        <form onSubmit={submit} className="space-y-2.5">
          {mode !== "forgot" && (
            <Field
              label={mode === "login" ? "用户名或邮箱" : "用户名"}
              value={username}
              onChange={setUsername}
              autoComplete="username"
              autoFocus
              hint={mode === "register" ? "字母、数字或下划线，3–32 位" : undefined}
            />
          )}

          {mode !== "login" && (
            <Field
              label="邮箱"
              value={email}
              onChange={setEmail}
              type="email"
              autoComplete="email"
              autoFocus={mode === "forgot"}
            />
          )}

          {mode !== "forgot" && (
            <Field
              label="密码"
              value={password}
              onChange={setPassword}
              type="password"
              autoComplete={mode === "login" ? "current-password" : "new-password"}
              hint={mode === "register" ? "至少 8 位" : undefined}
            />
          )}

          {mode === "register" && (
            <Field
              label="昵称"
              value={nickname}
              onChange={setNickname}
              optional
              hint="留空就用用户名"
            />
          )}

          {error && (
            <div className="flex items-start gap-2 rounded-lg border border-line bg-sunken px-3 py-2.5">
              <AlertCircle size={13} className="mt-[2px] shrink-0 text-ink3" />
              <p className="text-[12px] leading-relaxed text-ink2">{error}</p>
            </div>
          )}

          <button
            type="submit"
            disabled={busy}
            className={`mt-1 w-full rounded-lg py-[9px] text-[13px] font-medium text-white transition-opacity ${
              busy ? "cursor-not-allowed bg-ink3/40" : "bg-accent hover:opacity-90"
            }`}
          >
            {busy ? "请稍等…" : mode === "login" ? "登录" : mode === "register" ? "注册并进入" : "发邮件"}
          </button>
        </form>

        {mode === "register" && (
          <p className="mt-4 text-center text-[11.5px] leading-relaxed text-ink3">
            第一个注册的账号会接手这个库里已有的收藏
          </p>
        )}
      </div>
    </div>
  );
}

/*
 * 导出是为了给重置密码那页复用（MailLinkView）。
 * 不为它单独建一个 components/Field.tsx：它只在「登录 / 注册 / 重置」这三处用，
 * 而这三处本来就是同一个场景的三种形态，拆出去反而要把一堆 props 搬到别处。
 */
export function Field({
  label,
  value,
  onChange,
  type = "text",
  hint,
  optional,
  autoComplete,
  autoFocus,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  type?: string;
  hint?: string;
  optional?: boolean;
  autoComplete?: string;
  autoFocus?: boolean;
}) {
  return (
    <label className="block">
      <span className="mb-1 flex items-baseline gap-1.5 text-[11.5px] text-ink2">
        {label}
        {optional && <span className="text-[10.5px] text-ink3">选填</span>}
      </span>
      <input
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        autoComplete={autoComplete}
        // eslint-disable-next-line jsx-a11y/no-autofocus
        autoFocus={autoFocus}
        className="w-full rounded-lg border border-line bg-surface px-3 py-[7px] text-[13px] text-ink outline-none transition-colors placeholder:text-ink3/60 focus:border-line-strong"
      />
      {hint && <span className="mt-1 block text-[10.5px] text-ink3">{hint}</span>}
    </label>
  );
}

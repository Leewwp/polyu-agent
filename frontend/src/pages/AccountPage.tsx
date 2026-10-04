import * as React from "react";
import { useNavigate } from "react-router-dom";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from "@/components/ui/dialog";
import { SiteFooter } from "@/components/layout/SiteFooter";
import { useOptionalFeedLang } from "@/components/feed/feedLang";
import { usePageTitle } from "@/hooks/usePageTitle";
import { useAuthStore } from "@/stores/authStore";
import {
  changePassword,
  confirmEmailChange,
  requestEmailChange
} from "@/services/userService";
import { listMyAgentShares, revokeAgentShare, type AgentShareMineItem } from "@/services/agentShareService";
import { listMyShares, revokeShare, type ShareMineItem } from "@/services/shareService";
import { deleteAccount } from "@/services/authService";

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/**
 * 账号设置页（#104）：资料展示 + 改邮箱（两步验码）+ 改密自助 + 我的分享双 tab + 注销。
 * 唯一权威入口：UserMenu「账号设置」；「我的分享」弹窗已迁入本页（MyAgentSharesDialog 删除）。
 * 分享 tab 按 flag 探测：对应 mine 列表 404（flag 关）时隐藏该 tab——沿用分享钮
 * 「后端 404 即功能未开启」的判断模式，不另起前端 flag 面。
 * #227：操作钮/tab/状态文字随全局语言单语（动态错误与 toast 透传后端文案，不翻）。
 */
export function AccountPage() {
  const { lang } = useOptionalFeedLang();
  const zh = lang === "zh";
  usePageTitle({ zh: "账号设置", en: "Account settings" });
  const user = useAuthStore((state) => state.user);
  const fetchCurrentUser = useAuthStore((state) => state.fetchCurrentUser);

  return (
    <div className="flex min-h-screen flex-col px-4">
      <div className="mx-auto flex w-full max-w-3xl flex-1 flex-col gap-6 py-8">
        <header>
          <h1 className="text-2xl font-semibold">{zh ? "账号设置" : "Account settings"}</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            {zh
              ? "Account settings · 管理你的登录标识、邮箱、密码与分享"
              : "Manage your sign-in identity, email, password and shares"}
          </p>
        </header>
        <ProfileCard email={user?.email ?? null} emailVerified={user?.emailVerified ?? null} />
        <EmailChangeCard onChanged={fetchCurrentUser} />
        <PasswordCard />
        <SharesCard />
        <DangerZoneCard />
      </div>
      <SiteFooter className="relative z-10" />
    </div>
  );
}

function ProfileCard({ email, emailVerified }: { email: string | null; emailVerified: number | null }) {
  const { lang } = useOptionalFeedLang();
  const zh = lang === "zh";
  const user = useAuthStore((state) => state.user);
  const createTime = user?.createTime ? new Date(user.createTime).toLocaleDateString() : null;
  return (
    <section
      aria-label={zh ? "账号资料" : "Profile"}
      className="rounded-2xl border border-border/70 bg-background/80 p-6 shadow-soft"
    >
      <h2 className="text-base font-semibold">{zh ? "账号资料" : "Profile"}</h2>
      <dl className="mt-4 grid gap-3 text-sm sm:grid-cols-[130px_1fr]">
        <dt className="text-muted-foreground">{zh ? "用户名" : "Username"}</dt>
        <dd className="font-medium">
          {user?.username || "-"}
          <span className="ml-2 text-xs text-muted-foreground">
            {zh ? "注册后不可修改 · Cannot be changed" : "Cannot be changed after sign-up"}
          </span>
        </dd>
        <dt className="text-muted-foreground">{zh ? "角色" : "Role"}</dt>
        <dd>{user?.role === "admin" ? (zh ? "管理员" : "Admin") : zh ? "用户" : "User"}</dd>
        <dt className="text-muted-foreground">{zh ? "邮箱" : "Email"}</dt>
        <dd data-testid="profile-email">
          {email ? (
            <>
              {email}
              {emailVerified === 1 ? (
                <span className="ml-2 rounded bg-emerald-50 px-1.5 py-0.5 text-xs font-medium text-emerald-600">
                  {zh ? "已验证" : "Verified"}
                </span>
              ) : (
                <span className="ml-2 rounded bg-amber-50 px-1.5 py-0.5 text-xs font-medium text-amber-600">
                  {zh ? "未验证" : "Unverified"}
                </span>
              )}
            </>
          ) : (
            zh ? "未设置" : "Not set"
          )}
        </dd>
        {createTime ? (
          <>
            <dt className="text-muted-foreground">{zh ? "注册时间" : "Registered"}</dt>
            <dd>{createTime}</dd>
          </>
        ) : null}
      </dl>
    </section>
  );
}

function EmailChangeCard({ onChanged }: { onChanged: () => void }) {
  const { lang } = useOptionalFeedLang();
  const zh = lang === "zh";
  const [step, setStep] = React.useState<"form" | "code">("form");
  const [newEmail, setNewEmail] = React.useState("");
  const [password, setPassword] = React.useState("");
  const [code, setCode] = React.useState("");
  const [error, setError] = React.useState<string | null>(null);
  const [busy, setBusy] = React.useState(false);

  const emailValid = EMAIL_RE.test(newEmail.trim());

  const handleRequest = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!emailValid) {
      setError("请输入有效的新邮箱。");
      return;
    }
    setError(null);
    setBusy(true);
    try {
      await requestEmailChange(newEmail.trim(), password);
      setStep("code");
      toast.success("验证码已发送至新邮箱");
    } catch (err) {
      setError((err as Error).message || "发送失败，请稍后重试。");
    } finally {
      setBusy(false);
    }
  };

  const handleConfirm = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!code.trim()) {
      setError("请输入新邮箱中的验证码。");
      return;
    }
    setError(null);
    setBusy(true);
    try {
      await confirmEmailChange(newEmail.trim(), code.trim());
      toast.success("邮箱已更改，原邮箱已收到通知信");
      onChanged();
      setStep("form");
      setNewEmail("");
      setPassword("");
      setCode("");
    } catch (err) {
      setError((err as Error).message || "验证失败，请稍后重试。");
    } finally {
      setBusy(false);
    }
  };

  return (
    <section
      aria-label={zh ? "更改邮箱" : "Change email"}
      className="rounded-2xl border border-border/70 bg-background/80 p-6 shadow-soft"
    >
      <h2 className="text-base font-semibold">{zh ? "更改邮箱" : "Change email"}</h2>
      <p className="mt-1 text-xs text-muted-foreground">
        {zh
          ? "新邮箱收验证码、原邮箱收通知信；更改后原邮箱立即释放可被再注册。用户名不受影响。"
          : "A verification code goes to the new email and a notice to the old one; the old email is released immediately after the change. Your username is unaffected."}
      </p>
      {step === "form" ? (
        <form className="mt-4 space-y-3" onSubmit={handleRequest}>
          <Input
            type="email"
            placeholder={zh ? "新邮箱 · new@example.com" : "New email · new@example.com"}
            aria-label={zh ? "新邮箱" : "New email"}
            value={newEmail}
            onChange={(event) => setNewEmail(event.target.value)}
            autoComplete="email"
            data-testid="new-email"
          />
          <Input
            type="password"
            placeholder={zh ? "当前密码" : "Current password"}
            aria-label={zh ? "当前密码" : "Current password"}
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            autoComplete="current-password"
            data-testid="email-current-password"
          />
          {error ? <p className="text-sm text-destructive">{error}</p> : null}
          <Button type="submit" disabled={!emailValid || !password || busy}>
            {busy ? (zh ? "发送中..." : "Sending...") : zh ? "发送验证码" : "Send code"}
          </Button>
        </form>
      ) : (
        <form className="mt-4 space-y-3" onSubmit={handleConfirm}>
          <p className="text-sm text-muted-foreground">
            {zh ? "验证码已发送至 " : "Code sent to "}
            <span className="font-medium text-foreground">{newEmail.trim()}</span>
          </p>
          <Input
            placeholder={zh ? "6 位验证码" : "6-digit code"}
            aria-label={zh ? "验证码" : "Verification code"}
            value={code}
            onChange={(event) => setCode(event.target.value.replace(/\D/g, "").slice(0, 6))}
            inputMode="numeric"
            data-testid="email-code"
          />
          {error ? <p className="text-sm text-destructive">{error}</p> : null}
          <div className="flex gap-2">
            <Button type="submit" disabled={!code.trim() || busy}>
              {busy ? (zh ? "验证中..." : "Verifying...") : zh ? "确认更改" : "Confirm change"}
            </Button>
            <Button type="button" variant="outline" onClick={() => setStep("form")}>
              {zh ? "返回" : "Back"}
            </Button>
          </div>
        </form>
      )}
    </section>
  );
}

function PasswordCard() {
  const { lang } = useOptionalFeedLang();
  const zh = lang === "zh";
  const [current, setCurrent] = React.useState("");
  const [next, setNext] = React.useState("");
  const [error, setError] = React.useState<string | null>(null);
  const [busy, setBusy] = React.useState(false);
  const valid = next.length >= 8 && next.length <= 64;

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!valid) {
      setError("新密码需为 8–64 位字符。");
      return;
    }
    setError(null);
    setBusy(true);
    try {
      await changePassword({ currentPassword: current, newPassword: next });
      toast.success("密码已修改（当前会话不受影响）");
      setCurrent("");
      setNext("");
    } catch (err) {
      setError((err as Error).message || "修改失败，请稍后重试。");
    } finally {
      setBusy(false);
    }
  };

  return (
    <section
      aria-label={zh ? "修改密码" : "Change password"}
      className="rounded-2xl border border-border/70 bg-background/80 p-6 shadow-soft"
    >
      <h2 className="text-base font-semibold">{zh ? "修改密码" : "Change password"}</h2>
      <form className="mt-4 space-y-3" onSubmit={handleSubmit}>
        <Input
          type="password"
          placeholder={zh ? "当前密码" : "Current password"}
          aria-label={zh ? "当前密码" : "Current password"}
          value={current}
          onChange={(event) => setCurrent(event.target.value)}
          autoComplete="current-password"
          data-testid="pw-current"
        />
        <Input
          type="password"
          placeholder={zh ? "新密码（8–64 位）" : "New password (8–64 chars)"}
          aria-label={zh ? "新密码" : "New password"}
          value={next}
          onChange={(event) => setNext(event.target.value)}
          autoComplete="new-password"
          data-testid="pw-new"
        />
        {error ? <p className="text-sm text-destructive">{error}</p> : null}
        <Button type="submit" disabled={!current || !valid || busy}>
          {busy ? (zh ? "修改中..." : "Updating...") : zh ? "修改密码" : "Change password"}
        </Button>
      </form>
    </section>
  );
}

type ShareTab = "agent" | "answer";

function SharesCard() {
  const { lang } = useOptionalFeedLang();
  const zh = lang === "zh";
  // flag 探测：mine 列表 404（flag 关）隐藏对应 tab——沿用分享钮「404 即未开启」模式
  const [tabs, setTabs] = React.useState<ShareTab[]>([]);
  const [active, setActive] = React.useState<ShareTab>("agent");
  const [agentShares, setAgentShares] = React.useState<AgentShareMineItem[] | null>(null);
  const [answerShares, setAnswerShares] = React.useState<ShareMineItem[] | null>(null);

  React.useEffect(() => {
    let cancelled = false;
    const probe = async () => {
      const enabled: ShareTab[] = [];
      const canAgent = await listMyAgentShares().then(() => true).catch(() => false);
      if (canAgent) enabled.push("agent");
      const canAnswer = await listMyShares().then(() => true).catch(() => false);
      if (canAnswer) enabled.push("answer");
      if (cancelled) return;
      setTabs(enabled);
      if (enabled.length > 0) setActive(enabled[0]);
    };
    probe();
    return () => {
      cancelled = true;
    };
  }, []);

  const reload = React.useCallback(async (tab: ShareTab) => {
    if (tab === "agent") {
      setAgentShares(await listMyAgentShares().catch(() => []));
    } else {
      setAnswerShares(await listMyShares().catch(() => []));
    }
  }, []);

  React.useEffect(() => {
    if (tabs.includes(active)) {
      reload(active);
    }
  }, [active, tabs, reload]);

  const handleRevoke = async (tab: ShareTab, token: string) => {
    try {
      if (tab === "agent") {
        await revokeAgentShare(token);
        setAgentShares((prev) =>
          prev ? prev.map((item) => (item.token === token ? { ...item, status: "REVOKED" } : item)) : prev
        );
      } else {
        await revokeShare(token);
        setAnswerShares((prev) =>
          prev ? prev.map((item) => (item.token === token ? { ...item, status: "REVOKED" } : item)) : prev
        );
      }
      toast.success("已撤销，链接立即失效");
    } catch (error) {
      toast.error((error as Error).message || "撤销失败");
    }
  };

  if (tabs.length === 0) {
    return (
      <section
        aria-label={zh ? "我的分享" : "My shares"}
        className="rounded-2xl border border-border/70 bg-background/80 p-6 shadow-soft"
      >
        <h2 className="text-base font-semibold">{zh ? "我的分享" : "My shares"}</h2>
        <p className="mt-2 text-sm text-muted-foreground">
          {zh ? "分享功能未开启。" : "Sharing is not enabled."}
        </p>
      </section>
    );
  }

  return (
    <section
      aria-label={zh ? "我的分享" : "My shares"}
      className="rounded-2xl border border-border/70 bg-background/80 p-6 shadow-soft"
    >
      <h2 className="text-base font-semibold">{zh ? "我的分享" : "My shares"}</h2>
      <p className="mt-1 text-xs text-muted-foreground">
        {/* #139：不写死保留天数——有效期是配置值（默认 90 天，可调），以每条记录显示的到期时间为准 */}
        {zh
          ? "你分享出去的只读快照；分享链接的到期时间以每条记录显示为准；撤销后链接立即失效。"
          : "Read-only snapshots you have shared; expiry is shown per link and takes effect as displayed; revoked links stop working immediately."}
      </p>
      <div className="mt-4 flex gap-2" role="tablist">
        {tabs.includes("agent") ? (
          <button
            type="button"
            role="tab"
            aria-selected={active === "agent"}
            onClick={() => setActive("agent")}
            className={
              "rounded-full px-3.5 py-1.5 text-[13px] font-medium transition-colors " +
              (active === "agent"
                ? "bg-[var(--polyu-red)] text-white"
                : "border border-border text-muted-foreground hover:text-foreground")
            }
          >
            {zh ? "会话分享" : "Chats"}
          </button>
        ) : null}
        {tabs.includes("answer") ? (
          <button
            type="button"
            role="tab"
            aria-selected={active === "answer"}
            onClick={() => setActive("answer")}
            className={
              "rounded-full px-3.5 py-1.5 text-[13px] font-medium transition-colors " +
              (active === "answer"
                ? "bg-[var(--polyu-red)] text-white"
                : "border border-border text-muted-foreground hover:text-foreground")
            }
          >
            {zh ? "答案分享" : "Answers"}
          </button>
        ) : null}
      </div>
      <div className="mt-4 space-y-2">
        {active === "agent" ? (
          <ShareRows
            items={(agentShares ?? []).map((item) => ({
              token: item.token,
              preview: item.titlePreview || "新对话",
              expireTime: item.expireTime,
              status: item.status
            }))}
            loading={agentShares === null}
            emptyText={zh ? "还没有分享过会话" : "No shared chats yet"}
            onRevoke={(token) => handleRevoke("agent", token)}
          />
        ) : (
          <ShareRows
            items={(answerShares ?? []).map((item) => ({
              token: item.token,
              preview: item.questionPreview || "问答",
              expireTime: item.expireTime,
              status: item.status
            }))}
            loading={answerShares === null}
            emptyText={zh ? "还没有分享过问答" : "No shared answers yet"}
            onRevoke={(token) => handleRevoke("answer", token)}
          />
        )}
      </div>
    </section>
  );
}

interface ShareRowItem {
  token: string;
  preview: string;
  expireTime?: string | null;
  status: string;
}

function ShareRows({
  items,
  loading,
  emptyText,
  onRevoke
}: {
  items: ShareRowItem[];
  loading: boolean;
  emptyText: string;
  onRevoke: (token: string) => void;
}) {
  const { lang } = useOptionalFeedLang();
  const zh = lang === "zh";
  if (loading) {
    return (
      <div className="py-6 text-center text-[12.5px] text-muted-foreground">
        {zh ? "加载中…" : "Loading…"}
      </div>
    );
  }
  if (items.length === 0) {
    return <div className="py-6 text-center text-[12.5px] text-muted-foreground">{emptyText}</div>;
  }
  return (
    <>
      {items.map((item) => {
        const revoked = item.status === "REVOKED";
        return (
          <div
            key={item.token}
            className="flex items-center gap-2.5 rounded-xl border border-border/70 p-3"
          >
            <div className="min-w-0 flex-1">
              <div className="truncate text-[13px] font-medium">{item.preview}</div>
              <div className="mt-0.5 text-[11.5px] text-muted-foreground">
                {revoked
                  ? zh ? "已撤销" : "Revoked"
                  : item.expireTime
                    ? zh
                      ? `过期于 ${new Date(item.expireTime).toLocaleDateString()}`
                      : `Expires ${new Date(item.expireTime).toLocaleDateString()}`
                    : zh ? "永不过期" : "Never expires"}
              </div>
            </div>
            <Button
              variant="outline"
              size="sm"
              disabled={revoked}
              onClick={() => onRevoke(item.token)}
              className="h-7 px-2.5 text-[12px]"
            >
              {revoked ? (zh ? "已撤销" : "Revoked") : zh ? "撤销" : "Revoke"}
            </Button>
          </div>
        );
      })}
    </>
  );
}

function DangerZoneCard() {
  const { lang } = useOptionalFeedLang();
  const zh = lang === "zh";
  const navigate = useNavigate();
  const logout = useAuthStore((state) => state.logout);
  const user = useAuthStore((state) => state.user);
  const isAdmin = user?.role === "admin";
  const [open, setOpen] = React.useState(false);
  const [password, setPassword] = React.useState("");
  const [error, setError] = React.useState<string | null>(null);
  const [busy, setBusy] = React.useState(false);

  const handleDelete = async () => {
    if (!password) {
      setError("请输入密码确认注销。");
      return;
    }
    setError(null);
    setBusy(true);
    try {
      await deleteAccount(password);
      await logout().catch(() => null);
      toast.success("注销申请已受理，进入 30 天冷静期");
      navigate("/", { replace: true });
    } catch (err) {
      setError((err as Error).message || "注销失败，请稍后重试。");
    } finally {
      setBusy(false);
    }
  };

  return (
    <section
      aria-label={zh ? "注销账号" : "Delete account"}
      className="rounded-2xl border border-destructive/40 bg-background/80 p-6 shadow-soft"
    >
      <h2 className="text-base font-semibold text-destructive">
        {zh ? "注销账号" : "Delete account"}
      </h2>
      <p className="mt-2 text-sm text-muted-foreground">
        {zh ? (
          <>
            注销后你的对话记录、分享链接与个人数据将按
            <a href="/privacy" className="underline">隐私声明</a>
            级联清理；申请起 30 天冷静期内可凭账号与密码撤销恢复，到期后不可恢复。
          </>
        ) : (
          <>
            After deletion, your conversations, share links and personal data are cleaned up per the{" "}
            <a href="/privacy" className="underline">Privacy Notice</a>
            ; within a 30-day grace period you can sign in to cancel, after which recovery is impossible.
          </>
        )}
      </p>
      {isAdmin ? (
        <p className="mt-3 rounded-lg bg-muted px-3 py-2 text-sm text-muted-foreground">
          {zh ? "管理员账号不支持自助注销。" : "Admin accounts cannot be self-deleted."}
        </p>
      ) : (
        <Button variant="destructive" className="mt-3" onClick={() => setOpen(true)} data-testid="delete-account">
          {zh ? "注销账号" : "Delete account"}
        </Button>
      )}
      <Dialog open={open} onOpenChange={(next) => (!next ? setOpen(false) : undefined)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{zh ? "确认注销账号？" : "Delete this account?"}</DialogTitle>
            <DialogDescription>
              {zh
                ? "此操作进入 30 天冷静期；期间可凭账号与密码在登录页恢复。到期后数据将被永久删除。"
                : "This starts a 30-day grace period, during which you can recover the account from the sign-in page. After it expires, all data is permanently deleted."}
            </DialogDescription>
          </DialogHeader>
          <Input
            type="password"
            placeholder={zh ? "输入密码确认" : "Enter password to confirm"}
            aria-label={zh ? "密码确认" : "Password confirmation"}
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            autoComplete="current-password"
            data-testid="delete-password"
          />
          {error ? <p className="text-sm text-destructive">{error}</p> : null}
          <DialogFooter>
            <Button variant="outline" onClick={() => setOpen(false)} disabled={busy}>
              {zh ? "取消" : "Cancel"}
            </Button>
            <Button variant="destructive" onClick={handleDelete} disabled={!password || busy} data-testid="delete-confirm">
              {busy ? (zh ? "提交中..." : "Submitting...") : zh ? "确认注销" : "Confirm deletion"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </section>
  );
}

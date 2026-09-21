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
 */
export function AccountPage() {
  const user = useAuthStore((state) => state.user);
  const fetchCurrentUser = useAuthStore((state) => state.fetchCurrentUser);

  return (
    <div className="flex min-h-screen flex-col px-4">
      <div className="mx-auto flex w-full max-w-3xl flex-1 flex-col gap-6 py-8">
        <header>
          <h1 className="text-2xl font-semibold">账号设置</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            Account settings · 管理你的登录标识、邮箱、密码与分享
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
  const user = useAuthStore((state) => state.user);
  const createTime = user?.createTime ? new Date(user.createTime).toLocaleDateString() : null;
  return (
    <section aria-label="账号资料" className="rounded-2xl border border-border/70 bg-background/80 p-6 shadow-soft">
      <h2 className="text-base font-semibold">账号资料 · Profile</h2>
      <dl className="mt-4 grid gap-3 text-sm sm:grid-cols-[130px_1fr]">
        <dt className="text-muted-foreground">用户名</dt>
        <dd className="font-medium">
          {user?.username || "-"}
          <span className="ml-2 text-xs text-muted-foreground">注册后不可修改 · Cannot be changed</span>
        </dd>
        <dt className="text-muted-foreground">角色</dt>
        <dd>{user?.role === "admin" ? "管理员" : "用户"}</dd>
        <dt className="text-muted-foreground">邮箱</dt>
        <dd data-testid="profile-email">
          {email ? (
            <>
              {email}
              {emailVerified === 1 ? (
                <span className="ml-2 rounded bg-emerald-50 px-1.5 py-0.5 text-xs font-medium text-emerald-600">
                  已验证
                </span>
              ) : (
                <span className="ml-2 rounded bg-amber-50 px-1.5 py-0.5 text-xs font-medium text-amber-600">
                  未验证
                </span>
              )}
            </>
          ) : (
            "未设置"
          )}
        </dd>
        {createTime ? (
          <>
            <dt className="text-muted-foreground">注册时间</dt>
            <dd>{createTime}</dd>
          </>
        ) : null}
      </dl>
    </section>
  );
}

function EmailChangeCard({ onChanged }: { onChanged: () => void }) {
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
    <section aria-label="更改邮箱" className="rounded-2xl border border-border/70 bg-background/80 p-6 shadow-soft">
      <h2 className="text-base font-semibold">更改邮箱 · Change email</h2>
      <p className="mt-1 text-xs text-muted-foreground">
        新邮箱收验证码、原邮箱收通知信；更改后原邮箱立即释放可被再注册。用户名不受影响。
      </p>
      {step === "form" ? (
        <form className="mt-4 space-y-3" onSubmit={handleRequest}>
          <Input
            type="email"
            placeholder="新邮箱 · new@example.com"
            value={newEmail}
            onChange={(event) => setNewEmail(event.target.value)}
            autoComplete="email"
            data-testid="new-email"
          />
          <Input
            type="password"
            placeholder="当前密码"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            autoComplete="current-password"
            data-testid="email-current-password"
          />
          {error ? <p className="text-sm text-destructive">{error}</p> : null}
          <Button type="submit" disabled={!emailValid || !password || busy}>
            {busy ? "发送中..." : "发送验证码"}
          </Button>
        </form>
      ) : (
        <form className="mt-4 space-y-3" onSubmit={handleConfirm}>
          <p className="text-sm text-muted-foreground">
            验证码已发送至 <span className="font-medium text-foreground">{newEmail.trim()}</span>
          </p>
          <Input
            placeholder="6 位验证码"
            value={code}
            onChange={(event) => setCode(event.target.value.replace(/\D/g, "").slice(0, 6))}
            inputMode="numeric"
            data-testid="email-code"
          />
          {error ? <p className="text-sm text-destructive">{error}</p> : null}
          <div className="flex gap-2">
            <Button type="submit" disabled={!code.trim() || busy}>
              {busy ? "验证中..." : "确认更改"}
            </Button>
            <Button type="button" variant="outline" onClick={() => setStep("form")}>
              返回
            </Button>
          </div>
        </form>
      )}
    </section>
  );
}

function PasswordCard() {
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
    <section aria-label="修改密码" className="rounded-2xl border border-border/70 bg-background/80 p-6 shadow-soft">
      <h2 className="text-base font-semibold">修改密码 · Change password</h2>
      <form className="mt-4 space-y-3" onSubmit={handleSubmit}>
        <Input
          type="password"
          placeholder="当前密码"
          value={current}
          onChange={(event) => setCurrent(event.target.value)}
          autoComplete="current-password"
          data-testid="pw-current"
        />
        <Input
          type="password"
          placeholder="新密码（8–64 位）"
          value={next}
          onChange={(event) => setNext(event.target.value)}
          autoComplete="new-password"
          data-testid="pw-new"
        />
        {error ? <p className="text-sm text-destructive">{error}</p> : null}
        <Button type="submit" disabled={!current || !valid || busy}>
          {busy ? "修改中..." : "修改密码"}
        </Button>
      </form>
    </section>
  );
}

type ShareTab = "agent" | "answer";

function SharesCard() {
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
      <section aria-label="我的分享" className="rounded-2xl border border-border/70 bg-background/80 p-6 shadow-soft">
        <h2 className="text-base font-semibold">我的分享 · My shares</h2>
        <p className="mt-2 text-sm text-muted-foreground">分享功能未开启。</p>
      </section>
    );
  }

  return (
    <section aria-label="我的分享" className="rounded-2xl border border-border/70 bg-background/80 p-6 shadow-soft">
      <h2 className="text-base font-semibold">我的分享 · My shares</h2>
      <p className="mt-1 text-xs text-muted-foreground">
        你分享出去的只读快照；撤销后链接立即失效。快照保留 90 天后由系统清理。
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
            会话分享
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
            答案分享
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
            emptyText="还没有分享过会话"
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
            emptyText="还没有分享过问答"
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
  if (loading) {
    return <div className="py-6 text-center text-[12.5px] text-muted-foreground">加载中…</div>;
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
                  ? "已撤销"
                  : item.expireTime
                    ? `过期于 ${new Date(item.expireTime).toLocaleDateString()}`
                    : "永不过期"}
              </div>
            </div>
            <Button
              variant="outline"
              size="sm"
              disabled={revoked}
              onClick={() => onRevoke(item.token)}
              className="h-7 px-2.5 text-[12px]"
            >
              {revoked ? "已撤销" : "撤销"}
            </Button>
          </div>
        );
      })}
    </>
  );
}

function DangerZoneCard() {
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
      aria-label="注销账号"
      className="rounded-2xl border border-destructive/40 bg-background/80 p-6 shadow-soft"
    >
      <h2 className="text-base font-semibold text-destructive">注销账号 · Delete account</h2>
      <p className="mt-2 text-sm text-muted-foreground">
        注销后你的对话记录、分享链接与个人数据将按
        <a href="/privacy" className="underline">隐私声明</a>
        级联清理；申请起 30 天冷静期内可凭账号与密码撤销恢复，到期后不可恢复。
      </p>
      {isAdmin ? (
        <p className="mt-3 rounded-lg bg-muted px-3 py-2 text-sm text-muted-foreground">
          管理员账号不支持自助注销。
        </p>
      ) : (
        <Button variant="destructive" className="mt-3" onClick={() => setOpen(true)} data-testid="delete-account">
          注销账号
        </Button>
      )}
      <Dialog open={open} onOpenChange={(next) => (!next ? setOpen(false) : undefined)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>确认注销账号？</DialogTitle>
            <DialogDescription>
              此操作进入 30 天冷静期；期间可凭账号与密码在登录页恢复。到期后数据将被永久删除。
            </DialogDescription>
          </DialogHeader>
          <Input
            type="password"
            placeholder="输入密码确认"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            autoComplete="current-password"
            data-testid="delete-password"
          />
          {error ? <p className="text-sm text-destructive">{error}</p> : null}
          <DialogFooter>
            <Button variant="outline" onClick={() => setOpen(false)} disabled={busy}>
              取消
            </Button>
            <Button variant="destructive" onClick={handleDelete} disabled={!password || busy} data-testid="delete-confirm">
              {busy ? "提交中..." : "确认注销"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </section>
  );
}

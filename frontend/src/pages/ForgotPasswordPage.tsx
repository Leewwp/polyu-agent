import * as React from "react";
import { Link, useNavigate } from "react-router-dom";
import { Eye, EyeOff, KeyRound, Lock, Mail } from "lucide-react";

import { AuthShell } from "@/components/common/AuthShell";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { requestPasswordReset, resetPassword } from "@/services/authService";

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

type Step = "request" | "reset" | "done";

/**
 * 忘记密码 / 密码重置页（U11-④，端点=U2）：先提交邮箱（受理口径统一，
 * 无论邮箱是否已注册都进入下一步——重置码只有邮箱主人可收），再提交
 * 重置码 + 新密码。与注册端点同挂 ragent.registration.enabled flag。
 */
export function ForgotPasswordPage() {
  const navigate = useNavigate();
  const [step, setStep] = React.useState<Step>("request");
  const [email, setEmail] = React.useState("");
  const [form, setForm] = React.useState({ code: "", newPassword: "" });
  const [showPassword, setShowPassword] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = React.useState(false);
  const [isResetting, setIsResetting] = React.useState(false);

  const emailValid = EMAIL_RE.test(email.trim());
  const passwordValid = form.newPassword.length >= 8 && form.newPassword.length <= 64;
  const canRequest = emailValid && !isSubmitting;
  const canReset = emailValid && form.code.trim().length > 0 && passwordValid && !isResetting;

  const handleRequest = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!emailValid) {
      setError("请填写有效的邮箱地址。");
      return;
    }
    setError(null);
    setIsSubmitting(true);
    try {
      // 受理口径统一：不区分邮箱是否已注册，静默进入下一步
      await requestPasswordReset(email.trim());
      setStep("reset");
    } catch (err) {
      setError((err as Error).message || "提交失败，请稍后重试。");
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleReset = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!canReset) {
      setError("请填写验证码与 8–64 位新密码。");
      return;
    }
    setError(null);
    setIsResetting(true);
    try {
      await resetPassword(email.trim(), form.code.trim(), form.newPassword);
      setStep("done");
    } catch (err) {
      setError((err as Error).message || "重置失败，请稍后重试。");
    } finally {
      setIsResetting(false);
    }
  };

  return (
    <AuthShell title="找回密码" titleEn="Reset your password">
      {step === "request" ? (
        <form className="space-y-4" onSubmit={handleRequest}>
          <p className="text-sm text-muted-foreground">
            输入注册邮箱，我们会发送密码重置验证码。
          </p>
          <p className="text-xs text-muted-foreground">
            Enter your registered email and we will send you a reset code.
          </p>
          <div className="space-y-2">
            <label className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              邮箱 · Email
            </label>
            <div className="relative">
              <Mail className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                type="email"
                placeholder="you@example.com"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                className="pl-10"
                autoComplete="email"
                inputMode="email"
              />
            </div>
          </div>
          {error ? <p className="text-sm text-destructive">{error}</p> : null}
          <Button type="submit" className="w-full" disabled={!canRequest}>
            {isSubmitting ? "正在提交..." : "发送重置码"}
          </Button>
          <p className="text-center text-sm text-muted-foreground">
            想起密码了？{" "}
            <Link to="/login" className="underline">
              返回登录
            </Link>
          </p>
        </form>
      ) : null}

      {step === "reset" ? (
        <form className="space-y-4" onSubmit={handleReset}>
          <p className="text-sm text-muted-foreground">
            重置码已发送至 <span className="font-medium text-foreground">{email.trim()}</span>
            （若该邮箱已注册）。请输入验证码与新密码。
          </p>
          <p className="text-xs text-muted-foreground">
            A reset code has been sent to your email if it is registered. Enter the code and a new
            password below.
          </p>
          <div className="space-y-2">
            <label className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              重置码 · Code
            </label>
            <div className="relative">
              <KeyRound className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                placeholder="6 位数字"
                value={form.code}
                onChange={(event) =>
                  setForm((prev) => ({
                    ...prev,
                    code: event.target.value.replace(/\D/g, "").slice(0, 6)
                  }))
                }
                className="pl-10"
                autoComplete="one-time-code"
                inputMode="numeric"
              />
            </div>
          </div>
          <div className="space-y-2">
            <label className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              新密码 · New password
            </label>
            <div className="relative">
              <Lock className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                type={showPassword ? "text" : "password"}
                placeholder="8–64 位字符"
                value={form.newPassword}
                onChange={(event) =>
                  setForm((prev) => ({ ...prev, newPassword: event.target.value }))
                }
                className="pl-10 pr-10"
                autoComplete="new-password"
                maxLength={64}
              />
              <button
                type="button"
                onClick={() => setShowPassword((prev) => !prev)}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground"
                aria-label="显示或隐藏密码"
              >
                {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              </button>
            </div>
            <p className="text-xs text-muted-foreground">
              密码长度 8–64 字符 · Password must be 8–64 characters.
            </p>
          </div>
          {error ? <p className="text-sm text-destructive">{error}</p> : null}
          <Button type="submit" className="w-full" disabled={!canReset}>
            {isResetting ? "正在重置..." : "重置密码"}
          </Button>
          <button
            type="button"
            className="w-full text-center text-sm text-muted-foreground underline"
            onClick={() => {
              setStep("request");
              setForm({ code: "", newPassword: "" });
              setError(null);
            }}
          >
            返回修改邮箱 · Use a different email
          </button>
        </form>
      ) : null}

      {step === "done" ? (
        <div className="space-y-4">
          <p className="text-sm text-muted-foreground">密码已重置，请使用新密码登录。</p>
          <p className="text-xs text-muted-foreground">
            Your password has been reset. Please sign in with your new password.
          </p>
          <Button className="w-full" onClick={() => navigate("/login")}>
            前往登录 · Sign in
          </Button>
        </div>
      ) : null}
    </AuthShell>
  );
}

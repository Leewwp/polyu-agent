import * as React from "react";
import { Link, useNavigate } from "react-router-dom";
import { Eye, EyeOff, Lock, Mail } from "lucide-react";

import { AuthShell } from "@/components/common/AuthShell";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { register, resendVerificationCode, verifyEmail } from "@/services/authService";

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const RESEND_COOLDOWN_SECONDS = 60;

type Step = "form" | "verify" | "done";

/**
 * 自助注册页（U11-④，端点=U2）：注册表单（含 U8 勾选确认，未勾选不放行）→
 * 邮箱验证码（60s 重发冷却对齐 T10）→ 完成引导登录。
 * 后端 flag（ragent.registration.enabled）默认关，关闭态接口统一回
 * 「注册通道当前未开放」，直接内联展示，前端不做 flag 判断。
 */
export function RegisterPage() {
  const navigate = useNavigate();
  const [step, setStep] = React.useState<Step>("form");
  const [form, setForm] = React.useState({ email: "", password: "" });
  const [agreed, setAgreed] = React.useState(false);
  const [code, setCode] = React.useState("");
  const [showPassword, setShowPassword] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);
  const [notice, setNotice] = React.useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = React.useState(false);
  const [isVerifying, setIsVerifying] = React.useState(false);
  const [isResending, setIsResending] = React.useState(false);
  const [cooldown, setCooldown] = React.useState(0);

  React.useEffect(() => {
    if (cooldown <= 0) return;
    const timer = window.setInterval(() => {
      setCooldown((prev) => (prev <= 1 ? 0 : prev - 1));
    }, 1000);
    return () => window.clearInterval(timer);
  }, [cooldown]);

  const emailValid = EMAIL_RE.test(form.email.trim());
  const passwordValid = form.password.length >= 8 && form.password.length <= 64;
  const canSubmit = emailValid && passwordValid && agreed && !isSubmitting;

  const handleRegister = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!agreed) {
      setError("请先阅读并勾选同意隐私声明与服务条款。");
      return;
    }
    if (!emailValid || !passwordValid) {
      setError("请填写有效的邮箱与 8–64 位密码。");
      return;
    }
    setError(null);
    setIsSubmitting(true);
    try {
      // 注册受理口径统一（邮箱已注册静默受理），无返回数据可判断注册状态
      await register(form.email.trim(), form.password);
      setCooldown(RESEND_COOLDOWN_SECONDS);
      setStep("verify");
    } catch (err) {
      setError((err as Error).message || "注册失败，请稍后重试。");
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleVerify = async (event: React.FormEvent) => {
    event.preventDefault();
    if (code.trim().length === 0) {
      setError("请输入邮箱中的验证码。");
      return;
    }
    setError(null);
    setIsVerifying(true);
    try {
      await verifyEmail(form.email.trim(), code.trim());
      setStep("done");
    } catch (err) {
      setError((err as Error).message || "验证失败，请稍后重试。");
    } finally {
      setIsVerifying(false);
    }
  };

  const handleResend = async () => {
    if (cooldown > 0 || isResending) return;
    setError(null);
    setNotice(null);
    setIsResending(true);
    try {
      await resendVerificationCode(form.email.trim());
      setCooldown(RESEND_COOLDOWN_SECONDS);
      setNotice("验证码已重新发送，请查收邮箱。");
    } catch (err) {
      setError((err as Error).message || "重发失败，请稍后重试。");
    } finally {
      setIsResending(false);
    }
  };

  return (
    <AuthShell title="创建账号" titleEn="Create your account">
      {step === "form" ? (
        <form className="space-y-4" onSubmit={handleRegister}>
          <div className="space-y-2">
            <label className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              邮箱 · Email
            </label>
            <div className="relative">
              <Mail className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                type="email"
                placeholder="you@example.com"
                value={form.email}
                onChange={(event) => setForm((prev) => ({ ...prev, email: event.target.value }))}
                className="pl-10"
                autoComplete="email"
                inputMode="email"
              />
            </div>
          </div>
          <div className="space-y-2">
            <label className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              密码 · Password
            </label>
            <div className="relative">
              <Lock className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                type={showPassword ? "text" : "password"}
                placeholder="8–64 位字符"
                value={form.password}
                onChange={(event) => setForm((prev) => ({ ...prev, password: event.target.value }))}
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
          <div className="space-y-1">
            <label className="flex items-start gap-2 text-sm text-muted-foreground">
              <Checkbox
                checked={agreed}
                onCheckedChange={(value) => setAgreed(Boolean(value))}
                aria-label="同意隐私声明与服务条款"
              />
              <span className="min-w-0 leading-snug">
                我已阅读并同意
                <Link to="/privacy" className="underline">
                  隐私声明
                </Link>
                与
                <Link to="/terms" className="underline">
                  服务条款
                </Link>
                。
              </span>
            </label>
            <p className="pl-7 text-xs text-muted-foreground">
              I have read and agree to the Privacy Notice and Terms of Service.
            </p>
          </div>
          {error ? <p className="text-sm text-destructive">{error}</p> : null}
          <Button type="submit" className="w-full" disabled={!canSubmit}>
            {isSubmitting ? "正在提交..." : "注册"}
          </Button>
          <p className="text-center text-sm text-muted-foreground">
            已有账号？{" "}
            <Link to="/login" className="underline">
              直接登录
            </Link>
          </p>
        </form>
      ) : null}

      {step === "verify" ? (
        <form className="space-y-4" onSubmit={handleVerify}>
          <p className="text-sm text-muted-foreground">
            验证码已发送至 <span className="font-medium text-foreground">{form.email.trim()}</span>
            ，请输入 6 位验证码完成邮箱验证。
          </p>
          <p className="text-xs text-muted-foreground">
            A 6-digit verification code has been sent to your email. Enter it below to verify your
            address.
          </p>
          <div className="space-y-2">
            <label className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              验证码 · Code
            </label>
            <Input
              placeholder="6 位数字"
              value={code}
              onChange={(event) => setCode(event.target.value.replace(/\D/g, "").slice(0, 6))}
              autoComplete="one-time-code"
              inputMode="numeric"
              className="text-center text-lg tracking-[0.5em]"
            />
          </div>
          {error ? <p className="text-sm text-destructive">{error}</p> : null}
          {notice ? <p className="text-sm text-emerald-600">{notice}</p> : null}
          <div className="flex items-center gap-2">
            <Button type="submit" className="flex-1" disabled={isVerifying}>
              {isVerifying ? "正在验证..." : "完成验证"}
            </Button>
            <Button
              type="button"
              variant="outline"
              onClick={handleResend}
              disabled={cooldown > 0 || isResending}
            >
              {cooldown > 0 ? `${cooldown}s` : isResending ? "发送中..." : "重发验证码"}
            </Button>
          </div>
          <button
            type="button"
            className="w-full text-center text-sm text-muted-foreground underline"
            onClick={() => {
              setStep("form");
              setCode("");
              setError(null);
              setNotice(null);
            }}
          >
            返回修改邮箱 · Use a different email
          </button>
        </form>
      ) : null}

      {step === "done" ? (
        <div className="space-y-4">
          <p className="text-sm text-muted-foreground">邮箱验证完成，现在可以使用该账号登录了。</p>
          <p className="text-xs text-muted-foreground">
            Your email is verified. You can now sign in with your account.
          </p>
          <Button className="w-full" onClick={() => navigate("/login")}>
            前往登录 · Sign in
          </Button>
        </div>
      ) : null}
    </AuthShell>
  );
}

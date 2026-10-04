import * as React from "react";
import { Link, useNavigate } from "react-router-dom";
import { Eye, EyeOff, Lock, Mail, User } from "lucide-react";

import { AuthShell } from "@/components/common/AuthShell";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { useOptionalFeedLang } from "@/components/feed/feedLang";
import { usePageTitle } from "@/hooks/usePageTitle";
import { register, resendVerificationCode, verifyEmail } from "@/services/authService";

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
/** 与后端 UsernamePolicy 镜像：小写字母/数字/_/-，3–20 位（不区分大小写，提交前小写化） */
const USERNAME_RE = /^[a-z0-9_-]{3,20}$/;
const RESEND_COOLDOWN_SECONDS = 60;

type Step = "form" | "verify" | "done";

/**
 * 自助注册页：注册表单（含勾选确认，未勾选不放行）→
 * 邮箱验证码（60s 重发冷却对齐后端）→ 完成引导登录。
 * 后端 flag（ragent.registration.enabled）默认关，关闭态接口统一回
 * 「注册通道当前未开放」，直接内联展示，前端不做 flag 判断。
 * #227：按钮/表单用途名称/提示随全局语言；动态错误透传后端文案不翻。
 * #235：可见 label 经 htmlFor/id 与输入显式关联（含验证码第二阶段），无可见标签的控件才用 aria-label。
 */
export function RegisterPage() {
  const { lang } = useOptionalFeedLang();
  const zh = lang === "zh";
  usePageTitle({ zh: "注册", en: "Register" });
  const navigate = useNavigate();
  const [step, setStep] = React.useState<Step>("form");
  const [form, setForm] = React.useState({ username: "", email: "", password: "" });
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

  const usernameValid = USERNAME_RE.test(form.username.trim().toLowerCase());
  const emailValid = EMAIL_RE.test(form.email.trim());
  const passwordValid = form.password.length >= 8 && form.password.length <= 64;
  const canSubmit = usernameValid && emailValid && passwordValid && agreed && !isSubmitting;

  const handleRegister = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!agreed) {
      setError("请先阅读并勾选同意隐私声明与服务条款。");
      return;
    }
    if (!usernameValid || !emailValid || !passwordValid) {
      setError("请填写有效的用户名（3–20 位小写字母/数字/_/-）、邮箱与 8–64 位密码。");
      return;
    }
    setError(null);
    setIsSubmitting(true);
    try {
      // 重复邮箱/用户名由后端显式报错（#103），错误文案经此透传内联展示
      await register(form.username.trim().toLowerCase(), form.email.trim(), form.password);
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
    <AuthShell
      title={zh ? "创建账号" : "Create your account"}
      titleEn={zh ? "Create your account" : undefined}
    >
      {step === "form" ? (
        <form className="space-y-4" onSubmit={handleRegister}>
          <div className="space-y-2">
            <label
              htmlFor="register-username"
              className="text-xs font-semibold uppercase tracking-wide text-muted-foreground"
            >
              {zh ? "用户名" : "Username"}
            </label>
            <div className="relative">
              <User className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                id="register-username"
                placeholder={zh ? "3–20 位小写字母、数字、_ 或 -" : "3–20 chars: lowercase letters, digits, _ or -"}
                value={form.username}
                onChange={(event) => setForm((prev) => ({ ...prev, username: event.target.value }))}
                className="pl-10"
                autoComplete="username"
                maxLength={20}
              />
            </div>
            <p className="text-xs text-muted-foreground">
              {zh
                ? "注册后不可修改 · 不区分大小写 · 用于登录与展示。Cannot be changed after sign-up."
                : "Cannot be changed after sign-up · Case-insensitive · Used for sign-in and display."}
            </p>
          </div>
          <div className="space-y-2">
            <label
              htmlFor="register-email"
              className="text-xs font-semibold uppercase tracking-wide text-muted-foreground"
            >
              {zh ? "邮箱" : "Email"}
            </label>
            <div className="relative">
              <Mail className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                id="register-email"
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
            <label
              htmlFor="register-password"
              className="text-xs font-semibold uppercase tracking-wide text-muted-foreground"
            >
              {zh ? "密码" : "Password"}
            </label>
            <div className="relative">
              <Lock className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                id="register-password"
                type={showPassword ? "text" : "password"}
                placeholder={zh ? "8–64 位字符" : "8–64 characters"}
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
                aria-label={zh ? "显示或隐藏密码" : "Show or hide password"}
              >
                {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              </button>
            </div>
            <p className="text-xs text-muted-foreground">
              {zh ? "密码长度 8–64 字符 · " : ""}Password must be 8–64 characters.
            </p>
          </div>
          <div className="space-y-1">
            <label className="flex items-start gap-2 text-sm text-muted-foreground">
              <Checkbox
                checked={agreed}
                onCheckedChange={(value) => setAgreed(Boolean(value))}
                aria-label={zh ? "同意隐私声明与服务条款" : "Agree to the Privacy Notice and Terms of Service"}
              />
              <span className="min-w-0 leading-snug">
                {zh ? "我已阅读并同意" : "I have read and agree to the "}
                <Link to="/privacy" className="underline">
                  {zh ? "隐私声明" : "Privacy Notice"}
                </Link>
                {zh ? "与" : " and "}
                <Link to="/terms" className="underline">
                  {zh ? "服务条款" : "Terms of Service"}
                </Link>
                {zh ? "。" : "."}
              </span>
            </label>
            {zh ? (
              <p className="pl-7 text-xs text-muted-foreground">
                I have read and agree to the Privacy Notice and Terms of Service.
              </p>
            ) : null}
          </div>
          {error ? <p className="text-sm text-destructive">{error}</p> : null}
          <Button type="submit" className="min-h-[44px] w-full" disabled={!canSubmit}>
            {isSubmitting ? (zh ? "正在提交..." : "Submitting...") : zh ? "注册" : "Sign up"}
          </Button>
          <p className="text-center text-sm text-muted-foreground">
            {zh ? "已有账号？ " : "Already have an account? "}
            <Link to="/login" className="underline">
              {zh ? "直接登录" : "Sign in"}
            </Link>
          </p>
        </form>
      ) : null}

      {step === "verify" ? (
        <form className="space-y-4" onSubmit={handleVerify}>
          <p className="text-sm text-muted-foreground">
            {zh
              ? "验证码已发送至 "
              : "A 6-digit code has been sent to "}
            <span className="font-medium text-foreground">{form.email.trim()}</span>
            {zh ? "，请输入 6 位验证码完成邮箱验证。" : ". Enter it below to verify your address."}
          </p>
          <div className="space-y-2">
            <label
              htmlFor="register-code"
              className="text-xs font-semibold uppercase tracking-wide text-muted-foreground"
            >
              {zh ? "验证码" : "Code"}
            </label>
            <Input
              id="register-code"
              placeholder={zh ? "6 位数字" : "6-digit code"}
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
            <Button type="submit" className="min-h-[44px] flex-1" disabled={isVerifying}>
              {isVerifying ? (zh ? "正在验证..." : "Verifying...") : zh ? "完成验证" : "Verify"}
            </Button>
            <Button
              type="button"
              variant="outline"
              onClick={handleResend}
              disabled={cooldown > 0 || isResending}
            >
              {cooldown > 0 ? `${cooldown}s` : isResending ? (zh ? "发送中..." : "Sending...") : zh ? "重发验证码" : "Resend code"}
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
            {zh ? "返回修改邮箱 · Use a different email" : "Use a different email"}
          </button>
        </form>
      ) : null}

      {step === "done" ? (
        <div className="space-y-4">
          <p className="text-sm text-muted-foreground">
            {zh ? "邮箱验证完成，现在可以使用该账号登录了。" : "Your email is verified. You can now sign in with your account."}
          </p>
          <Button className="w-full" onClick={() => navigate("/login")}>
            {zh ? "前往登录 · Sign in" : "Sign in"}
          </Button>
        </div>
      ) : null}
    </AuthShell>
  );
}

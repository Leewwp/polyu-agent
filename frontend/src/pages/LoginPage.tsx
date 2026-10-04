import * as React from "react";
import { Eye, EyeOff, Lock, User } from "lucide-react";
import { Link, useNavigate } from "react-router-dom";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Checkbox } from "@/components/ui/checkbox";
import { SiteFooter } from "@/components/layout/SiteFooter";
import { useOptionalFeedLang } from "@/components/feed/feedLang";
import { useAuthStore } from "@/stores/authStore";

/** 登录页（#227 消费面）：按钮/表单用途名称/提示随全局语言；动态错误透传后端文案不翻。 */
export function LoginPage() {
  const { lang } = useOptionalFeedLang();
  const zh = lang === "zh";
  const navigate = useNavigate();
  const { login, isLoading } = useAuthStore();
  const [showPassword, setShowPassword] = React.useState(false);
  const [remember, setRemember] = React.useState(true);
  const [form, setForm] = React.useState({ username: "", password: "" });
  const [error, setError] = React.useState<string | null>(null);

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    setError(null);
    if (!form.username.trim() || !form.password.trim()) {
      setError("请输入用户名和密码。");
      return;
    }
    try {
      await login(form.username.trim(), form.password.trim());
      if (!remember) {
        // 如需仅在内存中保存登录态，可在此扩展。
      }
      navigate("/chat");
    } catch (err) {
      setError((err as Error).message || "登录失败，请稍后重试。");
    }
  };

  return (
    <div className="relative flex min-h-screen flex-col px-4">
      <div className="absolute inset-0 bg-gradient-to-br from-slate-50 via-blue-50/50 to-blue-100 dark:from-slate-950 dark:via-slate-900 dark:to-slate-900" />
      <div className="relative z-10 flex flex-1 items-center justify-center py-8">
        <div className="w-full max-w-md rounded-3xl border border-border/70 bg-background/80 p-8 shadow-soft backdrop-blur">
          <div className="mb-6">
            <p className="font-display text-2xl font-semibold">{zh ? "欢迎回来" : "Welcome back"}</p>
            <p className="mt-1 text-sm text-muted-foreground">
              {zh ? "登录后继续你的检索增强对话。" : "Sign in to continue your retrieval-augmented chats."}
            </p>
          </div>
          <form className="space-y-4" onSubmit={handleSubmit}>
            <div className="space-y-2">
              <label className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                {zh ? "用户名或邮箱" : "Username or email"}
              </label>
              <div className="relative">
                <User className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                <Input
                  placeholder={zh ? "请输入用户名或邮箱" : "Enter username or email"}
                  aria-label={zh ? "用户名或邮箱" : "Username or email"}
                  value={form.username}
                  onChange={(event) =>
                    setForm((prev) => ({ ...prev, username: event.target.value }))
                  }
                  className="pl-10"
                  autoComplete="username"
                />
              </div>
            </div>
            <div className="space-y-2">
              <label className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                {zh ? "密码" : "Password"}
              </label>
              <div className="relative">
                <Lock className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                <Input
                  type={showPassword ? "text" : "password"}
                  placeholder={zh ? "请输入密码" : "Enter password"}
                  aria-label={zh ? "密码" : "Password"}
                  value={form.password}
                  onChange={(event) =>
                    setForm((prev) => ({ ...prev, password: event.target.value }))
                  }
                  className="pl-10 pr-10"
                  autoComplete="current-password"
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
            </div>
            <div className="flex items-center justify-between text-sm">
              <label className="flex items-center gap-2 text-muted-foreground">
                <Checkbox
                  checked={remember}
                  onCheckedChange={(value) => setRemember(Boolean(value))}
                  aria-label={zh ? "记住我" : "Remember me"}
                />
                {zh ? "记住我" : "Remember me"}
              </label>
              <Link
                to="/forgot-password"
                className="text-xs text-muted-foreground hover:underline"
              >
                {zh ? "忘记密码？" : "Forgot password?"}
              </Link>
            </div>
            {error ? <p className="text-sm text-destructive">{error}</p> : null}
            <Button type="submit" className="w-full" disabled={isLoading}>
              {isLoading ? (zh ? "正在登录..." : "Signing in...") : zh ? "登录" : "Sign in"}
            </Button>
            <p className="text-center text-sm text-muted-foreground">
              {zh ? "还没有账号？ " : "No account yet? "}
              <Link to="/register" className="underline">
                {zh ? "注册" : "Sign up"}
              </Link>
            </p>
          </form>
        </div>
      </div>
      <SiteFooter className="relative z-10" />
    </div>
  );
}

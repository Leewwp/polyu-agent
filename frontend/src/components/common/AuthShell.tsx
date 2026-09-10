import * as React from "react";

import { SiteFooter } from "@/components/layout/SiteFooter";

interface AuthShellProps {
  /** 主标题（中文） */
  title: string;
  /** 主标题英文随行（内联双语，紧凑式「zh · En」由调用方排好） */
  titleEn?: string;
  children: React.ReactNode;
}

/**
 * 认证页共用壳（U11-④）：渐变背景 + 居中卡片 + 常驻页脚，
 * 视觉规格与 LoginPage 卡片逐类名对齐，注册/找回密码页与登录页并排无跳变。
 */
export function AuthShell({ title, titleEn, children }: AuthShellProps) {
  return (
    <div className="relative flex min-h-screen flex-col px-4">
      <div className="absolute inset-0 bg-gradient-to-br from-slate-50 via-blue-50/50 to-blue-100 dark:from-slate-950 dark:via-slate-900 dark:to-slate-900" />
      <div className="relative z-10 flex flex-1 items-center justify-center py-8">
        <div className="w-full max-w-md rounded-3xl border border-border/70 bg-background/80 p-8 shadow-soft backdrop-blur">
          <div className="mb-6">
            <p className="font-display text-2xl font-semibold">{title}</p>
            {titleEn ? (
              <p className="mt-0.5 text-sm font-normal text-muted-foreground">{titleEn}</p>
            ) : null}
          </div>
          {children}
        </div>
      </div>
      <SiteFooter className="relative z-10" />
    </div>
  );
}

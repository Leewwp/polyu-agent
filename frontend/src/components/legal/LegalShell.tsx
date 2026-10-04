import * as React from "react";
import { Link } from "react-router-dom";

import { BrandMark } from "@/components/site/BrandMark";
import { Button } from "@/components/ui/button";
import { SiteFooter } from "@/components/layout/SiteFooter";
import { useOptionalFeedLang } from "@/components/feed/feedLang";
import { usePageTitle } from "@/hooks/usePageTitle";
import { useAuthStore } from "@/stores/authStore";

interface LegalShellProps {
  title: string;
  titleEn: string;
  children: React.ReactNode;
}

/**
 * 法务静态页共用壳：公开无守卫路由，自绘 header（同 SharePage 范式），底部自带三链接页脚。
 * #227：返回钮随全局语言单语（原「返回 · Back」硬拼拆开）；页脚由 SiteFooter 随语言。
 * #229：头部品牌位换 PolyUGuide（BrandMark，随主站系视觉），轻量布局保持不并入资讯壳。
 * 法务正文保持中英并列（Q7 已确认），段落级 lang 标注由各法务页按段标注。
 * #231：法务三页页面名经本壳单点接入标题机制（标签随语言取 title/titleEn）。
 */
export function LegalShell({ title, titleEn, children }: LegalShellProps) {
  const { lang } = useOptionalFeedLang();
  const zh = lang === "zh";
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  const backHref = isAuthenticated ? "/chat" : "/login";
  usePageTitle({ zh: title, en: titleEn });

  return (
    <div className="mx-auto flex min-h-screen w-full max-w-3xl flex-col px-4 py-8 sm:py-12">
      <header className="mb-8 flex items-center justify-between">
        <BrandMark />
        <Button asChild variant="ghost" size="sm" className="min-h-[44px] text-[#666666]">
          <Link to={backHref}>{zh ? "返回" : "Back"}</Link>
        </Button>
      </header>
      <main className="flex-1">
        <h1 className="font-display text-2xl font-semibold">
          {title} <span className="text-muted-foreground">· {titleEn}</span>
        </h1>
        <div className="mt-6 space-y-8 text-sm leading-relaxed text-foreground/90">{children}</div>
      </main>
      <SiteFooter className="mt-8" />
    </div>
  );
}

interface LegalSectionProps {
  heading: string;
  headingEn: string;
  children: React.ReactNode;
}

/** 双语小节：中文段在前、英文段在后（对齐 SharePage 无效态的分段双语范式） */
export function LegalSection({ heading, headingEn, children }: LegalSectionProps) {
  return (
    <section>
      <h2 className="mb-2 text-base font-semibold">
        {heading} <span className="text-muted-foreground">· {headingEn}</span>
      </h2>
      {children}
    </section>
  );
}

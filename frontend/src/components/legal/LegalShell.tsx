import * as React from "react";
import { GraduationCap } from "lucide-react";
import { Link } from "react-router-dom";

import { Button } from "@/components/ui/button";
import { SiteFooter } from "@/components/layout/SiteFooter";
import { useAuthStore } from "@/stores/authStore";

interface LegalShellProps {
  title: string;
  titleEn: string;
  children: React.ReactNode;
}

/**
 * 法务静态页共用壳：公开无守卫路由，自绘 header（同 SharePage 范式），底部自带三链接页脚。
 */
export function LegalShell({ title, titleEn, children }: LegalShellProps) {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  const backHref = isAuthenticated ? "/chat" : "/login";

  return (
    <div className="mx-auto flex min-h-screen w-full max-w-3xl flex-col px-4 py-8 sm:py-12">
      <header className="mb-8 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-[#BFDBFE]">
            <GraduationCap className="h-5 w-5 text-[#2563EB]" />
          </div>
          <span className="text-sm font-medium text-[#1A1A1A]">PolyU Wayfinder</span>
        </div>
        <Button asChild variant="ghost" size="sm" className="text-[#666666]">
          <Link to={backHref}>返回 · Back</Link>
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

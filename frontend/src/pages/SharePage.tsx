import * as React from "react";
import { Link, useParams } from "react-router-dom";
import { GraduationCap, ShieldAlert } from "lucide-react";

import { MarkdownRenderer } from "@/components/chat/MarkdownRenderer";
import { Button } from "@/components/ui/button";
import { useAuthStore } from "@/stores/authStore";

import { SiteFooter } from "@/components/layout/SiteFooter";
import type { PublicShare } from "@/services/shareService";
import { getPublicShare } from "@/services/shareService";

/**
 * 公开分享页（匿名可访问）：渲染不可变 Q&A 快照 + 结构化官方引用。
 * 首发要求搜索引擎不收录：meta robots noindex（后端另有 X-Robots-Tag 双保险）。
 * 始终显示非官方与时效提示。
 */
export function SharePage() {
  const { token } = useParams<{ token: string }>();
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  const [share, setShare] = React.useState<PublicShare | null>(null);
  const [invalid, setInvalid] = React.useState(false);
  const [loading, setLoading] = React.useState(true);

  // 首发要求搜索引擎不收录
  React.useEffect(() => {
    const meta = document.createElement("meta");
    meta.name = "robots";
    meta.content = "noindex, nofollow";
    document.head.appendChild(meta);
    return () => {
      document.head.removeChild(meta);
    };
  }, []);

  React.useEffect(() => {
    let cancelled = false;
    if (!token) {
      setInvalid(true);
      setLoading(false);
      return;
    }
    setLoading(true);
    getPublicShare(token)
      .then((data) => {
        if (!cancelled) setShare(data);
      })
      .catch(() => {
        if (!cancelled) setInvalid(true);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [token]);

  const chatHref = isAuthenticated ? "/chat" : "/login";

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
          <Link to={chatHref}>继续提问 · Continue</Link>
        </Button>
      </header>

      {loading ? (
        <div className="flex flex-1 items-center justify-center text-sm text-[#999999]">
          加载中 · Loading…
        </div>
      ) : invalid || !share ? (
        <div className="flex flex-1 flex-col items-center justify-center gap-3 text-center">
          <p className="text-base font-medium text-[#1A1A1A]">分享链接无效或已撤销</p>
          <p className="text-sm text-[#999999]">This share link is invalid or has been revoked.</p>
          <Button asChild variant="outline" size="sm" className="mt-2">
            <Link to={chatHref}>继续提问 · Continue asking</Link>
          </Button>
        </div>
      ) : (
        <main className="flex-1 space-y-6">
          <div className="rounded-xl border border-[#E5E5E5] bg-[#FAFAFA] p-4 sm:p-5">
            <p className="whitespace-pre-wrap break-words text-sm leading-relaxed text-[#1A1A1A]">
              {share.question}
            </p>
          </div>

          <div className="text-sm leading-relaxed text-[#333333]">
            <MarkdownRenderer
              content={share.answerMd}
              messageId={token}
              sources={share.citations ?? undefined}
            />
          </div>

          {share.citations && share.citations.length > 0 ? (
            <section className="space-y-2">
              <h2 className="text-sm font-medium text-[#1A1A1A]">官方来源 · Official sources</h2>
              <ol className="space-y-1.5">
                {share.citations.map((source, index) => (
                  <li key={source.url ?? index} className="text-sm text-[#666666]">
                    <span className="mr-1.5 text-[#999999]">[{source.index ?? index + 1}]</span>
                    {source.url ? (
                      <a
                        href={source.url}
                        target="_blank"
                        rel="noreferrer nofollow"
                        className="text-[#2563EB] hover:underline"
                      >
                        {source.docName ?? source.url}
                      </a>
                    ) : (
                      <span>{source.docName}</span>
                    )}
                  </li>
                ))}
              </ol>
            </section>
          ) : null}

          <aside className="flex items-start gap-2 rounded-lg border border-[#FDE68A] bg-[#FEFCE8] p-3 text-xs leading-relaxed text-[#854D0E]">
            <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0" />
            <p>
              本页面为 AI 生成的单条问答快照，非官方服务，内容可能过期，请以 PolyU 官方页面为准。 ·
              This page shows an AI-generated snapshot of a single Q&amp;A. It is not an official
              PolyU service and may be outdated; please verify against official PolyU pages.
            </p>
          </aside>

          <div className="flex items-center justify-between text-xs text-[#999999]">
            <span>
              {share.createTime
                ? `分享于 · Shared at ${new Date(share.createTime).toLocaleString()}`
                : null}
            </span>
            <Button asChild size="sm">
              <Link to={chatHref}>继续提问 · Continue asking</Link>
            </Button>
          </div>
        </main>
      )}
      <SiteFooter className="mt-4" />
    </div>
  );
}

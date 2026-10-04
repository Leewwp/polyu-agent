import * as React from "react";
import { Link, useParams } from "react-router-dom";
import { ShieldAlert } from "lucide-react";

import { MarkdownRenderer } from "@/components/chat/MarkdownRenderer";
import { BrandMark } from "@/components/site/BrandMark";
import { Button } from "@/components/ui/button";
import { useEnterChat } from "@/hooks/useEnterChat";
import { useAuthStore } from "@/stores/authStore";
import { isSafeUrl } from "@/utils/urlSafety";
import { contentLangOf, useOptionalFeedLang } from "@/components/feed/feedLang";
import { usePageTitle } from "@/hooks/usePageTitle";

import { SiteFooter } from "@/components/layout/SiteFooter";
import type { PublicShare } from "@/services/shareService";
import { getPublicShare } from "@/services/shareService";

/**
 * 公开分享页（匿名可访问）：渲染不可变 Q&A 快照 + 结构化官方引用。
 * 首发要求搜索引擎不收录：meta robots noindex（后端另有 X-Robots-Tag 双保险）。
 * 始终显示非官方与时效提示。
 * #227：操作钮/加载/无效态随全局语言单语呈现；快照正文不翻译，lang 属性
 * 按实际内容语言标注（全局偏好≠快照内容语言）。
 * #231：标签用稳定页名「答案分享」（Q&A 正文标题不作页面名，加载/无效态同页名）。
 * #229：头部品牌位换 PolyUGuide（BrandMark）；「继续提问」复用游客直通
 * （useEnterChat fresh，口径与会话分享页对齐=开新会话，铸号失败降级登录）。
 */
export function SharePage() {
  const { token } = useParams<{ token: string }>();
  const { lang } = useOptionalFeedLang();
  const zh = lang === "zh";
  usePageTitle({ zh: "答案分享", en: "Shared answer" });
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

  return (
    <div className="mx-auto flex min-h-screen w-full max-w-3xl flex-col px-4 py-8 sm:py-12">
      <header className="mb-8 flex items-center justify-between">
        <BrandMark />
        <ContinueAskingButton variant="ghost" size="sm" className="text-[#666666]" />
      </header>

      {loading ? (
        <div className="flex flex-1 items-center justify-center text-sm text-[#999999]">
          {zh ? "加载中…" : "Loading…"}
        </div>
      ) : invalid || !share ? (
        <div className="flex flex-1 flex-col items-center justify-center gap-3 text-center">
          <p className="text-base font-medium text-[#1A1A1A]">
            {zh ? "分享链接无效或已撤销" : "This share link is invalid or has been revoked"}
          </p>
          <p className="text-sm text-[#999999]">
            {zh
              ? "This share link is invalid or has been revoked."
              : "分享链接无效或已撤销。"}
          </p>
          <ContinueAskingButton variant="outline" size="sm" className="mt-2" />
        </div>
      ) : (
        <main className="flex-1 space-y-6">
          <div
            lang={contentLangOf(share.question)}
            className="rounded-xl border border-[#E5E5E5] bg-[#FAFAFA] p-4 sm:p-5"
          >
            <p className="whitespace-pre-wrap break-words text-sm leading-relaxed text-[#1A1A1A]">
              {share.question}
            </p>
          </div>

          <div
            lang={contentLangOf(share.answerMd)}
            className="text-sm leading-relaxed text-[#333333]"
          >
            <MarkdownRenderer
              content={share.answerMd}
              messageId={token}
              sources={share.citations ?? undefined}
            />
          </div>

          {share.citations && share.citations.length > 0 ? (
            <section className="space-y-2">
              <h2 className="text-sm font-medium text-[#1A1A1A]">
                {zh ? "官方来源" : "Official sources"}
              </h2>
              <ol className="space-y-1.5">
                {share.citations.map((source, index) => (
                  <li key={source.url ?? index} className="text-sm text-[#666666]">
                    <span className="mr-1.5 text-[#999999]">[{source.index ?? index + 1}]</span>
                    {isSafeUrl(source.url) ? (
                      <a
                        href={source.url}
                        target="_blank"
                        rel="noreferrer nofollow"
                        className="text-[#2563EB] hover:underline"
                      >
                        {source.docName ?? source.url}
                      </a>
                    ) : (
                      <span>{source.docName ?? source.url}</span>
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
                ? zh
                  ? `分享于 ${new Date(share.createTime).toLocaleString()}`
                  : `Shared at ${new Date(share.createTime).toLocaleString()}`
                : null}
            </span>
            <ContinueAskingButton size="sm" />
          </div>
        </main>
      )}
      <SiteFooter className="mt-4" />
    </div>
  );
}

/**
 * 「继续提问」出口（#229）：匿名访客复用游客直通 useEnterChat（fresh=开新会话，
 * 口径与会话分享页对齐；铸号失败由 hook 降级 /login，业务提示走既有 toast）；
 * 已登录（含已铸游客）直连 /chat——不裸链受守卫路由给匿名访客落登录墙。
 */
function ContinueAskingButton({
  variant = "default",
  size = "sm",
  className = ""
}: {
  variant?: "ghost" | "outline" | "default";
  size?: "sm" | "default";
  className?: string;
}) {
  const { lang } = useOptionalFeedLang();
  const zh = lang === "zh";
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  const enterChat = useEnterChat({ fresh: true });
  const label = zh ? "继续提问" : "Continue asking";
  if (isAuthenticated) {
    return (
      <Button asChild variant={variant} size={size} className={className}>
        <Link to="/chat">{label}</Link>
      </Button>
    );
  }
  return (
    <Button variant={variant} size={size} className={className} onClick={enterChat}>
      {label}
    </Button>
  );
}

import { useEffect, useState } from "react";

import { AgentMarkdownRenderer } from "@/components/agent/AgentMarkdownRenderer";
import { FeedShell } from "@/components/feed/FeedShell";
import { useFeedLang } from "@/components/feed/feedLang";
import { FeedFooter } from "@/components/feed/FeedFooter";
import { FeedbackDialog } from "@/components/site/FeedbackDialog";
import { fetchSiteAbout, type SiteAboutContent } from "@/services/siteService";

/**
 * 关于页（doc 25；doc 32 批复改造）：FeedShell 壳内右侧打开（交互对齐热点榜/
 * 主题——侧边栏保持不动，不整页跳转），内容随全局语言切换（en 无配置回落中文）。
 * - 接口 404/空内容 → 「内容暂未配置」空态（flag 关/未配置两态同面）；
 * - 赞赏区渲染契约：两码 URL 皆空则含标题整块不渲染（避免「有咖啡没有码」）。
 */
export function AboutPage() {
  return (
    <FeedShell title={{ zh: "关于本站", en: "About" }}>
      <AboutBody />
      <FeedFooter compact />
    </FeedShell>
  );
}

/** 壳内子组件才可用 useFeedLang（FeedPage 顶层判例） */
function AboutBody() {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  const [about, setAbout] = useState<SiteAboutContent | null>(null);
  const [failed, setFailed] = useState(false);
  const [feedbackOpen, setFeedbackOpen] = useState(false);

  useEffect(() => {
    let active = true;
    fetchSiteAbout()
      .then((data) => {
        if (active) setAbout(data);
      })
      .catch(() => {
        if (active) setFailed(true);
      });
    return () => {
      active = false;
    };
  }, []);

  // 英文档回落中文内容（content_en 未配置时不空屏）
  const content = zh
    ? about?.content?.trim()
    : about?.contentEn?.trim() || about?.content?.trim();
  const qrMain = about?.qrImageUrl?.trim() || null;
  const qrAlt = about?.qrImageUrlAlt?.trim() || null;
  const showDonate = Boolean(qrMain || qrAlt);

  return (
    <div className="rounded-2xl border border-[var(--feed-line)] bg-[var(--feed-card)] p-6 sm:p-8">
      {failed || !content ? (
        <p className="text-[13px] text-[var(--feed-text-tertiary)]">
          {zh ? "内容暂未配置" : "Content not available yet."}
        </p>
      ) : (
        <div className="legal-markdown text-[14px] leading-relaxed">
          <AgentMarkdownRenderer content={content} />
        </div>
      )}

      {showDonate ? (
        <section className="mt-8 border-t border-[var(--feed-line-soft)] pt-6">
          <h2 className="mb-1 text-base font-semibold">
            {zh ? "请作者喝杯咖啡" : "Buy the author a coffee"}
          </h2>
          <p className="mb-3 text-[13px] text-[var(--feed-text-tertiary)]">
            {zh
              ? "站点服务器与 API 均有成本，如果内容帮到了你，欢迎扫码支持"
              : "The server and API costs are real — if this site helped you, consider a small tip."}
          </p>
          <div className="flex flex-wrap gap-4">
            {qrMain ? (
              <figure className="text-center">
                <img
                  src={qrMain}
                  alt={zh ? "赞赏二维码" : "WeChat QR"}
                  className="h-40 w-40 rounded-lg border border-[var(--feed-line)] object-contain"
                />
                <figcaption className="mt-1 text-xs text-[var(--feed-text-tertiary)]">微信 / WeChat</figcaption>
              </figure>
            ) : null}
            {qrAlt ? (
              <figure className="text-center">
                <img
                  src={qrAlt}
                  alt={zh ? "赞赏二维码（二）" : "Alipay QR"}
                  className="h-40 w-40 rounded-lg border border-[var(--feed-line)] object-contain"
                />
                <figcaption className="mt-1 text-xs text-[var(--feed-text-tertiary)]">支付宝 / Alipay</figcaption>
              </figure>
            ) : null}
          </div>
        </section>
      ) : null}

      <p className="mt-6 text-[13px] text-[var(--feed-text-tertiary)]">
        {about === null && !failed ? null : (
          <>
            {zh ? "有想法或发现问题？" : "Got ideas or spotted an issue?"}{" "}
            <button
              type="button"
              className="underline decoration-dotted hover:text-[var(--feed-text-primary)]"
              onClick={() => setFeedbackOpen(true)}
            >
              {zh ? "反馈" : "Feedback"}
            </button>
          </>
        )}
      </p>
      <FeedbackDialog open={feedbackOpen} onClose={() => setFeedbackOpen(false)} />
    </div>
  );
}

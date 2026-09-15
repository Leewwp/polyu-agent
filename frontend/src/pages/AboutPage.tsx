import { useEffect, useState } from "react";

import { AgentMarkdownRenderer } from "@/components/agent/AgentMarkdownRenderer";
import { FeedbackDialog } from "@/components/site/FeedbackDialog";
import { LegalShell } from "@/components/legal/LegalShell";
import { fetchSiteAbout, type SiteAboutContent } from "@/services/siteService";

/**
 * 关于页（doc 25）：markdown 内容由后台编辑，公开只读。
 * - 接口 404/空内容 → 「内容暂未配置」空态（flag 关/未配置两态同面）；
 * - 赞赏区渲染契约：两码 URL 皆空则含标题整块不渲染（避免「有咖啡没有码」）。
 */
export function AboutPage() {
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

  const content = about?.content?.trim();
  const qrMain = about?.qrImageUrl?.trim() || null;
  const qrAlt = about?.qrImageUrlAlt?.trim() || null;
  const showDonate = Boolean(qrMain || qrAlt);

  return (
    <LegalShell title="关于本站" titleEn="About">
      {failed || !content ? (
        <p className="text-muted-foreground">
          {failed
            ? "内容暂未配置。 · Content not available yet."
            : "内容暂未配置 · Content not available yet."}
        </p>
      ) : (
        <div className="legal-markdown">
          <AgentMarkdownRenderer content={content} />
        </div>
      )}

      {showDonate ? (
        <section>
          <h2 className="mb-1 text-base font-semibold">请作者喝杯咖啡</h2>
          <p className="mb-3 text-muted-foreground">
            站点服务器与 API 均有成本，如果内容帮到了你，欢迎扫码支持 · The server and API
            costs are real — if this site helped you, consider buying a coffee.
          </p>
          <div className="flex flex-wrap gap-4">
            {qrMain ? (
              <figure className="text-center">
                <img
                  src={qrMain}
                  alt="赞赏二维码"
                  className="h-40 w-40 rounded-lg border border-[#EAEAEA] object-contain"
                />
                <figcaption className="mt-1 text-xs text-muted-foreground">微信 / WeChat</figcaption>
              </figure>
            ) : null}
            {qrAlt ? (
              <figure className="text-center">
                <img
                  src={qrAlt}
                  alt="赞赏二维码（二）"
                  className="h-40 w-40 rounded-lg border border-[#EAEAEA] object-contain"
                />
                <figcaption className="mt-1 text-xs text-muted-foreground">支付宝 / Alipay</figcaption>
              </figure>
            ) : null}
          </div>
        </section>
      ) : null}

      <p className="text-sm text-muted-foreground">
        {about === null && !failed ? null : (
          <>
            {"有想法或发现问题？"}
            <button
              type="button"
              className="underline decoration-dotted hover:text-foreground"
              onClick={() => setFeedbackOpen(true)}
            >
              反馈 · Feedback
            </button>
          </>
        )}
      </p>
      <FeedbackDialog open={feedbackOpen} onClose={() => setFeedbackOpen(false)} />
    </LegalShell>
  );
}

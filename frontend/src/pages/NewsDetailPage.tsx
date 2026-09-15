import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { toast } from "sonner";

import { FeedFooter } from "@/components/feed/FeedFooter";
import { FeedShell } from "@/components/feed/FeedShell";
import { useFeedLang } from "@/components/feed/feedLang";
import type { NewsItem } from "@/types/news";
import { NEWS_CATEGORY_LABELS_EN, NEWS_CATEGORY_LABELS_ZH, NEWS_TOPICS } from "@/services/newsMockData";
import { fetchNewsDetail } from "@/services/newsService";

/**
 * 公开资讯详情页（仅 AI 摘要档——后端无正文列，原文全文
 * 另立处理；本页不放原文全文）：
 * - 形态=FeedShell 壳（feed 域同款）：返回链 + 标题/信源/分类/发布时间/热度/主题标签
 *   + AI 导读（summary 跟随全局语言 pill）+ 首尾两处「查看原文 ↗」；
 * - 轻量分享：navigator.share 优先（移动端拉系统面板），降级复制链接+toast；
 * - 不存在/已下架/flag 关（404）同一 not-found 态；裸路由无守卫（FeedPage 范式），
 *   不 import engineStore（公开页红线）。
 */
export function NewsDetailPage() {
  const { id = "" } = useParams<{ id: string }>();
  const [item, setItem] = useState<NewsItem | null>(null);
  const [missing, setMissing] = useState(false);

  useEffect(() => {
    let alive = true;
    setItem(null);
    setMissing(false);
    fetchNewsDetail(id)
      .then((data) => alive && setItem(data))
      .catch(() => alive && setMissing(true));
    return () => {
      alive = false;
    };
  }, [id]);

  return (
    <FeedShell title={{ zh: "资讯详情", en: "News" }}>
      <NewsDetailBody item={item} missing={missing} />
    </FeedShell>
  );
}

function NewsDetailBody({ item, missing }: { item: NewsItem | null; missing: boolean }) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";

  // 不存在/已下架/功能未部署（flag 关 404）同形文案：不向匿名访问者区分存在性
  if (!item) {
    return missing ? (
      <>
        <BackLink zh={zh} />
        <div className="mt-3 rounded-2xl border border-dashed border-[var(--feed-line)] bg-[var(--feed-card)] p-7 text-center text-[13px] text-[var(--feed-text-tertiary)]">
          {zh ? "该资讯不存在或已下架" : "This news item does not exist or has been removed"}
        </div>
      </>
    ) : null;
  }

  return (
    <>
      <BackLink zh={zh} />

      <article className="rounded-2xl border border-[var(--feed-line)] bg-[var(--feed-card)] p-5 shadow-sm md:px-7 md:py-6">
        {/* 顶部动作行：顶部「查看原文 ↗」+分享钮，右挂与顶栏既有按钮同一节奏；尾部再置一处大钮 */}
        <div className="mb-1 flex flex-wrap items-center justify-end gap-2">
          <ShareButton item={item} zh={zh} compact />
          <a
            className="inline-flex items-center gap-1 text-[12.5px] font-semibold text-[var(--polyu-red)] hover:underline"
            href={item.url}
            target="_blank"
            rel="noopener noreferrer"
          >
            {zh ? "查看原文 ↗" : "Source ↗"}
          </a>
        </div>
        <div className="mb-1.5 flex flex-wrap items-center gap-2.5">
          <span className="inline-flex items-center gap-1.5 rounded-full bg-[var(--feed-bg)] px-2.5 py-0.5 text-[11.5px] font-medium text-[var(--feed-text-secondary)]">
            <i className="h-[7px] w-[7px] rounded-full" style={{ backgroundColor: item.source.color }} />
            {zh ? item.source.labelZh : item.source.labelEn}
          </span>
          <span className="rounded-full bg-[var(--polyu-red-50)] px-2.5 py-0.5 text-[11px] font-semibold text-[var(--polyu-red-dark)]">
            {zh ? NEWS_CATEGORY_LABELS_ZH[item.category] : NEWS_CATEGORY_LABELS_EN[item.category]}
          </span>
          <span className="text-xs tabular-nums text-[var(--feed-text-tertiary)]">
            {item.publishDate} {item.publishTime} HKT
          </span>
          {item.heat > 0 && (
            <span className="rounded-full bg-[var(--feed-heat-bg)] px-2.5 py-0.5 text-[11.5px] font-bold text-[var(--feed-heat)]">
              🔥 {item.heat}
            </span>
          )}
        </div>

        <h1 className="mb-1 text-[19px] font-extrabold leading-[1.45] md:text-[21px]">
          {zh ? item.titleZh : item.titleEn}
        </h1>

        {item.topics.length > 0 && (
          <div className="mb-1 flex flex-wrap items-center gap-2">
            {item.topics.map((slug) => {
              const topic = NEWS_TOPICS.find((candidate) => candidate.slug === slug);
              if (!topic) {
                return null;
              }
              return (
                <Link
                  key={slug}
                  to={`/topics/${slug}`}
                  className="rounded-full border border-[var(--feed-line)] px-2.5 py-0.5 text-[11.5px] text-[var(--feed-text-secondary)] transition-colors hover:border-[var(--polyu-red)] hover:text-[var(--polyu-red)]"
                >
                  # {zh ? topic.nameZh : topic.nameEn}
                </Link>
              );
            })}
          </div>
        )}

        <div className="mt-3 mb-3 border-t border-dashed border-[var(--feed-line-soft)] pt-3.5">
          <div className="mb-1.5 flex items-baseline gap-2.5">
            <h2 className="text-[15px] font-bold">{zh ? "AI 导读" : "AI summary"}</h2>
            <span className="text-xs text-[var(--feed-text-tertiary)]">
              {zh ? "摘要由 AI 生成，以原文为准" : "AI-generated summary — always check the source"}
            </span>
          </div>
          <p className="whitespace-pre-line text-[14px] leading-[1.75] text-[var(--feed-text-secondary)]">
            {zh ? item.summaryZh : item.summaryEn}
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2.5 border-t border-dashed border-[var(--feed-line-soft)] pt-3.5">
          <a
            className="inline-flex items-center gap-1 rounded-full border border-[var(--polyu-red)] bg-white px-[15px] py-1.5 text-[13px] font-semibold text-[var(--polyu-red)] transition-colors hover:bg-[var(--polyu-red-50)]"
            href={item.url}
            target="_blank"
            rel="noopener noreferrer"
          >
            {zh ? "查看原文 ↗" : "View source ↗"}
          </a>
          <ShareButton item={item} zh={zh} />
        </div>
      </article>

      <FeedFooter compact />
    </>
  );
}

function BackLink({ zh }: { zh: boolean }) {
  return (
    <Link to="/" className="mb-2.5 inline-block text-[13px] text-[var(--feed-text-tertiary)] hover:text-[var(--polyu-red)]">
      {zh ? "‹ 返回资讯流" : "‹ Back to feed"}
    </Link>
  );
}

/**
 * 轻量分享：navigator.share（移动端系统面板）优先，不支持时降级
 * 复制链接+toast；分享 URL=本详情页直链。用户主动取消 share 不打扰。
 * compact=顶部动作行小钮档；尾部主钮走默认档。
 */
function ShareButton({ item, zh, compact = false }: { item: NewsItem; zh: boolean; compact?: boolean }) {
  const onShare = async () => {
    const shareUrl = `${window.location.origin}/news/${item.id}`;
    const title = zh ? item.titleZh : item.titleEn;
    if (typeof navigator.share === "function") {
      try {
        await navigator.share({ title, url: shareUrl });
      } catch {
        // AbortError=用户取消分享面板，静默；其余异常也不再打扰
      }
      return;
    }
    try {
      await navigator.clipboard.writeText(shareUrl);
      toast(zh ? "链接已复制" : "Link copied");
    } catch {
      toast(zh ? "复制失败，请手动复制地址栏链接" : "Copy failed — please copy from the address bar");
    }
  };
  return (
    <button
      type="button"
      className={
        compact
          ? "inline-flex items-center gap-1 text-[12.5px] font-semibold text-[var(--feed-text-secondary)] transition-colors hover:text-[var(--polyu-red)]"
          : "inline-flex items-center gap-1 rounded-full border border-[var(--feed-line)] px-[15px] py-1.5 text-[13px] font-semibold text-[var(--feed-text-secondary)] transition-colors hover:border-[var(--polyu-red)] hover:text-[var(--polyu-red)]"
      }
      onClick={onShare}
    >
      {zh ? "分享" : "Share"}
    </button>
  );
}

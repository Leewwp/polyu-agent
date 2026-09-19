import { Link } from "react-router-dom";

import type { NewsItem } from "@/types/news";
import { NEWS_CATEGORY_LABELS_EN, NEWS_CATEGORY_LABELS_ZH } from "@/services/newsMockData";
import { useFeedLang } from "./feedLang";
import { isSafeUrl } from "@/utils/urlSafety";

/**
 * 单张资讯卡（原型 .card 结构）：
 * - 主体（元信息+标题+摘要）整块可点 → /news/:id 详情页
 *   （原型「卡片不进详情页」的旧设定随之作废）；
 * - 「查看原文 ↗」保持外链新开标签（永久原文外链语义不变）；
 * - 语言=全局唯一开关（卡片级「中 / EN」小钮已移除，
 *   入口收归 feed/hot/topics/detail 五页共用的顶栏 LangPill）；
 * - 信源徽章五色圆点 + 分类章 + 故事线聚簇章。
 */
export function NewsCard({ item }: { item: NewsItem }) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";

  return (
    <article className="mb-3 rounded-2xl border border-[var(--feed-line)] bg-[var(--feed-card)] p-4 shadow-sm transition-colors hover:border-[#D8B7BC] md:px-[18px]">
      <Link to={`/news/${item.id}`} className="block">
        <div className="mb-1.5 flex flex-wrap items-center gap-2.5">
          <span className="text-xs tabular-nums text-[var(--feed-text-tertiary)]">{item.publishTime}</span>
          <span className="inline-flex items-center gap-1.5 rounded-full bg-[var(--feed-bg)] px-2.5 py-0.5 text-[11.5px] font-medium text-[var(--feed-text-secondary)]">
            <i className="h-[7px] w-[7px] rounded-full" style={{ backgroundColor: item.source.color }} />
            {zh ? item.source.labelZh : item.source.labelEn}
          </span>
          <span className="rounded-full bg-[var(--polyu-red-50)] px-2.5 py-0.5 text-[11px] font-semibold text-[var(--polyu-red-dark)]">
            {zh ? NEWS_CATEGORY_LABELS_ZH[item.category] : NEWS_CATEGORY_LABELS_EN[item.category]}
          </span>
          {item.clusterSourceCount !== undefined && (
            <span className="rounded-full bg-[var(--feed-heat-bg)] px-2.5 py-0.5 text-[11px] font-semibold text-[var(--feed-cluster)]">
              {zh ? `热点 · 另有 ${item.clusterSourceCount} 个来源` : `Hot · ${item.clusterSourceCount} more sources`}
            </span>
          )}
        </div>
        <h4 className="mb-1 text-[15.5px] font-semibold leading-[1.45]">{zh ? item.titleZh : item.titleEn}</h4>
        <p className="line-clamp-3 text-[13px] leading-[1.65] text-[var(--feed-text-secondary)]">
          {zh ? item.summaryZh : item.summaryEn}
        </p>
      </Link>
      <div className="mt-2 flex items-center gap-2.5">
        {isSafeUrl(item.url) ? (
          <a
            className="ml-auto inline-flex items-center gap-1 text-[12.5px] font-semibold text-[var(--polyu-red)] hover:underline"
            href={item.url}
            target="_blank"
            rel="noopener noreferrer"
          >
            {zh ? "查看原文 ↗" : "Source ↗"}
          </a>
        ) : null}
      </div>
    </article>
  );
}

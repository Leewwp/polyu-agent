import { Link } from "react-router-dom";

import type { HotRankEntry } from "@/types/news";
import { useFeedLang } from "./feedLang";
import { RankRow } from "./RankRow";

/**
 * 首页热点卡（原型 .hot-card）：今日热点 Top5，前 3 名红色名次徽章。
 * 2026-09-12 修复：行点击入 /news/:id 详情（共享 RankRow；
 * 原型遗留的「榜单行详情随真数据接线定夺」就此落地），mock fixture 无 itemId 保持纯文本。
 */
export function HotPanel({ entries }: { entries: HotRankEntry[] }) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";

  if (entries.length === 0) {
    return null;
  }

  return (
    <section className="mb-4 rounded-2xl border border-[var(--feed-line)] bg-[var(--feed-card)] p-4 pb-2.5 shadow-sm md:px-[18px]">
      <div className="mb-1.5 flex items-baseline justify-between">
        <h2 className="text-[15px] font-bold">{zh ? "今日热点" : "Trending today"}</h2>
        {/* 已接线：热点榜页 /hot（原型「查看全部 ›」） */}
        <Link
          to="/hot"
          className="text-[12px] font-semibold text-[var(--feed-text-tertiary)] transition-colors hover:text-[var(--polyu-red)]"
        >
          {zh ? "查看全部 ›" : "View all ›"}
        </Link>
      </div>
      <ol>
        {entries.map((entry, index) => (
          <RankRow
            key={`${entry.itemId ?? entry.titleZh}`}
            entry={entry}
            className="flex items-center gap-3 border-b border-dashed border-[var(--feed-line-soft)] py-2 last:border-b-0"
            title={zh ? entry.titleZh : entry.titleEn}
          >
            <span
              className={
                index < 3
                  ? "flex h-5 w-5 flex-none items-center justify-center rounded-md bg-[var(--polyu-red)] text-xs font-bold text-white"
                  : "flex h-5 w-5 flex-none items-center justify-center rounded-md bg-[var(--feed-bg)] text-xs font-bold text-[var(--feed-text-tertiary)]"
              }
            >
              {index + 1}
            </span>
            <span className="min-w-0 flex-1 truncate text-[13.5px]">{zh ? entry.titleZh : entry.titleEn}</span>
            <span className="flex-none rounded-full bg-[var(--feed-heat-bg)] px-2.5 py-0.5 text-[11.5px] font-bold text-[var(--feed-heat)]">
              🔥 {entry.heat}
            </span>
          </RankRow>
        ))}
      </ol>
    </section>
  );
}

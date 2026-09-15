import { useEffect, useState } from "react";

import { FeedFooter } from "@/components/feed/FeedFooter";
import { RankRow } from "@/components/feed/RankRow";
import { FeedShell } from "@/components/feed/FeedShell";
import { SourceCountBadge } from "@/components/feed/SourceListPopover";
import { useFeedLang } from "@/components/feed/feedLang";
import { formatUpdatedLabel } from "@/services/newsMapping";
import { HOT_TAG_LABELS } from "@/services/newsMockData";
import { fetchHotRank } from "@/services/newsService";
import type { HotRankEntry } from "@/types/news";

/**
 * 公开热点榜页（原型 #viewHot）：
 * - Top10 排行（前 3 红名次徽章）+ 爆/新/发酵中标签 + 来源数徽章（点击/悬停弹
 *   信源名单 SourceListPopover）+ 🔥热度右对齐；
 * - 「榜单说明」两段方法论注脚逐字照原型——**注脚描述必须与后端实际算法一致**
 *   ——热度=Σ信源权重+覆盖信源数、24h 半衰、故事线合并显示来源数、
 *   氛围票 V2——与 rag.news 热度模型一致；降级开关关闭时该文案须
 *   同步改（静态文案，已知限制记后续待办）；
 * - 公开数据走 newsService，不 import engineStore、匿名零 /auth 请求（公开页红线）。
 */

/** 页头+榜单说明（原型 .topics-head+.rank-note）：useFeedLang 须在 FeedShell 内调用 */
function HotRankHead({ entries }: { entries: HotRankEntry[] }) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  // 「更新至」改实时值——HotRankEntryVO 无数据时点字段，此处以视图加载
  // 时刻呈现榜单时点（热度随 24h 半衰连续衰减，「榜单时点」语义成立）
  const updatedLabel = formatUpdatedLabel(new Date(), lang);
  return (
    <>
      <div className="mb-[18px]">
        {/* 双标题收敛——页级标题由 FeedShell 顶栏承载，此处只留副标题行
            （数据时点/排行说明为全页唯一，顶栏日期是全站通用日期） */}
        <p className="text-[12.5px] text-[var(--feed-text-tertiary)]">
          {zh
            ? `今日理大资讯热度排行 · ${updatedLabel}`
            : `Today’s trending PolyU news · ${updatedLabel}`}
        </p>
      </div>

      {entries.length > 0 && (
        <ol className="mb-4">
          {entries.map((entry, index) => {
            const rank = index + 1;
            return (
              <RankRow
                key={`${entry.itemId ?? entry.titleZh}-${rank}`}
                entry={entry}
                className="flex items-start gap-3 border-b border-dashed border-[var(--feed-line-soft)] py-2.5 last:border-b-0"
                title={zh ? entry.titleZh : entry.titleEn}
              >
                <span
                  className={
                    rank <= 3
                      ? "flex h-6 w-6 flex-none items-center justify-center rounded-lg bg-[var(--polyu-red)] text-[13px] font-bold text-white"
                      : "flex h-6 w-6 flex-none items-center justify-center rounded-lg bg-[var(--feed-bg)] text-[13px] font-bold text-[var(--feed-text-tertiary)]"
                  }
                >
                  {rank}
                </span>
                <div className="min-w-0 flex-1">
                  <span className="block text-[13.5px] font-semibold leading-snug">
                    {zh ? entry.titleZh : entry.titleEn}
                  </span>
                  <div className="mt-1 flex flex-wrap items-center gap-1.5">
                    {entry.tags.map((tag) => (
                      <span
                        key={tag}
                        className={
                          tag === "boom"
                            ? "rounded px-1.5 py-px text-[10.5px] font-bold text-white bg-[var(--polyu-red)]"
                            : tag === "fresh"
                              ? "rounded bg-[#E8F4EC] px-1.5 py-px text-[10.5px] font-bold text-[#18794E]"
                              : "rounded bg-[#FDF3E7] px-1.5 py-px text-[10.5px] font-bold text-[#B45309]"
                        }
                      >
                        {zh ? HOT_TAG_LABELS[tag].zh : HOT_TAG_LABELS[tag].en}
                      </span>
                    ))}
                    <SourceCountBadge sources={entry.sources} />
                  </div>
                </div>
                <span className="flex-none pt-0.5 text-[13px] font-bold text-[var(--feed-heat)]">
                  🔥 {entry.heat}
                </span>
              </RankRow>
            );
          })}
        </ol>
      )}

      <div className="mb-4 rounded-2xl border border-dashed border-[var(--feed-line)] bg-[var(--feed-card)] px-4 py-3 text-[12px] leading-[1.75] text-[var(--feed-text-secondary)]">
        <b className="mb-0.5 block text-[var(--feed-text-primary)]">{zh ? "榜单说明" : "How the score works"}</b>
        <p>
          {zh
            ? "榜单热度 = 信源权重（官方主站高、补充源低）+ 覆盖信源数，并按 24 小时半衰期随时间衰减；同一故事线的关联报道在榜单中合并为一条并显示来源数；用户氛围票权重将随互动功能上线后并入。"
            : "Heat = source weight + number of covering sources, decaying with a 24-hour half-life; related coverage is merged into one entry showing its source count; community votes join the formula once interactions ship."}
        </p>
        <p>
          {zh
            ? "标签含义：爆 = 短时间密集报道 · 新 = 首报 6 小时内 · 发酵中 = 信源仍在增加。悬停或点击来源数字可查看信源名单。"
            : "Tags: BOOM = dense coverage in a short window · NEW = first reported within 6 hours · RISING = sources still growing. Click the source number to see the list."}
        </p>
      </div>
    </>
  );
}

export function HotRankPage() {
  const [entries, setEntries] = useState<HotRankEntry[] | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let alive = true;
    fetchHotRank()
      .then((data) => alive && setEntries(data))
      .catch(() => alive && setFailed(true));
    return () => {
      alive = false;
    };
  }, []);

  return (
    <FeedShell title={{ zh: "热点榜", en: "Trending" }}>
      {failed ? (
        <div className="rounded-2xl border border-dashed border-[var(--feed-line)] bg-[var(--feed-card)] p-7 text-center text-[13px] text-[var(--feed-text-tertiary)]">
          热点榜加载失败，请稍后刷新重试
        </div>
      ) : (
        <HotRankHead entries={entries ?? []} />
      )}
      <FeedFooter compact />
    </FeedShell>
  );
}

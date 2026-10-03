import { useEffect, useState } from "react";

import { FeedFooter } from "@/components/feed/FeedFooter";
import { FeedShell } from "@/components/feed/FeedShell";
import { NewsCard } from "@/components/feed/NewsCard";
import { useFeedLang } from "@/components/feed/feedLang";
import { digestItemToNewsItem } from "@/services/newsMapping";
import { dailyDigestRssUrl, fetchDailyDigest, fetchDailyDigestList } from "@/services/newsService";
import type { NewsDailyDigest, NewsDailyDigestSummary } from "@/types/news";
import { cn } from "@/lib/utils";

/**
 * 公开日报页（#212，/daily；FeedPage 范式：裸路由无守卫、匿名直读
 * /public/news/daily、零引擎探测）。读取面零 LLM——后端只读快照表
 * （结构保证），导语在生成期一次性产出；页面渲染不触发任何模型调用。
 *
 * 数据形态：目录（近 N 期，日期倒序）→ 选中日期详情（刊头+生效导语+
 * 读取期下架复检后的可见条目）。有失格条目时后端已把导语回退为模板
 * （introDegraded=true），本页加「部分内容已下架」注记——透明口径。
 * RSS：/public/news/daily/{date}/rss 外链订阅（RSS 2.0 原文 feed）。
 */

/** 目录日期 chips 展示上限（避免长尾日期挤爆头部；目录接口本身 30 期） */
const DATE_CHIPS_MAX = 14;

function DigestHead({ digest }: { digest: NewsDailyDigest }) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  return (
    <div className="mb-4 rounded-2xl border border-[var(--feed-line)] bg-[var(--feed-card)] px-4 py-3.5">
      <div className="mb-1.5 flex flex-wrap items-center gap-2">
        <b className="text-[15px] font-bold text-[var(--feed-text-primary)]">
          {zh ? `理大资讯日报 · ${digest.digestDate}` : `PolyU Daily Digest · ${digest.digestDate}`}
        </b>
        <span className="rounded-full bg-[var(--feed-bg)] px-2.5 py-0.5 text-[11px] font-medium text-[var(--feed-text-secondary)]">
          {zh ? `${digest.visibleCount} 条动态` : `${digest.visibleCount} updates`}
        </span>
        {(digest.storedIntroSource === "fallback" || digest.storedIntroSource === "empty") && (
          <span className="rounded-full border border-[var(--feed-line)] px-2 py-0.5 text-[10.5px] font-semibold text-[var(--feed-text-tertiary)]">
            {zh ? "模板导语" : "Template intro"}
          </span>
        )}
        {digest.introDegraded && (
          <span className="rounded-full bg-[#FDF3E7] px-2 py-0.5 text-[10.5px] font-semibold text-[#B45309]">
            {zh ? "部分内容已下架" : "Some items removed"}
          </span>
        )}
      </div>
      <p className="text-[12.5px] text-[var(--feed-text-tertiary)]">
        {zh
          ? "覆盖窗口：发布时间介于前一日 08:00 与本日 08:00（HKT）之间的公开动态，按发布时间倒序排列。"
          : "Window: public updates published between 08:00 HKT the previous day and 08:00 HKT today, newest first."}
      </p>
      {(zh ? digest.introZh : digest.introEn) && (
        <p className="mt-2 text-[13.5px] leading-[1.7] text-[var(--feed-text-secondary)]">
          {zh ? digest.introZh : digest.introEn}
        </p>
      )}
    </div>
  );
}

export function DailyDigestPage() {
  const [summaries, setSummaries] = useState<NewsDailyDigestSummary[] | null>(null);
  const [selectedDate, setSelectedDate] = useState<string | null>(null);
  const [digest, setDigest] = useState<NewsDailyDigest | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let alive = true;
    fetchDailyDigestList()
      .then((list) => {
        if (!alive) {
          return;
        }
        setSummaries(list);
        if (list.length > 0) {
          setSelectedDate(list[0].digestDate);
        }
      })
      .catch(() => alive && setFailed(true));
    return () => {
      alive = false;
    };
  }, []);

  useEffect(() => {
    if (!selectedDate) {
      return;
    }
    let alive = true;
    setDigest(null);
    fetchDailyDigest(selectedDate)
      .then((detail) => alive && setDigest(detail))
      .catch(() => alive && setFailed(true));
    return () => {
      alive = false;
    };
  }, [selectedDate]);

  return (
    <FeedShell title={{ zh: "日报", en: "Daily digest" }}>
      {failed ? (
        <div className="rounded-2xl border border-dashed border-[var(--feed-line)] bg-[var(--feed-card)] p-7 text-center text-[13px] text-[var(--feed-text-tertiary)]">
          日报加载失败，请稍后刷新重试
        </div>
      ) : (
        <>
          {summaries && summaries.length > 0 && (
            <div className="mb-4 flex flex-wrap items-center gap-1.5">
              {summaries.slice(0, DATE_CHIPS_MAX).map((summary) => (
                <button
                  key={summary.digestDate}
                  type="button"
                  aria-pressed={summary.digestDate === selectedDate}
                  onClick={() => setSelectedDate(summary.digestDate)}
                  className={cn(
                    "rounded-full border px-3 py-1 text-[12px] font-semibold tabular-nums transition-colors",
                    summary.digestDate === selectedDate
                      ? "border-[var(--polyu-red)] bg-[var(--polyu-red)] text-white"
                      : "border-[var(--feed-line)] bg-[var(--feed-card)] text-[var(--feed-text-secondary)] hover:border-[#D8B7BC]"
                  )}
                >
                  {summary.digestDate.slice(5)}
                </button>
              ))}
              <a
                className="ml-auto inline-flex items-center gap-1 text-[12px] font-semibold text-[var(--polyu-red)] hover:underline"
                href={selectedDate ? dailyDigestRssUrl(selectedDate) : "#"}
                target="_blank"
                rel="noreferrer"
              >
                RSS ↗
              </a>
            </div>
          )}

          {digest ? (
            <>
              <DigestHead digest={digest} />
              {digest.items.length > 0 ? (
                digest.items.map((item) => (
                  <NewsCard key={`${item.itemId}-${item.seq}`} item={digestItemToNewsItem(item)} />
                ))
              ) : (
                <div className="rounded-2xl border border-dashed border-[var(--feed-line)] bg-[var(--feed-card)] p-7 text-center text-[13px] text-[var(--feed-text-tertiary)]">
                  本期窗口内暂无公开动态
                </div>
              )}
            </>
          ) : (
            summaries !== null &&
            summaries.length === 0 && (
              <div className="rounded-2xl border border-dashed border-[var(--feed-line)] bg-[var(--feed-card)] p-7 text-center text-[13px] text-[var(--feed-text-tertiary)]">
                日报尚未生成（每日 08:40 HKT 出刊）
              </div>
            )
          )}
        </>
      )}
      <FeedFooter compact />
    </FeedShell>
  );
}

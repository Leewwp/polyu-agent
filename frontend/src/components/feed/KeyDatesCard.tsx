import { useEffect, useState } from "react";
import { Link } from "react-router-dom";

import { useFeedLang } from "./feedLang";
import type { KeyDateBoard } from "@/types/keyDate";
import { KEY_DATE_CARD_LIMIT, countdownBadge, fetchKeyDateBoard, keyDateLabel } from "@/services/keyDateService";

/**
 * 首页关键日期卡（#193；HotPanel 同形态）：临近度序前 K 条（进行中/今日/即将，
 * 后端排好序——currentAndUpcoming.slice(0,K) 即同一序），倒计时徽章=后端
 * daysUntil 直读（exact-day/exact-range 才有；onwards 不倒计时只列日期）。
 *
 * - 抓取失败/无近期事件：整卡隐藏（首页不为辅助卡渲染错误态）；
 * - 源异常（退化/隔离）时不冒充最新：头部下加「数据截至 …」小字（三态口径）；
 * - 「查看全部 ›」进 /key-dates 独立页。
 */
export function KeyDatesCard() {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  const [board, setBoard] = useState<KeyDateBoard | null>(null);

  useEffect(() => {
    let alive = true;
    fetchKeyDateBoard()
      .then((data) => alive && setBoard(data))
      .catch(() => null); // 静默：flag 关/网络失败=不渲染（辅助卡不占错误面）
    return () => {
      alive = false;
    };
  }, []);

  if (!board || board.currentAndUpcoming.length === 0) {
    return null;
  }
  const rows = board.currentAndUpcoming.slice(0, KEY_DATE_CARD_LIMIT);

  return (
    <section className="mb-4 rounded-2xl border border-[var(--feed-line)] bg-[var(--feed-card)] p-4 pb-2.5 shadow-sm md:px-[18px]">
      <div className="mb-1.5 flex items-baseline justify-between">
        <h2 className="text-[15px] font-bold">{zh ? "关键日期" : "Key dates"}</h2>
        <Link
          to="/key-dates"
          className="text-[12px] font-semibold text-[var(--feed-text-tertiary)] transition-colors hover:text-[var(--polyu-red)]"
        >
          {zh ? "查看全部 ›" : "View all ›"}
        </Link>
      </div>
      {board.anySourceAbnormal && (
        <p className="mb-1 px-0.5 text-[11.5px] text-[var(--feed-text-tertiary)]">
          {zh
            ? `部分来源同步异常，数据截至 ${formatAsOf(board.lastFullSyncAt, zh)}——最新以 eStudent 及校务邮件为准`
            : `Some sources degraded, data as of ${formatAsOf(board.lastFullSyncAt, zh)} — always refer to eStudent and official email`}
        </p>
      )}
      <ul>
        {rows.map((item) => {
          const badge = countdownBadge(item, lang);
          return (
            <li
              key={item.uid}
              className="flex items-center gap-3 border-b border-dashed border-[var(--feed-line-soft)] py-2 last:border-b-0"
            >
              <span className="min-w-0 flex-1 truncate text-[13.5px]">
                {(zh ? item.titleZh : item.titleEn) ?? item.titleEn}
              </span>
              <span className="flex-none text-[12px] text-[var(--feed-text-tertiary)]">{keyDateLabel(item, lang)}</span>
              {badge && (
                <span className="flex-none rounded-full bg-[var(--polyu-red-50)] px-2.5 py-0.5 text-[11.5px] font-bold text-[var(--polyu-red-dark)]">
                  {badge}
                </span>
              )}
            </li>
          );
        })}
      </ul>
    </section>
  );
}

/** 「数据截至」日期部分（不含时钟；卡片小字口径） */
function formatAsOf(iso: string | null, zh: boolean): string {
  if (!iso) {
    return zh ? "未知" : "unknown";
  }
  const [date] = iso.split("T");
  const noon = new Date(`${date}T12:00:00+08:00`);
  return zh
    ? `${noon.getUTCMonth() + 1}月${noon.getUTCDate()}日`
    : `${noon.getUTCDate()} ${["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"][noon.getUTCMonth()]}`;
}

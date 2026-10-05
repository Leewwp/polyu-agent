import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";

import { useFeedLang } from "@/components/feed/feedLang";
import type { NewsDailyDigestSummary } from "@/types/news";
import { dailyMonthKey, dailyMonthLabel, hktTodayKey } from "@/services/newsMapping";
import { cn } from "@/lib/utils";

/**
 * 报眼月历（#242，原型 proto/238 内联组件转正，#238 R2 落位第 4 点）：
 * 展示当月每天的出刊状态，点击日期直达该期深链（/daily/:date）。
 * - 周一开头 7 列；
 * - 日格状态（data-state 可编程识别）：本期=红底白字（aria-current="date"）、
 *   已出刊=红点、休刊=灰点（仍可点，空期照常占刊位）、无刊（目录外/未出刊）=
 *   淡字不可点；今天=红框（data-today，与本期红底互斥呈现）；
 * - 月切换限存档范围（目录 30 期内有数据的月），越界方向 disabled（原生属性
 *   可编程识别，禁用月不可进入）；
 * - 挂载档位（同页 IssueToc 判例）：spacious=移动目录抽屉档，日格与月切换钮
 *   触控目标 ≥44px 高宽（#232 口径）；紧凑档=桌面 rail 鼠标档。
 * 目录数据全客户端推导（GET /api/ragent/public/news/daily），无后端改动。
 * 日期工具（月键/月标签/HKT 今天）与页面共用 newsMapping 单源（#259 去逐字双份）。
 */

type DayCellState = "current" | "published" | "recess" | "none";

export function IssueCalendar({
  summaries,
  selectedDate,
  spacious = false
}: {
  summaries: NewsDailyDigestSummary[];
  selectedDate: string;
  /** 抽屉档（触控）：日格/切换钮 ≥44px 高宽；rail 档保持紧凑 */
  spacious?: boolean;
}) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  const byDate = useMemo(() => new Map(summaries.map((s) => [s.digestDate, s])), [summaries]);
  // summaries 日期倒序：[0]=最新一期、末位=最早一期——月切换的存档边界
  const bounds = useMemo(
    () =>
      summaries.length > 0
        ? { min: dailyMonthKey(summaries[summaries.length - 1].digestDate), max: dailyMonthKey(summaries[0].digestDate) }
        : null,
    [summaries]
  );
  const [month, setMonth] = useState(() => dailyMonthKey(selectedDate));
  useEffect(() => {
    setMonth(dailyMonthKey(selectedDate));
  }, [selectedDate]);

  if (bounds === null) {
    return null;
  }

  const [y, m] = month.split("-").map(Number);
  const firstDow = (new Date(Date.UTC(y, m - 1, 1)).getUTCDay() + 6) % 7; // 周一开头
  const daysInMonth = new Date(Date.UTC(y, m, 0)).getUTCDate();
  const cells: (string | null)[] = [];
  for (let i = 0; i < firstDow; i++) {
    cells.push(null);
  }
  for (let d = 1; d <= daysInMonth; d++) {
    cells.push(`${month}-${String(d).padStart(2, "0")}`);
  }
  while (cells.length % 7 !== 0) {
    cells.push(null);
  }
  const todayKey = hktTodayKey();
  const canPrev = month > bounds.min;
  const canNext = month < bounds.max;
  const shiftMonth = (delta: number) => {
    const d = new Date(Date.UTC(y, m - 1 + delta, 1));
    setMonth(`${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, "0")}`);
  };
  const weekdays = zh ? ["一", "二", "三", "四", "五", "六", "日"] : ["M", "T", "W", "T", "F", "S", "S"];

  const stateOf = (date: string, summary: NewsDailyDigestSummary | undefined): DayCellState => {
    if (date === selectedDate) {
      return "current";
    }
    if (!summary) {
      return "none";
    }
    return summary.itemCount === 0 ? "recess" : "published";
  };

  return (
    <div aria-label={zh ? "报眼月历" : "Issue calendar"} className="rounded-2xl border border-[var(--feed-line)] bg-[var(--feed-card)] p-3 shadow-sm">
      <div className="mb-1.5 flex items-center">
        <button
          type="button"
          disabled={!canPrev}
          aria-label={zh ? "上一月" : "Previous month"}
          onClick={() => shiftMonth(-1)}
          className={cn(
            "flex flex-none items-center justify-center rounded-md leading-none",
            spacious ? "h-11 w-11 text-[15px]" : "h-6 w-6 text-[13px]",
            canPrev ? "text-[var(--feed-text-secondary)] hover:bg-[var(--feed-bg)]" : "cursor-default text-[var(--feed-line)]"
          )}
        >
          ‹
        </button>
        <b className={cn("flex-1 text-center font-bold text-[var(--feed-text-primary)]", spacious ? "text-[13.5px]" : "text-[12.5px]")}>
          {dailyMonthLabel(month, zh)}
        </b>
        <button
          type="button"
          disabled={!canNext}
          aria-label={zh ? "下一月" : "Next month"}
          onClick={() => shiftMonth(1)}
          className={cn(
            "flex flex-none items-center justify-center rounded-md leading-none",
            spacious ? "h-11 w-11 text-[15px]" : "h-6 w-6 text-[13px]",
            canNext ? "text-[var(--feed-text-secondary)] hover:bg-[var(--feed-bg)]" : "cursor-default text-[var(--feed-line)]"
          )}
        >
          ›
        </button>
      </div>
      {/* spacious 档无列间距：7×44px 在 390px 抽屉内放得下，间距会挤破 44px 宽目标 */}
      <div className="grid grid-cols-7">
        {weekdays.map((w, i) => (
          <span
            key={`wd-${i}`}
            className={cn(
              "text-center font-semibold text-[var(--feed-text-tertiary)]",
              spacious ? "pb-1.5 text-[10.5px]" : "pb-1 text-[9.5px]"
            )}
          >
            {w}
          </span>
        ))}
        {cells.map((date, i) => {
          if (!date) {
            return <span key={`blank-${i}`} className={spacious ? "min-h-[44px]" : "h-[30px]"} />;
          }
          const summary = byDate.get(date);
          const state = stateOf(date, summary);
          const isToday = date === todayKey;
          if (state === "none") {
            return (
              <span
                key={date}
                data-state="none"
                className={cn(
                  "flex items-center justify-center tabular-nums text-[#d4d4d8]",
                  spacious ? "min-h-[44px] text-[12px]" : "h-[30px] text-[11px]"
                )}
              >
                {Number(date.slice(8))}
              </span>
            );
          }
          return (
            <Link
              key={date}
              to={`/daily/${date}`}
              aria-current={state === "current" ? "date" : undefined}
              data-state={state}
              data-today={isToday ? "true" : undefined}
              className={cn(
                "relative flex items-center justify-center rounded-lg font-semibold tabular-nums transition-colors",
                spacious ? "min-h-[44px] text-[12px]" : "h-[30px] text-[11px]",
                state === "current"
                  ? "bg-[var(--polyu-red)] text-white"
                  : state === "recess"
                    ? "text-[var(--feed-text-tertiary)] hover:bg-[var(--feed-bg)]"
                    : "text-[var(--feed-text-primary)] hover:bg-[var(--feed-bg)]",
                isToday && state !== "current" && "border border-[var(--polyu-red)]"
              )}
            >
              {Number(date.slice(8))}
              {state !== "current" && (
                <span
                  className={cn(
                    "absolute rounded-full",
                    spacious ? "bottom-[6px] h-[4px] w-[4px]" : "bottom-[3px] h-[3px] w-[3px]",
                    state === "recess" ? "bg-[var(--feed-text-tertiary)]" : "bg-[var(--polyu-red)]"
                  )}
                />
              )}
            </Link>
          );
        })}
      </div>
      <div className={cn("mt-1.5 flex items-center justify-center gap-3 text-[var(--feed-text-tertiary)]", spacious ? "text-[10.5px]" : "text-[9.5px]")}>
        <span className="inline-flex items-center gap-1">
          <i className="h-[4px] w-[4px] rounded-full bg-[var(--polyu-red)]" />
          {zh ? "出刊" : "Published"}
        </span>
        <span className="inline-flex items-center gap-1">
          <i className="h-[4px] w-[4px] rounded-full bg-[var(--feed-text-tertiary)]" />
          {zh ? "休刊" : "Recess"}
        </span>
        <span className="inline-flex items-center gap-1">
          <i className="h-[7px] w-[7px] rounded-[3px] border border-[var(--polyu-red)]" />
          {zh ? "今天" : "Today"}
        </span>
      </div>
    </div>
  );
}

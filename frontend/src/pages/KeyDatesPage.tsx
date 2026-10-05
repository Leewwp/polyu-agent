import { useEffect, useState } from "react";
import { Link } from "react-router-dom";

import { FeedFooter } from "@/components/feed/FeedFooter";
import { FeedShell } from "@/components/feed/FeedShell";
import { useFeedLang } from "@/components/feed/feedLang";
import type { KeyDateBoard, KeyDateItem, KeyDateSourceStatus } from "@/types/keyDate";
import { countdownBadge, fetchKeyDateBoard, formatSyncTime, keyDateLabel } from "@/services/keyDateService";

/**
 * 公开关键日期页（#193；FeedPage 范式：裸路由无守卫、匿名直读
 * /public/calendar/key-dates；零引擎探测）。数据加载在页级（FeedLangProvider
 * 之外），全部双语呈现收在内层 Body（HotRankPage 判例：useFeedLang 须在
 * FeedShell 内调用）。
 *
 * 三态口径（票面验收「不冒充最新」）：
 * - 正常：头部只示覆盖学年+最近完整同步；
 * - 退化/隔离：顶部横幅明示「部分来源同步异常——以下为最后完整版本（截至 …）」，
 *   逐源状态面板标 degraded/isolated（数据截至时间原样透出，不刷新不粉饰）；
 * - 人工停用源标 manual_disabled（不参与同步）。
 * 四类 eStudent/邮件独发节点（个人考试时间表/个人缴费截止日/Add-Drop 结果/
 * 留位费截止日）=接受缺失不补录：固定缺失声明块+页脚「以 eStudent 及校务邮件为准」。
 */
const SOURCE_NAMES: Record<string, { zh: string; en: string }> = {
  "cal-academic-calendar": { zh: "校历", en: "Academic calendar" },
  "cal-fee-payment-annual": { zh: "缴费与证件时间表", en: "Fee & payment" },
  "cal-timetable-exam-results": { zh: "时间表·考试·成绩", en: "Timetable, exams & results" },
  "cal-exam-timetable": { zh: "考试时间表（纯校验源）", en: "Exam timetable (verifier)" },
  "cal-assessment-results": { zh: "成绩发布", en: "Assessment results" }
};

/** 四类 eStudent/邮件独发节点（合同§1：接受缺失不补录——固定声明，无数据面） */
const PERSONAL_NODE_GAPS: { zh: string; en: string }[] = [
  { zh: "个人考试时间表", en: "Personal exam timetable" },
  { zh: "个人缴费截止日", en: "Personal payment deadline" },
  { zh: "Add-Drop 结果", en: "Add/Drop results" },
  { zh: "留位费截止日", en: "Seat reservation fee deadline" }
];

function SourceStateChip({ state }: { state: KeyDateSourceStatus["state"] }) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  const map: Record<KeyDateSourceStatus["state"], { text: string; className: string }> = {
    normal: {
      text: zh ? "正常" : "Normal",
      className: "bg-[var(--feed-bg)] text-[var(--feed-text-secondary)]"
    },
    degraded: {
      text: zh ? "退化（保留最后完整版本）" : "Degraded (last complete kept)",
      className: "bg-[#FFF7E6] text-[#B45309]"
    },
    isolated: {
      text: zh ? "自动隔离（数据为隔离前版本）" : "Auto-isolated (pre-isolation data)",
      className: "bg-[#FEE2E2] text-[#B91C1C]"
    },
    manual_disabled: {
      text: zh ? "人工停用" : "Manually disabled",
      className: "bg-[var(--feed-bg)] text-[var(--feed-text-tertiary)]"
    }
  };
  const entry = map[state];
  return <span className={`flex-none rounded-full px-2 py-0.5 text-[11px] font-semibold ${entry.className}`}>{entry.text}</span>;
}

function EventRow({ item, dimmed = false }: { item: KeyDateItem; dimmed?: boolean }) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  const badge = countdownBadge(item, lang);
  return (
    <li
      className={`flex flex-wrap items-center gap-x-4 gap-y-1 border-b border-dashed border-[var(--feed-line-soft)] py-2.5 last:border-b-0 ${
        dimmed ? "opacity-70" : ""
      }`}
    >
      <span className="min-w-0 flex-1 text-[13.5px]">
        {(zh ? item.titleZh : item.titleEn) ?? item.titleEn}
        {item.audienceText && (
          <span className="ml-1.5 text-[11.5px] text-[var(--feed-text-tertiary)]">（{item.audienceText}）</span>
        )}
        <a
          href={item.sourceUrl}
          target="_blank"
          rel="noreferrer"
          className="ml-1.5 text-[11.5px] font-semibold text-[var(--feed-text-tertiary)] hover:text-[var(--polyu-red)]"
        >
          {zh ? "原文" : "source"}
        </a>
      </span>
      <span className="flex-none text-[12.5px] text-[var(--feed-text-secondary)]">{keyDateLabel(item, lang)}</span>
      {badge && (
        <span className="flex-none rounded-full bg-[var(--polyu-red-50)] px-2.5 py-0.5 text-[11.5px] font-bold text-[var(--polyu-red-dark)]">
          {badge}
        </span>
      )}
    </li>
  );
}

/** 页主体（FeedShell 内；lang 可用） */
function KeyDatesBody({ board, failed }: { board: KeyDateBoard | null; failed: boolean }) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  const [archivedOpen, setArchivedOpen] = useState(false);

  if (failed) {
    return (
      <div className="mt-4 rounded-2xl border border-dashed border-[var(--feed-line)] bg-[var(--feed-card)] p-7 text-center text-[13px] text-[var(--feed-text-tertiary)]">
        {zh
          ? "关键日期暂不可用，请稍后再试；最新信息以 eStudent 及校务邮件为准。"
          : "Key dates are unavailable right now — please retry later; always refer to eStudent and official email."}
      </div>
    );
  }
  if (!board) {
    return (
      <div className="mt-4 rounded-2xl border border-dashed border-[var(--feed-line)] bg-[var(--feed-card)] p-7 text-center text-[13px] text-[var(--feed-text-tertiary)]">
        {zh ? "加载中…" : "Loading…"}
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      {/* 头部：覆盖学年+最近完整同步（三态展示口径的正常态基线） */}
      <div className="flex flex-wrap items-center gap-2 rounded-2xl border border-[var(--feed-line)] bg-[var(--feed-card)] px-4 py-3 text-[12.5px]">
        <span className="rounded-full bg-[var(--polyu-red-50)] px-2.5 py-0.5 font-semibold text-[var(--polyu-red-dark)]">
          {zh ? `覆盖学年 ${board.coverageAcademicYear ?? "未知"}` : `Academic year ${board.coverageAcademicYear ?? "unknown"}`}
        </span>
        <span className="text-[var(--feed-text-secondary)]">
          {zh ? `最近完整同步 ${formatSyncTime(board.lastFullSyncAt, lang)}` : `Last full sync ${formatSyncTime(board.lastFullSyncAt, lang)}`}
        </span>
      </div>

      {/* 源异常横幅：退化/隔离不冒充最新（票面验收三态口径） */}
      {board.anySourceAbnormal && (
        <div className="rounded-[10px] border border-[#FDE68A] bg-[#FFFBEB] px-3.5 py-2 text-[12.5px] text-[#92400E]">
          {zh ? (
            <>
              <b>部分来源近期同步异常</b>
              ——以下为最后完整版本（截至 {formatSyncTime(board.lastFullSyncAt, lang)}），最新信息以 eStudent 及校务邮件为准
            </>
          ) : (
            <>
              <b>Some sources failed to sync recently</b>
              {" "}— showing the last complete version (as of {formatSyncTime(board.lastFullSyncAt, lang)});
              always refer to eStudent and official email for the latest
            </>
          )}
        </div>
      )}

      {/* 即将到来/进行中（临近度序；倒计时徽章=exact-day/exact-range） */}
      <section className="rounded-2xl border border-[var(--feed-line)] bg-[var(--feed-card)] p-4 md:px-[18px]">
        <h2 className="mb-1 text-[15px] font-bold">{zh ? "即将到来 · 进行中" : "Upcoming & ongoing"}</h2>
        {board.currentAndUpcoming.length === 0 ? (
          <p className="py-3 text-center text-[13px] text-[var(--feed-text-tertiary)]">
            {zh ? "近期没有已排定的关键日期" : "No scheduled key dates in the near future"}
          </p>
        ) : (
          <ul>
            {board.currentAndUpcoming.map((item) => (
              <EventRow key={item.uid} item={item} />
            ))}
          </ul>
        )}
      </section>

      {/* 近期已过（过期 ≤N 天；不作废展示供回看） */}
      {board.recentPast.length > 0 && (
        <section className="rounded-2xl border border-[var(--feed-line)] bg-[var(--feed-card)] p-4 md:px-[18px]">
          <h2 className="mb-1 text-[15px] font-bold">{zh ? "近期已过" : "Recently passed"}</h2>
          <ul>
            {board.recentPast.map((item) => (
              <EventRow key={item.uid} item={item} dimmed />
            ))}
          </ul>
        </section>
      )}

      {/* 已归档（过期 >N 天；默认折叠，跨学年旧事件不删除不冒充最新） */}
      {board.archivedTotal > 0 && (
        <section className="rounded-2xl border border-[var(--feed-line)] bg-[var(--feed-card)] p-4 md:px-[18px]">
          <button
            type="button"
            aria-expanded={archivedOpen}
            className="flex w-full items-baseline justify-between text-left"
            onClick={() => setArchivedOpen((open) => !open)}
          >
            <h2 className="text-[15px] font-bold">{zh ? `已归档（${board.archivedTotal}）` : `Archived (${board.archivedTotal})`}</h2>
            <span className="text-[12px] font-semibold text-[var(--feed-text-tertiary)]">
              {archivedOpen ? (zh ? "收起 ▲" : "Collapse ▲") : zh ? "展开 ▼" : "Expand ▼"}
            </span>
          </button>
          {archivedOpen && (
            <div className="mt-1">
              <ul>
                {board.archived.map((item) => (
                  <EventRow key={item.uid} item={item} dimmed />
                ))}
              </ul>
              {board.archivedTotal > board.archived.length && (
                <p className="pt-2 text-center text-[11.5px] text-[var(--feed-text-tertiary)]">
                  {zh
                    ? `已归档共 ${board.archivedTotal} 条，显示最近 ${board.archived.length} 条`
                    : `${board.archivedTotal} archived in total, showing the most recent ${board.archived.length}`}
                </p>
              )}
            </div>
          )}
        </section>
      )}

      {/* 模糊窗（官方未公布具体日——不伪造精确日期，不进倒计时） */}
      {board.undated.length > 0 && (
        <section className="rounded-2xl border border-[var(--feed-line)] bg-[var(--feed-card)] p-4 md:px-[18px]">
          <h2 className="mb-1 text-[15px] font-bold">{zh ? "日期待定（官方未公布具体日）" : "Undated (no exact date published)"}</h2>
          <ul>
            {board.undated.map((item) => (
              <EventRow key={item.uid} item={item} dimmed />
            ))}
          </ul>
        </section>
      )}

      {/* 来源状态面板（三态逐源；数据截至原样透出） */}
      <section className="rounded-2xl border border-[var(--feed-line)] bg-[var(--feed-card)] p-4 md:px-[18px]">
        <h2 className="mb-1.5 text-[15px] font-bold">{zh ? "来源状态" : "Source status"}</h2>
        <ul className="flex flex-col gap-1.5">
          {board.sources.map((source) => (
            <li key={source.sourceKey} className="flex flex-wrap items-center gap-2 text-[12.5px]">
              <span className="min-w-0 flex-1 truncate">
                {(zh ? SOURCE_NAMES[source.sourceKey]?.zh : SOURCE_NAMES[source.sourceKey]?.en) ?? source.sourceKey}
              </span>
              <SourceStateChip state={source.state} />
              <span className="flex-none text-[11.5px] text-[var(--feed-text-tertiary)]">
                {zh ? "上次完整同步 " : "last full sync "}
                {formatSyncTime(source.lastSuccessAt, lang)}
              </span>
            </li>
          ))}
          {board.sources.length === 0 && (
            <li className="py-2 text-center text-[13px] text-[var(--feed-text-tertiary)]">
              {zh ? "校历数据尚未同步，请稍后再来" : "Calendar data not synced yet"}
            </li>
          )}
        </ul>
      </section>

      {/* 四类 eStudent/邮件独发节点：接受缺失不补录（固定缺失声明） */}
      <section className="rounded-2xl border border-dashed border-[var(--feed-line)] bg-[var(--feed-card)] p-4 text-[12.5px] text-[var(--feed-text-secondary)] md:px-[18px]">
        <h2 className="mb-1 text-[13.5px] font-bold">{zh ? "本页不收录的个人信息" : "Not covered here"}</h2>
        <p>
          {zh
            ? "以下四类信息仅通过 eStudent 及校务邮件单独发布，本页不做补录："
            : "The following are only published individually via eStudent and official email and are intentionally not collected here: "}
          {PERSONAL_NODE_GAPS.map((gap) => (zh ? gap.zh : gap.en)).join(" · ")}
        </p>
      </section>

      {/* 缺口页脚（票面口径原文） */}
      <p className="text-center text-[11.5px] text-[var(--feed-text-tertiary)]">
        {zh
          ? "关键日期整理自理大公开页面，仅供快速参考——一切以 eStudent 及校务邮件为准。"
          : "Key dates are compiled from public PolyU pages for quick reference only — always refer to eStudent and official email."}
      </p>
      <div className="text-center text-[11.5px] text-[var(--feed-text-tertiary)]">
        <Link className="font-semibold hover:text-[var(--polyu-red-dark)]" to="/">
          {zh ? "← 返回资讯首页" : "← Back to feed"}
        </Link>
      </div>
    </div>
  );
}

export function KeyDatesPage() {
  const [board, setBoard] = useState<KeyDateBoard | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let alive = true;
    fetchKeyDateBoard()
      .then((data) => alive && setBoard(data))
      .catch(() => alive && setFailed(true));
    return () => {
      alive = false;
    };
  }, []);

  return (
    // pageHeading（#231）：分区标题（即将到来/近期已过…）不充当页名——页面级 h1 由壳渲染
    <FeedShell title={{ zh: "关键日期", en: "Key dates" }} pageHeading>
      <KeyDatesBody board={board} failed={failed} />
      <FeedFooter />
    </FeedShell>
  );
}

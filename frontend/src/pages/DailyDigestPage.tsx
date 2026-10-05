import { useEffect, useMemo, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";

import { FeedFooter } from "@/components/feed/FeedFooter";
import { FeedShell } from "@/components/feed/FeedShell";
import { useFeedLang } from "@/components/feed/feedLang";
import { IssueCalendar } from "@/components/daily/IssueCalendar";
import { useHeadElement } from "@/hooks/useHeadElement";
import {
  NEWS_CATEGORY_LABELS_EN,
  NEWS_CATEGORY_LABELS_ZH,
  NEWS_PLATFORM_COLOR_DEFAULT,
  NEWS_PLATFORM_COLORS,
  MONTHS_EN,
  dailyMonthKey,
  dailyMonthLabel,
  hktTodayKey
} from "@/services/newsMapping";
import {
  DAILY_MISSING_MESSAGE,
  dailyDigestRssUrl,
  dailyIssuesFeedUrl,
  fetchDailyDigest,
  fetchDailyDigestList
} from "@/services/newsService";
import type { NewsCategory, NewsDailyDigest, NewsDailyDigestItem, NewsDailyDigestSummary } from "@/types/news";
import { isSafeUrl } from "@/utils/urlSafety";
import { NotFoundPage } from "@/pages/NotFoundPage";
import { cn } from "@/lib/utils";

/**
 * 公开日报页（#241 报刊范式，原型 proto/238-daily-newspaper 转正）：
 * - 头条=快照序第一条（#237 Q6），头版放大、版面内不重复；
 * - 版面=9 类目固定版序（Q7，沿用 feed chips 序），空版消失；每版 >8 溢出快讯 ≤12；
 * - 导航两层（Q8）：桌面月分组 rail（首条标题两行预览=目录接口 firstTitle 字段，
 *   #240 已上线）+ 移动横向日期条（「今天」标记）+「本期目录」抽屉；
 * - 路由（Q5+默认采纳）：/daily=最新一期渲染（canonical=/daily）、
 *   /daily/:date 深链（canonical=自身）；日期切换=真实路由导航改写地址栏
 *   （对齐已提交 IndexNow/sitemap 的 URL 面）；key 不合式 404；
 * - 三态+页内重试（吸收 #234）：未出刊/休刊（rail 灰化+0 徽章+「本日休刊」
 *   说明全页仅一次+「查看热点」引导）/加载失败重试不整页刷新；越界日期
 *   （存档外）与失败态文案可区分（#238 复核点）；
 * - 透明口径：模板导语/部分内容已下架 chips 保留；统计条全客户端推导；
 *   document.title 按期设置——骑 #231 FeedShell title 单源机制（title prop
 *   传期标题，不另写 effect）；报眼月历（#242）挂 rail 顶与目录抽屉
 *   （IssueCalendar 独立组件，月切换限存档范围）；
 * - 加载反馈与竞态（#272）：目录/详情等待均出稳定骨架（aria-busy+
 *   装饰 aria-hidden+motion-safe 脉动），目录失败与详情失败分开表达，
 *   详情成功不抹目录失败；渲染守卫保证切期帧不把新日期配旧刊；
 *   切期关闭旧目录抽屉。
 */

/** 版序 = feed 类目 chips 序（#237 Q7：版序沿用 feed 类目序；other 兜底最后） */
const CATEGORY_ORDER: NewsCategory[] = [
  "admission",
  "research",
  "campus",
  "event",
  "career",
  "exchange",
  "scholarship",
  "admin",
  "other"
];

/** 每版容量上限，溢出进快讯（Q7：每版 >8 溢出快讯 ≤12） */
const SECTION_CAPACITY = 8;
const FLASH_CAP = 12;

/**
 * 目录拉取条数：吃满后端 MAX_LIST_LIMIT=400（约 13 个月存档）——月历/前后期
 * 导航/「存档自 X 起」提示与 rail 往期清单、移动日期条全部由同一份全量摘要
 * 驱动（展示面不再截 30，清单标签如实显示存档期数）
 */
const ARCHIVE_SUMMARY_LIMIT = 400;

const WEEKDAYS_ZH = ["日", "一", "二", "三", "四", "五", "六"];
const WEEKDAYS_EN = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

function dateParts(ds: string) {
  const d = new Date(`${ds}T12:00:00+08:00`);
  return { y: d.getUTCFullYear(), m: d.getUTCMonth() + 1, day: d.getUTCDate(), wd: d.getUTCDay() };
}
const zhDate = (ds: string) => {
  const p = dateParts(ds);
  return `${p.m}月${p.day}日`;
};
const zhWeekday = (ds: string) => `周${WEEKDAYS_ZH[dateParts(ds).wd]}`;
const enDate = (ds: string) => {
  const p = dateParts(ds);
  return `${p.day} ${MONTHS_EN[p.m - 1]}`;
};
const enWeekday = (ds: string) => WEEKDAYS_EN[dateParts(ds).wd];

interface DerivedIssue {
  lead: NewsDailyDigestItem | null;
  highlights: NewsDailyDigestItem[];
  sections: { cat: NewsCategory; items: NewsDailyDigestItem[] }[];
  flashes: NewsDailyDigestItem[];
  stats: { count: number; sources: number; official: number; minutes: number };
}

function deriveIssue(digest: NewsDailyDigest): DerivedIssue {
  const lead = digest.items[0] ?? null;
  const highlights = digest.items.slice(1, 4);
  const rest = digest.items.slice(1);
  const sections: DerivedIssue["sections"] = [];
  const flashes: NewsDailyDigestItem[] = [];
  for (const cat of CATEGORY_ORDER) {
    const bucket = rest.filter((item) => item.category === cat);
    if (bucket.length === 0) {
      continue;
    }
    sections.push({ cat, items: bucket.slice(0, SECTION_CAPACITY) });
    flashes.push(...bucket.slice(SECTION_CAPACITY));
  }
  const sourceKeys = new Set(digest.items.map((item) => item.source?.sourceKey).filter(Boolean));
  const chars = digest.items.reduce((acc, item) => acc + (item.titleZh?.length ?? 0) + (item.summaryZh?.length ?? 0), 0);
  return {
    lead,
    highlights,
    sections,
    flashes: flashes.slice(0, FLASH_CAP),
    stats: {
      count: digest.items.length,
      sources: sourceKeys.size,
      official: digest.items.filter((item) => item.source?.official).length,
      minutes: Math.max(1, Math.ceil(chars / 450))
    }
  };
}

function SourceDot({ item }: { item: NewsDailyDigestItem }) {
  const label = item.source?.displayName ?? item.source?.displayNameEn ?? "";
  if (!item.source) {
    return <span className="text-[11.5px] text-[var(--feed-text-tertiary)]">未知来源</span>;
  }
  return (
    <span className="inline-flex items-center gap-1.5 text-[11.5px] font-medium text-[var(--feed-text-secondary)]">
      <i
        className="h-[7px] w-[7px] flex-none rounded-full"
        style={{ backgroundColor: NEWS_PLATFORM_COLORS[item.source.platform] ?? NEWS_PLATFORM_COLOR_DEFAULT }}
      />
      {label}
    </span>
  );
}

function EntryCard({ item, headline = false }: { item: NewsDailyDigestItem; headline?: boolean }) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  const title = (zh ? item.titleZh : item.titleEn) || (zh ? item.titleEn : item.titleZh) || "";
  const summary = (zh ? item.summaryZh : item.summaryEn) || (zh ? item.summaryEn : item.summaryZh) || "";
  const time = item.publishTime ? item.publishTime.slice(11, 16) : "";
  return (
    <article
      className={cn(
        "rounded-2xl border border-[var(--feed-line)] bg-[var(--feed-card)] shadow-sm transition-colors hover:border-[#D8B7BC]",
        headline ? "h-full p-5 md:p-6" : "p-3.5 md:p-4"
      )}
    >
      <div className="mb-2 flex flex-wrap items-center gap-2.5">
        {headline && (
          <span className="rounded-full bg-[var(--polyu-red)] px-2.5 py-0.5 text-[11px] font-bold text-white">
            {zh ? "头条" : "LEAD"}
          </span>
        )}
        <span className="text-xs tabular-nums text-[var(--feed-text-tertiary)]">{time}</span>
        <SourceDot item={item} />
        <span className="rounded-full bg-[var(--polyu-red-50)] px-2.5 py-0.5 text-[11px] font-semibold text-[var(--polyu-red-dark)]">
          {zh ? NEWS_CATEGORY_LABELS_ZH[item.category] : NEWS_CATEGORY_LABELS_EN[item.category]}
        </span>
      </div>
      <h4 className={cn("font-bold leading-[1.42] text-[var(--feed-text-primary)]", headline ? "text-[22px] md:text-[26px] md:leading-[1.38]" : "text-[15px] md:text-[15.5px]")}>
        <Link to={`/news/${item.itemId}`}>{title}</Link>
      </h4>
      <p className={cn("mt-2 whitespace-pre-line text-[var(--feed-text-secondary)] leading-[1.7] [text-wrap:pretty]", headline ? "line-clamp-5 text-[13.5px] md:text-[14px]" : "line-clamp-2 text-[12.5px]")}>
        {summary}
      </p>
      {isSafeUrl(item.url) && (
        <div className="mt-2.5">
          <a
            className="inline-flex items-center gap-1 py-1 text-[12.5px] font-semibold text-[var(--polyu-red)] hover:underline"
            href={item.url}
            target="_blank"
            rel="noopener noreferrer"
          >
            {zh ? "查看原文 ↗" : "Source ↗"}
          </a>
        </div>
      )}
    </article>
  );
}

function Masthead({ digest, latest }: { digest: NewsDailyDigest; latest: boolean }) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  const date = digest.digestDate;
  const ws = digest.windowStart.slice(5, 16).replace("T", " ");
  const we = digest.windowEnd.slice(5, 16).replace("T", " ");
  const empty = digest.items.length === 0;
  return (
    <header className="mb-4">
      <div className="flex flex-wrap items-end gap-x-4 gap-y-2.5">
        <div className="min-w-0">
          <h1 className="text-[23px] font-black leading-none tracking-tight text-[var(--feed-text-primary)] min-[861px]:text-[27px]">
            {zh ? "理大资讯日报" : "PolyU Daily Digest"}
          </h1>
          <div className="mt-1.5 text-[10px] font-semibold tracking-[0.22em] text-[var(--feed-text-tertiary)]">
            POLYU DAILY DIGEST
          </div>
        </div>
        <div className="ml-auto flex flex-col items-end gap-1">
          <div className="flex items-center gap-2">
            {latest && (
              <span className="rounded-full bg-[var(--polyu-red)] px-2 py-0.5 text-[10.5px] font-bold text-white">
                {zh ? "最新一期" : "LATEST"}
              </span>
            )}
            {empty && (
              <span className="rounded-full border border-[var(--feed-line)] bg-[var(--feed-card)] px-2 py-0.5 text-[10.5px] font-bold text-[var(--feed-text-tertiary)]">
                {zh ? "休刊" : "RECESS"}
              </span>
            )}
            <span className="text-[14px] font-bold tabular-nums text-[var(--feed-text-primary)]">
              {zh ? `${date} · ${zhWeekday(date)}` : `${enWeekday(date)} · ${enDate(date)} ${date.slice(0, 4)}`}
            </span>
          </div>
          <div className="text-[10.5px] tabular-nums text-[var(--feed-text-tertiary)]">
            {zh ? `覆盖窗口 ${ws} → ${we} HKT · 出刊 ${digest.buildTime.slice(11, 16)}` : `Window ${ws} → ${we} HKT · Published ${digest.buildTime.slice(11, 16)}`}
          </div>
        </div>
      </div>
      <div className="mt-2.5 flex flex-wrap items-center gap-1.5">
        {(digest.storedIntroSource === "fallback" || digest.storedIntroSource === "empty") && (
          <span className="rounded-full border border-[var(--feed-line)] bg-[var(--feed-card)] px-2 py-0.5 text-[10.5px] font-semibold text-[var(--feed-text-tertiary)]">
            {zh ? "模板导语" : "Template intro"}
          </span>
        )}
        {digest.introDegraded && (
          <span className="rounded-full bg-[#FDF3E7] px-2 py-0.5 text-[10.5px] font-semibold text-[#B45309]">
            {zh ? "部分内容已下架" : "Some items removed"}
          </span>
        )}
      </div>
      <div className="mt-2.5 border-b-[3px] border-double border-[var(--feed-text-primary)]" />
    </header>
  );
}

function StatsBand({ stats }: { stats: DerivedIssue["stats"] }) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  const cells = [
    { n: String(stats.count), pre: "", zh: "条动态", en: "updates" },
    { n: String(stats.sources), pre: "", zh: "个来源", en: "sources" },
    { n: String(stats.official), pre: "", zh: "条官方发布", en: "official" },
    { n: String(stats.minutes), pre: zh ? "约 " : "~", zh: "分钟读完", en: "min read" }
  ];
  return (
    <div className="mb-4 flex flex-wrap items-center gap-x-1 gap-y-1 rounded-2xl border border-[var(--feed-line)] bg-[var(--feed-card)] px-5 py-3 text-[13px] text-[var(--feed-text-secondary)] shadow-sm">
      {cells.map((cell, i) => (
        <span key={cell.en} className="flex items-center">
          {i > 0 && <span className="mx-2 text-[var(--feed-line)]">·</span>}
          {cell.pre && <span className="mr-0.5">{cell.pre}</span>}
          <b className="tabular-nums text-[15px] font-black text-[var(--polyu-red)]">{cell.n}</b>
          <span className="ml-1">{zh ? cell.zh : cell.en}</span>
        </span>
      ))}
    </div>
  );
}

function IssueToc({ derived, spacious = false }: { derived: DerivedIssue; spacious?: boolean }) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  const rows = derived.sections.map((section, i) => ({
    key: section.cat,
    num: String(i + 1).padStart(2, "0"),
    label: zh ? NEWS_CATEGORY_LABELS_ZH[section.cat] : NEWS_CATEGORY_LABELS_EN[section.cat],
    count: zh ? `${section.items.length} 件` : `${section.items.length}`,
    href: `#sec-${section.cat}`
  }));
  if (derived.flashes.length > 0) {
    rows.push({
      key: "flash",
      num: String(rows.length + 1).padStart(2, "0"),
      label: zh ? "快讯" : "In brief",
      count: zh ? `${derived.flashes.length} 条` : `${derived.flashes.length}`,
      href: "#sec-flash"
    });
  }
  // #292：单条期（仅头条）版面行为空——按页面「空版消失」设计隐藏目录，
  // 不再渲染空 nav（头版「本期版面」标题由调用侧同条件隐藏）
  if (rows.length === 0) return null;
  return (
    <nav aria-label={zh ? "本期版面目录" : "Issue contents"} className="space-y-0.5">
      {rows.map((row) => (
        // 抽屉档 spacious=触控目标 ≥44px（#232）；头版右栏为桌面鼠标档保持紧凑
        <a
          key={row.key}
          href={row.href}
          className={cn(
            "flex items-baseline gap-2 rounded-lg px-1.5 hover:bg-[var(--feed-bg)]",
            spacious ? "min-h-[44px] py-2" : "py-[3px]"
          )}
        >
          <span className="w-[18px] flex-none text-right text-[11px] font-black tabular-nums text-[var(--polyu-red)]">{row.num}</span>
          <span className="min-w-0 flex-1 truncate text-[12px] font-semibold text-[var(--feed-text-primary)]">{row.label}</span>
          <span className="text-[10.5px] tabular-nums text-[var(--feed-text-tertiary)]">{row.count}</span>
        </a>
      ))}
    </nav>
  );
}

function FrontPage({ digest, derived }: { digest: NewsDailyDigest; derived: DerivedIssue }) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  const intro = (zh ? digest.introZh : digest.introEn) || "";
  return (
    <section className="mb-5">
      {intro && (
        <p className="mb-3 text-[12.5px] leading-[1.7] text-[var(--feed-text-tertiary)]">{intro}</p>
      )}
      <StatsBand stats={derived.stats} />
      <div className="grid items-stretch gap-3.5 min-[861px]:grid-cols-[minmax(0,1fr)_252px]">
        <div className="min-w-0 space-y-3.5">
          {derived.lead && <EntryCard item={derived.lead} headline />}
        </div>
        <aside className="min-w-0">
          <div className="rounded-2xl border border-[var(--feed-line)] bg-[var(--feed-card)] p-3.5 shadow-sm">
            <div className="mb-1.5 text-[10px] font-bold tracking-[0.14em] text-[var(--feed-text-tertiary)]">
              {zh ? "今日看点" : "HIGHLIGHTS"}
            </div>
            {derived.highlights.map((item, i) => {
              const title = (zh ? item.titleZh : item.titleEn) || "";
              return (
                <div key={item.itemId} className="flex gap-2 border-b border-[var(--feed-line-soft)] py-1.5 last:border-0">
                  <span className="text-[11.5px] font-black tabular-nums leading-[1.45] text-[var(--polyu-red)]">
                    {String(i + 2).padStart(2, "0")}
                  </span>
                  <div className="min-w-0">
                    <Link to={`/news/${item.itemId}`} className="line-clamp-2 text-[12px] font-semibold leading-[1.45] text-[var(--feed-text-primary)] hover:text-[var(--polyu-red)]">
                      {title}
                    </Link>
                    <div className="mt-px flex items-center gap-1.5">
                      <span className="text-[10px] tabular-nums text-[var(--feed-text-tertiary)]">{item.publishTime?.slice(11, 16)}</span>
                      <span className="text-[10px] text-[var(--feed-text-tertiary)]">
                        {zh ? NEWS_CATEGORY_LABELS_ZH[item.category] : NEWS_CATEGORY_LABELS_EN[item.category]}
                      </span>
                    </div>
                  </div>
                </div>
              );
            })}
            {derived.highlights.length === 0 && (
              <div className="py-1.5 text-[11.5px] text-[var(--feed-text-tertiary)]">{zh ? "本期看点不足三条" : "—"}</div>
            )}
            {/* #292：单条期（仅头条）版面行为空——「本期版面」整块随空版消失，
                与 IssueToc 空态同条件（sections+快讯均空） */}
            {(derived.sections.length > 0 || derived.flashes.length > 0) && (
              <>
                <div className="my-2.5 border-t border-[var(--feed-line-soft)]" />
                <div className="mb-1 text-[10px] font-bold tracking-[0.14em] text-[var(--feed-text-tertiary)]">
                  {zh ? "本期版面" : "SECTIONS"}
                </div>
                <IssueToc derived={derived} />
              </>
            )}
          </div>
        </aside>
      </div>
    </section>
  );
}

function SectionBlocks({ derived }: { derived: DerivedIssue }) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  return (
    <>
      {derived.sections.map((section, i) => (
        <section key={section.cat} id={`sec-${section.cat}`} className="mb-6 scroll-mt-[76px]">
          <div className="mb-3 flex items-baseline gap-2.5">
            <span className="text-[15px] font-black tabular-nums text-[var(--polyu-red)]">{String(i + 1).padStart(2, "0")}</span>
            <h2 className="text-[17.5px] font-bold text-[var(--feed-text-primary)]">
              {zh ? NEWS_CATEGORY_LABELS_ZH[section.cat] : NEWS_CATEGORY_LABELS_EN[section.cat]}
            </h2>
            <span className="text-[11.5px] tabular-nums text-[var(--feed-text-tertiary)]">
              {zh ? `${section.items.length} 件` : `${section.items.length} items`}
            </span>
            <span className="mx-1 flex-1 border-b border-[var(--feed-line-soft)]" />
          </div>
          <div className="grid gap-3 min-[861px]:grid-cols-2">
            {section.items.map((item) => (
              <EntryCard key={item.itemId} item={item} />
            ))}
          </div>
        </section>
      ))}
      {derived.flashes.length > 0 && (
        <section id="sec-flash" className="mb-6 scroll-mt-[76px]">
          <div className="mb-3 flex items-baseline gap-2.5">
            <span className="text-[15px] font-black tabular-nums text-[var(--polyu-red)]">
              {String(derived.sections.length + 1).padStart(2, "0")}
            </span>
            <h2 className="text-[17.5px] font-bold text-[var(--feed-text-primary)]">{zh ? "快讯" : "In brief"}</h2>
            <span className="text-[11.5px] tabular-nums text-[var(--feed-text-tertiary)]">
              {zh ? `${derived.flashes.length} 条 · 版面溢出` : `${derived.flashes.length} overflow items`}
            </span>
            <span className="mx-1 flex-1 border-b border-[var(--feed-line-soft)]" />
          </div>
          <div className="grid gap-2 min-[861px]:grid-cols-2">
            {derived.flashes.map((item) => (
              <div key={item.itemId} className="flex items-center gap-2.5 rounded-xl border border-[var(--feed-line)] bg-[var(--feed-card)] px-3.5 py-2 shadow-sm">
                <span className="text-[var(--feed-line)]">·</span>
                <Link to={`/news/${item.itemId}`} className="line-clamp-1 min-w-0 flex-1 text-[13px] font-medium text-[var(--feed-text-primary)] hover:text-[var(--polyu-red)]">
                  {(zh ? item.titleZh : item.titleEn) || ""}
                </Link>
                <SourceDot item={item} />
              </div>
            ))}
          </div>
        </section>
      )}
    </>
  );
}

function PrevNext({ summaries, selectedDate }: { summaries: NewsDailyDigestSummary[]; selectedDate: string }) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  const idx = summaries.findIndex((s) => s.digestDate === selectedDate);
  // summaries 日期倒序：上一期（更旧）= idx+1，下一期（更新）= idx-1；
  // 相邻期从 30 期目录推导（最新一期无 next）
  const prev = idx >= 0 ? summaries[idx + 1] ?? null : null;
  const next = idx > 0 ? summaries[idx - 1] ?? null : null;
  const cell = (issue: NewsDailyDigestSummary | null, dir: "prev" | "next") => {
    const label = dir === "prev" ? (zh ? "上一期" : "Previous") : zh ? "下一期" : "Next";
    if (!issue) {
      return (
        <div className="flex items-center justify-center rounded-2xl border border-dashed border-[var(--feed-line)] bg-transparent px-4 py-4 text-[12.5px] text-[var(--feed-text-tertiary)]">
          {dir === "prev" ? (zh ? "已是最早一期" : "First issue") : zh ? "已是最新一期" : "Latest issue"}
        </div>
      );
    }
    const preview = (zh ? issue.firstTitleZh : issue.firstTitleEn) || (zh ? "本日休刊" : "In recess");
    return (
      <Link
        to={`/daily/${issue.digestDate}`}
        className="block rounded-2xl border border-[var(--feed-line)] bg-[var(--feed-card)] px-4 py-3.5 shadow-sm transition-colors hover:border-[#D8B7BC]"
      >
        <div className="mb-1 flex items-center gap-2">
          <span className="text-[11.5px] font-bold text-[var(--feed-text-tertiary)]">{label}</span>
          <span className="text-[13px] font-bold tabular-nums text-[var(--feed-text-primary)]">
            {zh ? `${zhDate(issue.digestDate)} ${zhWeekday(issue.digestDate)}` : `${enWeekday(issue.digestDate)} ${enDate(issue.digestDate)}`}
          </span>
          <span className={cn("ml-auto rounded-full px-2 py-0.5 text-[10.5px] font-bold tabular-nums", issue.itemCount === 0 ? "bg-[var(--feed-bg)] text-[var(--feed-text-tertiary)]" : "bg-[var(--polyu-red-50)] text-[var(--polyu-red-dark)]")}>
            {zh ? `${issue.itemCount} 条` : issue.itemCount}
          </span>
        </div>
        <p className="line-clamp-1 text-[12px] text-[var(--feed-text-secondary)]">{preview}</p>
      </Link>
    );
  };
  return (
    <nav aria-label={zh ? "上下一期导航" : "Issue navigation"} className="mt-7 grid grid-cols-2 gap-3">
      {cell(prev, "prev")}
      {cell(next, "next")}
    </nav>
  );
}

function DesktopRail({ summaries, selectedDate }: { summaries: NewsDailyDigestSummary[]; selectedDate: string }) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  const groups = useMemo(() => {
    const out: { key: string; list: NewsDailyDigestSummary[] }[] = [];
    // 全量清单（不截近期 30）：按月分组折叠，rail 自身滚动承载长清单
    for (const s of summaries) {
      const key = dailyMonthKey(s.digestDate);
      if (out.length === 0 || out[out.length - 1].key !== key) {
        out.push({ key, list: [] });
      }
      out[out.length - 1].list.push(s);
    }
    return out;
  }, [summaries]);
  return (
    <aside className="hidden min-[861px]:block" aria-label={zh ? "往期日报" : "Past issues"}>
      <div className="sticky top-[76px] max-h-[calc(100vh-96px)] overflow-y-auto pb-4 pr-1">
        {/* 报眼月历（#242）：rail 顶=月历，其下才是往期清单；鼠标档紧凑 */}
        <div className="mb-3">
          <IssueCalendar summaries={summaries} selectedDate={selectedDate} />
        </div>
        <div className="mb-2 px-1 text-[11px] font-bold tracking-[0.14em] text-[var(--feed-text-tertiary)]">
          {zh ? `往期 · ${summaries.length} 期` : `PAST ${summaries.length} ISSUES`}
        </div>
        {groups.map((group) => (
          <details key={group.key} open className="mb-2">
            <summary className="mb-1.5 cursor-pointer list-none rounded-lg bg-[var(--feed-card)] px-2.5 py-1.5 text-[12px] font-bold text-[var(--feed-text-secondary)] shadow-sm">
              {dailyMonthLabel(group.key, zh)}
              <span className="ml-1.5 text-[10.5px] font-normal text-[var(--feed-text-tertiary)]">
                {zh ? `${group.list.length} 期` : `${group.list.length}`}
              </span>
            </summary>
            {group.list.map((s) => {
              const p = dateParts(s.digestDate);
              const selected = s.digestDate === selectedDate;
              const empty = s.itemCount === 0;
              const preview = (zh ? s.firstTitleZh : s.firstTitleEn) || (zh ? "本日休刊，窗口内无公开发布" : "In recess — nothing published in window");
              return (
                <Link
                  key={s.digestDate}
                  to={`/daily/${s.digestDate}`}
                  aria-current={selected ? "date" : undefined}
                  className={cn(
                    "mb-1 block rounded-xl border px-2.5 py-2 transition-colors",
                    selected
                      ? "border-[var(--polyu-red)] bg-[var(--polyu-red-50)]"
                      : "border-transparent hover:bg-[var(--feed-card)]",
                    empty && !selected && "opacity-55"
                  )}
                >
                  <div className="flex items-baseline gap-1.5">
                    <span className="text-[15px] font-black tabular-nums text-[var(--feed-text-primary)]">{p.day}</span>
                    <span className="text-[10.5px] text-[var(--feed-text-tertiary)]">{zh ? zhWeekday(s.digestDate) : enWeekday(s.digestDate)}</span>
                    <span
                      className={cn(
                        "ml-auto rounded-full px-1.5 py-px text-[10px] font-bold tabular-nums",
                        empty ? "bg-[var(--feed-bg)] text-[var(--feed-text-tertiary)]" : "bg-[var(--polyu-red-50)] text-[var(--polyu-red-dark)]"
                      )}
                    >
                      {empty ? (zh ? "0" : "0") : s.itemCount}
                    </span>
                  </div>
                  <p className="mt-0.5 line-clamp-2 text-[11.5px] leading-[1.45] text-[var(--feed-text-secondary)]">{preview}</p>
                </Link>
              );
            })}
          </details>
        ))}
      </div>
    </aside>
  );
}

function MobileDateBar({ summaries, selectedDate }: { summaries: NewsDailyDigestSummary[]; selectedDate: string }) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  const barRef = useRef<HTMLDivElement>(null);
  const todayKey = hktTodayKey();
  useEffect(() => {
    const el = barRef.current?.querySelector<HTMLElement>("[data-selected='true']");
    // jsdom 无 scrollIntoView 实现——可选调用防测试环境炸
    el?.scrollIntoView?.({ inline: "center", block: "nearest" });
  }, [selectedDate]);
  return (
    <div className="-mx-3.5 mb-4 min-[861px]:hidden">
      <div ref={barRef} className="flex gap-1.5 overflow-x-auto px-3.5 pb-2.5">
        {summaries.map((s) => {
          const selected = s.digestDate === selectedDate;
          const empty = s.itemCount === 0;
          const isToday = s.digestDate === todayKey;
          return (
            <Link
              key={s.digestDate}
              to={`/daily/${s.digestDate}`}
              data-selected={selected}
              aria-current={selected ? "date" : undefined}
              className={cn(
                "flex min-h-[44px] flex-none items-center whitespace-nowrap rounded-full border px-3.5 text-[12px] font-semibold tabular-nums transition-colors",
                selected
                  ? "border-[var(--polyu-red)] bg-[var(--polyu-red)] text-white"
                  : "border-[var(--feed-line)] bg-[var(--feed-card)] text-[var(--feed-text-secondary)] hover:border-[#D8B7BC]",
                empty && !selected && "opacity-55"
              )}
            >
              {isToday ? (zh ? "今天" : "Today") : zh ? zhDate(s.digestDate) : enDate(s.digestDate)}
              {empty && <span className="ml-1 text-[10px] font-normal">{zh ? "休" : "·0"}</span>}
            </Link>
          );
        })}
      </div>
    </div>
  );
}

function MobileTocButton({ onOpen }: { onOpen: () => void }) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  return (
    <div className="mb-4 min-[861px]:hidden">
      <button
        type="button"
        onClick={onOpen}
        className="min-h-[44px] w-full rounded-xl border border-[var(--feed-line)] bg-[var(--feed-card)] py-2.5 text-[13.5px] font-bold text-[var(--feed-text-primary)] shadow-sm transition-colors hover:border-[#D8B7BC]"
      >
        {zh ? "本期目录" : "Contents"} ⌄
      </button>
    </div>
  );
}

function TocDrawer({
  open,
  onClose,
  digest,
  derived,
  summaries,
  selectedDate
}: {
  open: boolean;
  onClose: () => void;
  digest: NewsDailyDigest;
  derived: DerivedIssue;
  summaries: NewsDailyDigestSummary[];
  selectedDate: string;
}) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  useEffect(() => {
    if (!open) {
      return;
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);
  if (!open) {
    return null;
  }
  const idx = summaries.findIndex((s) => s.digestDate === selectedDate);
  const prev = idx >= 0 ? summaries[idx + 1] ?? null : null;
  const next = idx > 0 ? summaries[idx - 1] ?? null : null;
  return (
    <div className="fixed inset-0 z-50 min-[861px]:hidden">
      <button type="button" aria-label={zh ? "关闭目录" : "Close contents"} className="absolute inset-0 bg-black/40" onClick={onClose} />
      <div className="absolute inset-x-0 bottom-0 max-h-[74vh] overflow-y-auto rounded-t-2xl bg-[var(--feed-card)] px-5 pb-8 pt-4 shadow-2xl">
        <div className="mb-3 flex items-center">
          <b className="text-[14.5px] font-bold text-[var(--feed-text-primary)]">
            {zh ? `本期目录 · ${digest.digestDate}` : `Contents · ${digest.digestDate}`}
          </b>
          <button
            type="button"
            onClick={onClose}
            className="ml-auto flex h-11 w-11 items-center justify-center rounded-lg border border-[var(--feed-line)] text-[13px] text-[var(--feed-text-secondary)]"
            aria-label={zh ? "关闭" : "Close"}
          >
            ✕
          </button>
        </div>
        {digest.items.length === 0 ? (
          <p className="py-4 text-center text-[13px] text-[var(--feed-text-tertiary)]">{zh ? "本日休刊，无版面" : "In recess"}</p>
        ) : (
          <IssueToc derived={derived} spacious />
        )}
        {/* 报眼月历（#242）：抽屉档 spacious=日格/切换钮 ≥44px 触控目标 */}
        <div className="mt-4 border-t border-[var(--feed-line-soft)] pt-3.5">
          <IssueCalendar summaries={summaries} selectedDate={selectedDate} spacious />
        </div>
        <div className="mt-4 grid grid-cols-2 gap-2 border-t border-[var(--feed-line-soft)] pt-3.5">
          <div>
            <div className="mb-1 text-[10.5px] font-bold text-[var(--feed-text-tertiary)]">{zh ? "上一期" : "PREVIOUS"}</div>
            {prev ? (
              <Link
                to={`/daily/${prev.digestDate}`}
                onClick={onClose}
                className="flex min-h-[44px] items-center text-[13px] font-semibold text-[var(--feed-text-primary)]"
              >
                {zh ? zhDate(prev.digestDate) : enDate(prev.digestDate)}
              </Link>
            ) : (
              <span className="flex min-h-[44px] items-center text-[12px] text-[var(--feed-text-tertiary)]">—</span>
            )}
          </div>
          <div className="text-right">
            <div className="mb-1 text-[10.5px] font-bold text-[var(--feed-text-tertiary)]">{zh ? "下一期" : "NEXT"}</div>
            {next ? (
              <Link
                to={`/daily/${next.digestDate}`}
                onClick={onClose}
                className="flex min-h-[44px] items-center justify-end text-[13px] font-semibold text-[var(--feed-text-primary)]"
              >
                {zh ? zhDate(next.digestDate) : enDate(next.digestDate)}
              </Link>
            ) : (
              <span className="flex min-h-[44px] items-center justify-end text-[12px] text-[var(--feed-text-tertiary)]">—</span>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

function EmptyIssueCard({ digest }: { digest: NewsDailyDigest }) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  const ws = digest.windowStart.slice(5, 16).replace("T", " ");
  const we = digest.windowEnd.slice(5, 16).replace("T", " ");
  return (
    <div className="rounded-2xl border border-dashed border-[var(--feed-line)] bg-[var(--feed-card)] px-6 py-12 text-center">
      <div className="mb-2 text-[17px] font-bold text-[var(--feed-text-primary)]">{zh ? "本日休刊" : "In recess"}</div>
      {/* 休刊说明全页仅此一处（rail/翻期格只留「本日休刊」短标签） */}
      <p className="mx-auto mb-4 max-w-[460px] text-[12.5px] leading-[1.7] text-[var(--feed-text-tertiary)]">
        {zh
          ? `覆盖窗口（${ws} → ${we} HKT）内没有可见的公开发布。休刊照常占住刊位、URL 可预期，不是生成故障，也不触发跳刊或并期。`
          : `Nothing visible was published in the window (${ws} → ${we} HKT). A recess issue still holds its date and URL — it is not an outage, and no issue is skipped or merged.`}
      </p>
      <div className="flex items-center justify-center gap-5">
        <Link to="/hot" className="inline-flex min-h-[44px] items-center text-[13px] font-semibold text-[var(--polyu-red)] hover:underline">
          {zh ? "查看热点 →" : "Browse the hot rank →"}
        </Link>
        <Link to="/daily" className="inline-flex min-h-[44px] items-center text-[13px] font-semibold text-[var(--feed-text-secondary)] hover:underline">
          {zh ? "查看最新一期 →" : "Read the latest issue →"}
        </Link>
      </div>
    </div>
  );
}

/** 加载失败+页内重试（#234 吸收）：重试=页内状态复位重取，不整页刷新。
 *  #272：目录失败与详情失败分开表达——title 区分（目录=清单拉不到，
 *  详情=单期拉不到），其余复用同一张卡。 */
function FailureCard({
  onRetry,
  title
}: {
  onRetry: () => void;
  title?: { zh: string; en: string };
}) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  return (
    <div className="rounded-2xl border border-dashed border-[var(--feed-line)] bg-[var(--feed-card)] p-7 text-center">
      <div className="mb-1.5 text-[15px] font-bold text-[var(--feed-text-primary)]">
        {title ? (zh ? title.zh : title.en) : zh ? "日报加载失败" : "Failed to load the daily digest"}
      </div>
      <p className="mb-4 text-[12.5px] text-[var(--feed-text-tertiary)]">
        {zh ? "网络或服务暂时不可用，请稍后重试。" : "The network or service is temporarily unavailable. Please retry."}
      </p>
      <button
        type="button"
        onClick={onRetry}
        className="min-h-[44px] rounded-xl bg-[var(--polyu-red)] px-6 text-[13.5px] font-bold text-white transition-opacity hover:opacity-90"
      >
        {zh ? "重试" : "Retry"}
      </button>
    </div>
  );
}

const LIST_FAILURE_TITLE = { zh: "日报目录加载失败", en: "Failed to load the issue list" };

/**
 * 主栏等待骨架（#272）：刊头/统计条/头条+看点/版面块的稳定布局占位。
 * 可访问性：容器 role=status + aria-busy 播报加载状态；全部装饰块
 * aria-hidden；脉动动画走 motion-safe:（prefers-reduced-motion 下静止不闪）。
 */
function DigestSkeleton() {
  return (
    <div role="status" aria-busy="true">
      <span className="sr-only">正在加载日报，请稍候</span>
      <div aria-hidden="true">
        {/* 刊头行：题字+日期块 */}
        <div className="mb-4">
          <div className="flex flex-wrap items-end gap-x-4 gap-y-2.5">
            <div className="min-w-0">
              <div className="h-[27px] w-52 rounded bg-[var(--feed-line-soft)] motion-safe:animate-pulse" />
              <div className="mt-2 h-2.5 w-40 rounded bg-[var(--feed-line-soft)] motion-safe:animate-pulse" />
            </div>
            <div className="ml-auto flex flex-col items-end gap-1.5">
              <div className="h-[19px] w-36 rounded bg-[var(--feed-line-soft)] motion-safe:animate-pulse" />
              <div className="h-2.5 w-44 rounded bg-[var(--feed-line-soft)] motion-safe:animate-pulse" />
            </div>
          </div>
          <div className="mt-2.5 border-b-[3px] border-double border-[var(--feed-line-soft)]" />
        </div>
        {/* 统计条 */}
        <div className="mb-4 h-[46px] rounded-2xl border border-[var(--feed-line)] bg-[var(--feed-card)] shadow-sm motion-safe:animate-pulse" />
        {/* 头条大卡+今日看点栏 */}
        <div className="grid items-stretch gap-3.5 min-[861px]:grid-cols-[minmax(0,1fr)_252px]">
          <div className="h-[216px] rounded-2xl border border-[var(--feed-line)] bg-[var(--feed-card)] shadow-sm motion-safe:animate-pulse" />
          <div className="h-[216px] rounded-2xl border border-[var(--feed-line)] bg-[var(--feed-card)] shadow-sm motion-safe:animate-pulse" />
        </div>
        {/* 版面块标题+两枚条目卡 */}
        <div className="mt-6 h-5 w-32 rounded bg-[var(--feed-line-soft)] motion-safe:animate-pulse" />
        <div className="mt-3 grid gap-3 min-[861px]:grid-cols-2">
          <div className="h-[104px] rounded-2xl border border-[var(--feed-line)] bg-[var(--feed-card)] shadow-sm motion-safe:animate-pulse" />
          <div className="h-[104px] rounded-2xl border border-[var(--feed-line)] bg-[var(--feed-card)] shadow-sm motion-safe:animate-pulse" />
        </div>
      </div>
    </div>
  );
}

/** 目录等待时的 rail 位占位（#272）：月历块+往期清单数行，纯装饰 */
function RailSkeleton() {
  return (
    <aside className="hidden min-[861px]:block" aria-hidden="true">
      <div className="sticky top-[76px] pb-4 pr-1">
        <div className="mb-3 h-[164px] rounded-xl border border-[var(--feed-line)] bg-[var(--feed-card)] shadow-sm motion-safe:animate-pulse" />
        <div className="mb-2 h-4 w-24 rounded bg-[var(--feed-line-soft)] motion-safe:animate-pulse" />
        <div className="space-y-1.5">
          {Array.from({ length: 5 }, (_, i) => (
            <div
              key={i}
              className="h-[58px] rounded-xl border border-transparent bg-[var(--feed-card)] shadow-sm motion-safe:animate-pulse"
            />
          ))}
        </div>
      </div>
    </aside>
  );
}

/** 越界日期（存档外）：与加载失败文案可区分（#238 复核点） */
function MissingIssueCard({ summaries }: { summaries: NewsDailyDigestSummary[] }) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  const earliest = summaries[summaries.length - 1]?.digestDate;
  return (
    <div className="rounded-2xl border border-dashed border-[var(--feed-line)] bg-[var(--feed-card)] p-7 text-center">
      <div className="mb-1.5 text-[15px] font-bold text-[var(--feed-text-primary)]">
        {zh ? "该日期暂无刊期存档" : "No archived issue on this date"}
      </div>
      <p className="mb-4 text-[12.5px] text-[var(--feed-text-tertiary)]">
        {earliest
          ? zh
            ? `线上存档自 ${earliest} 起。可从往期目录选择日期，或`
            : `The online archive starts on ${earliest}. Pick a date from past issues, or `
          : zh
            ? "可从往期目录选择日期，或"
            : "Pick a date from past issues, or "}
      </p>
      <Link to="/daily" className="inline-flex min-h-[44px] items-center text-[13px] font-semibold text-[var(--polyu-red)] hover:underline">
        {zh ? "查看最新一期 →" : "Read the latest issue →"}
      </Link>
    </div>
  );
}

/**
 * 一期正文的完整主栏（#272 抽出）：summaries 为空数组（目录失败但深链详情
 * 并行成功的场景）时省略依赖目录的导航件（日期条/翻期格），latest 无从判定。
 */
function IssueBody({
  digest,
  derived,
  summaries,
  onOpenToc
}: {
  digest: NewsDailyDigest;
  derived: DerivedIssue;
  summaries: NewsDailyDigestSummary[];
  onOpenToc: () => void;
}) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  const hasCatalog = summaries.length > 0;
  return (
    <>
      <Masthead digest={digest} latest={hasCatalog && digest.digestDate === summaries[0].digestDate} />
      {hasCatalog && <MobileDateBar summaries={summaries} selectedDate={digest.digestDate} />}
      <MobileTocButton onOpen={onOpenToc} />
      {digest.items.length > 0 ? (
        <>
          <FrontPage digest={digest} derived={derived} />
          <SectionBlocks derived={derived} />
        </>
      ) : (
        <EmptyIssueCard digest={digest} />
      )}
      {hasCatalog && <PrevNext summaries={summaries} selectedDate={digest.digestDate} />}
      <div className="mt-6 border-t border-[var(--feed-line-soft)] pt-4 text-center">
        <span className="text-[11.5px] text-[var(--feed-text-tertiary)]">
          {zh ? "— 本期完 —" : "— End of issue —"}
        </span>
        <div className="mt-2 flex flex-wrap items-center justify-center gap-4">
          <a
            href={dailyDigestRssUrl(digest.digestDate)}
            target="_blank"
            rel="noreferrer"
            className="inline-flex min-h-[44px] items-center text-[12px] font-semibold text-[var(--feed-text-secondary)] hover:underline"
          >
            {zh ? "本期 RSS ↗" : "Issue RSS ↗"}
          </a>
          {/* #243 期级订阅出口（默认口径=刊尾位，维护者可否决改位）：连续刊物 feed */}
          <a
            href={dailyIssuesFeedUrl()}
            target="_blank"
            rel="noreferrer"
            className="inline-flex min-h-[44px] items-center text-[12px] font-semibold text-[var(--feed-text-secondary)] hover:underline"
          >
            {zh ? "订阅日报 ↗" : "Subscribe ↗"}
          </a>
        </div>
      </div>
    </>
  );
}

export function DailyDigestPage() {
  const { date: routeDate } = useParams();
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  const [summaries, setSummaries] = useState<NewsDailyDigestSummary[] | null>(null);
  const [digest, setDigest] = useState<NewsDailyDigest | null>(null);
  // #272：目录失败与详情失败分开表达——详情成功不能抹掉目录失败，
  // 目录失败也不能吞掉深链并行到达的详情成功。
  const [listFailed, setListFailed] = useState(false);
  const [detailFailed, setDetailFailed] = useState(false);
  const [missing, setMissing] = useState(false);
  const [reloadKey, setReloadKey] = useState(0);
  const [tocOpen, setTocOpen] = useState(false);

  const malformed = routeDate !== undefined && !/^\d{4}-\d{2}-\d{2}$/.test(routeDate);
  const selectedDate = routeDate ?? summaries?.[0]?.digestDate ?? null;

  useEffect(() => {
    if (malformed) {
      return;
    }
    let alive = true;
    fetchDailyDigestList(ARCHIVE_SUMMARY_LIMIT)
      .then((list) => {
        if (!alive) {
          return;
        }
        setSummaries(list);
        setListFailed(false);
      })
      .catch(() => alive && setListFailed(true));
    return () => {
      alive = false;
    };
  }, [reloadKey, malformed]);

  useEffect(() => {
    if (!selectedDate || malformed) {
      return;
    }
    let alive = true;
    setDigest(null);
    setMissing(false);
    setDetailFailed(false);
    // 切期关闭旧目录抽屉：抽屉内容（版面目录/上下期）全部期绑定，
    // 不能带进新等待期，更不能在新期到达后自动重新弹出
    setTocOpen(false);
    fetchDailyDigest(selectedDate)
      .then((detail) => alive && setDigest(detail))
      .catch((error) => {
        if (!alive) {
          return;
        }
        setDigest(null);
        // 「日报不存在」= 日期在存档之外（含未来日期）；其余才是加载失败
        if (error instanceof Error && error.message.includes(DAILY_MISSING_MESSAGE)) {
          setMissing(true);
        } else {
          setDetailFailed(true);
        }
      });
    return () => {
      alive = false;
    };
  }, [selectedDate, malformed, reloadKey]);

  // 按期 document.title：骑 #231 FeedShell title 单源机制（title prop 进壳，
  // 壳内 usePageTitle 统一汇聚「页面名 · PolyUGuide」，不另写 effect）
  const pageTitle = useMemo(() => {
    const date = digest?.digestDate ?? selectedDate;
    return date
      ? { zh: `理大资讯日报 · ${date}`, en: `PolyU Daily Digest · ${date}` }
      : { zh: "日报", en: "Daily digest" };
  }, [digest, selectedDate]);

  // canonical（对齐已提交 IndexNow/sitemap 的 URL 面）：/daily 指向 /daily、
  // 深链指向自身；不合式 key 走 404（noindex），不注入 canonical
  const canonicalHref =
    typeof window !== "undefined" && !malformed
      ? `${window.location.origin}${routeDate ? `/daily/${routeDate}` : "/daily"}`
      : null;
  useHeadElement("link", canonicalHref ? { rel: "canonical", href: canonicalHref } : null);

  // 期级 feed autodiscovery（#243 订阅出口默认口径）：阅读器/聚合器可自动发现
  // 「订阅日报」——复用 #231 同款 head helper，不另起机制；绝对 URL 供阅读器直取
  useHeadElement("link", {
    rel: "alternate",
    type: "application/rss+xml",
    title: "理大资讯日报 | PolyU Daily Digest",
    href:
      typeof window !== "undefined"
        ? `${window.location.origin}${dailyIssuesFeedUrl()}`
        : dailyIssuesFeedUrl()
  });

  const derived = useMemo(() => (digest ? deriveIssue(digest) : null), [digest]);

  if (malformed) {
    return <NotFoundPage />;
  }

  const retry = () => {
    setListFailed(false);
    setDetailFailed(false);
    setReloadKey((key) => key + 1);
  };

  // 主栏状态机（#272）：成功=正文（digest 属当前所选日期才渲染——切期后
  // effect 执行前的帧里旧 digest 一律按等待处理，不与所选新日期混配）；
  // 其余=缺期/详情失败/等待骨架。目录三态在壳层分支表达。
  const renderMainColumn = (catalog: NewsDailyDigestSummary[]) => {
    if (digest && derived && digest.digestDate === selectedDate) {
      return (
        <IssueBody
          digest={digest}
          derived={derived}
          summaries={catalog}
          onOpenToc={() => setTocOpen(true)}
        />
      );
    }
    if (missing) {
      return <MissingIssueCard summaries={catalog} />;
    }
    if (detailFailed) {
      return <FailureCard onRetry={retry} />;
    }
    return <DigestSkeleton />;
  };

  return (
    // 报头 Masthead 自带 h1——pageHeading 不开（#231：正文有内容头的页面由正文出 h1）
    <FeedShell title={pageTitle}>
      {summaries === null && listFailed ? (
        // 目录失败（#272 分开表达）：非深链没有详情流可等，整页一张目录失败卡；
        // 深链详情请求仍在飞——目录位失败卡+主栏照常状态机，互不吞错
        selectedDate === null ? (
          <FailureCard onRetry={retry} title={LIST_FAILURE_TITLE} />
        ) : (
          <div className="grid gap-6 min-[861px]:grid-cols-[236px_minmax(0,1fr)]">
            <FailureCard onRetry={retry} title={LIST_FAILURE_TITLE} />
            <div className="min-w-0">{renderMainColumn([])}</div>
          </div>
        )
      ) : summaries === null ? (
        // 目录等待（首进/深链首帧，#272）：rail 位+主栏骨架，等待期可见；
        // 目录到达后 rail 保持可操作，切期只重挂主栏
        <div className="grid gap-6 min-[861px]:grid-cols-[236px_minmax(0,1fr)]">
          <RailSkeleton />
          <div className="min-w-0">
            <DigestSkeleton />
          </div>
        </div>
      ) : summaries.length === 0 ? (
        <div className="rounded-2xl border border-dashed border-[var(--feed-line)] bg-[var(--feed-card)] p-7 text-center text-[13px] text-[var(--feed-text-tertiary)]">
          {zh ? "日报尚未生成（每日 08:40 HKT 出刊）" : "No issue yet — the digest is published daily at 08:40 HKT"}
        </div>
      ) : (
        <div className="grid gap-6 min-[861px]:grid-cols-[236px_minmax(0,1fr)]">
          <DesktopRail summaries={summaries} selectedDate={selectedDate ?? summaries[0].digestDate} />
          <div className="min-w-0">
            {renderMainColumn(summaries)}
            {digest && derived && digest.digestDate === selectedDate && (
              <TocDrawer
                open={tocOpen}
                onClose={() => setTocOpen(false)}
                digest={digest}
                derived={derived}
                summaries={summaries}
                selectedDate={selectedDate ?? summaries[0].digestDate}
              />
            )}
          </div>
        </div>
      )}
      <FeedFooter compact />
    </FeedShell>
  );
}

import type { HotRankEntry, NewsCategory, NewsItem, NewsSource, NewsTopic } from "@/types/news";
import { NEWS_SOURCES, NEWS_TOPICS } from "@/services/newsMockData";

/**
 * 真数据映射层：/public/news/** VO → 前端类型。
 * - 后端 VO（NewsItemVO/NewsHotRankEntryVO/NewsTopicVO）为 camel 双语字段，但
 *   publishTime 是原始时间串、source 是元数据、topicGroup 是字符串枚举——
 *   HKT 展示件（publishDate/publishTime/dayLabel）、信源五色徽章、三维分组号
 *   在本层归一，页面组件零感知（mock 与真数据同形）。
 * - 时区口径=HKT：日期切日、今天/昨天判定、时间显示均 Asia/Hong_Kong。
 */

/** 后端 NewsItemVO 形状（实测契约；topics/clusterSourceCount 可空） */
export interface NewsItemVO {
  id: number;
  url: string;
  titleZh: string | null;
  titleEn: string | null;
  summaryZh: string | null;
  summaryEn: string | null;
  category: string;
  publishTime: string | null;
  /** #275：date/datetime/unknown（后端同名列）；date=publishTime 为 23:59:59 归期代表值 */
  publishTimePrecision?: string | null;
  heat: number | null;
  clusterSourceCount?: number | null;
  topics?: string[] | null;
  source: {
    sourceKey: string;
    platform: string;
    official: boolean | null;
    displayName: string | null;
    displayNameEn: string | null;
  } | null;
}

export interface NewsPageVO {
  records: NewsItemVO[];
  total: number;
  page: number;
  size: number;
  hasMore: boolean;
}

export interface NewsHotRankEntryVO {
  itemId: number;
  titleZh: string | null;
  titleEn: string | null;
  heat: number | null;
  tags: string[];
  sources: string[];
}

export interface NewsTopicVO {
  slug: string;
  nameZh: string;
  nameEn: string | null;
  topicGroup: string;
  descriptionZh: string | null;
  descriptionEn: string | null;
  itemCount: number;
}

/** 主题详情载荷（/public/news/topic/{slug}；计数/更新时间/条目同源） */
export interface NewsTopicDetailVO {
  topic: NewsTopicVO;
  /** 主题内最近一条发布时刻（ISO-8601 串；空主题 null） */
  lastPublishTime: string | null;
  items: NewsPageVO;
}

const HKT = "Asia/Hong_Kong";
const FALLBACK_SOURCE_COLOR = "#6B7280";

/** HKT 日期键（YYYY-MM-DD，en-CA 产稳定 ISO 形状；灰条判定同源） */
export function hktDateKey(date: Date): string {
  return date.toLocaleDateString("en-CA", { timeZone: HKT });
}

/** HKT 时钟显示（HH:mm 24h）；导出供映射单测直接验边界 */
export function hktClockSafe(date: Date): string {
  return date.toLocaleTimeString("en-GB", { timeZone: HKT, hour: "2-digit", minute: "2-digit", hour12: false });
}

const WEEKDAYS_ZH = ["日", "一", "二", "三", "四", "五", "六"];
const WEEKDAYS_EN = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
/** 英文月名（feed 日期标签与日报月标签共用；#259 起导出，双组件不再各写一份） */
export const MONTHS_EN = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** 分类中文短标签（卡片 c-cat 徽章文案；#259 自 newsMockData 迁入——生产词表不再落 mock 模块名下） */
export const NEWS_CATEGORY_LABELS_ZH: Record<NewsCategory, string> = {
  admission: "招生",
  scholarship: "奖学金",
  research: "科研",
  campus: "校园",
  event: "活动",
  career: "就业",
  exchange: "交流",
  admin: "公告",
  other: "其他"
};

/** 分类英文短标签（卡片 c-cat 徽章 EN 文案；与 chips labelEn 对齐） */
export const NEWS_CATEGORY_LABELS_EN: Record<NewsCategory, string> = {
  admission: "Admissions",
  scholarship: "Scholarships",
  research: "Research",
  campus: "Campus",
  event: "Events",
  career: "Careers",
  exchange: "Exchange",
  admin: "Notices",
  other: "Other"
};

/**
 * 平台基色（信源徽章单源，#259）：日报 SourceDot 的取色入口——与 mock 注册表
 * NEWS_SOURCES 同值集（注册表仅剩 mock 语义、该模块计划退役故不反向依赖）；
 * 新平台接入只改此处；未知平台兜底=品牌红（原 SourceDot 三目链默认支口径）
 */
export const NEWS_PLATFORM_COLORS: Record<string, string> = {
  official: "#A6192E",
  events: "#7C3AED",
  youtube: "#FF0000",
  prn: "#0F766E",
  /** #277 媒体/政府官网自有平台（official=false 的媒体徽章色） */
  media: "#2563EB"
};
export const NEWS_PLATFORM_COLOR_DEFAULT = "#A6192E";

/** digestDate（YYYY-MM-DD）→ 日报月键（YYYY-MM）；目录日期均为 HKT 出刊日（#259 两实现合一） */
export const dailyMonthKey = (ds: string) => ds.slice(0, 7);

/** 日报月标签（「2026年10月」/「Oct 2026」） */
export const dailyMonthLabel = (key: string, zh: boolean) => {
  const [y, m] = key.split("-");
  return zh ? `${y}年${Number(m)}月` : `${MONTHS_EN[Number(m) - 1]} ${y}`;
};

/** 今天（HKT 口径，与刊期时区一致）：YYYY-MM-DD——hktDateKey 的当日快捷方式 */
export function hktTodayKey(): string {
  return hktDateKey(new Date());
}

/**
 * HKT 日键 → HKT 日历分量（月/日/周/年）。正午 HKT 恒等于 04:00Z 同一历日，
 * 故对该 instant 取 **UTC** 分量即 HKT 分量；禁用本地 getter——美洲时区
 * （UTC-4 以西）下正午 HKT 落在前一本地日，getMonth/getDate 会整体偏一天
 * （与 dayDiff「今天/昨天」前缀自相矛盾）。
 */
function hktCalendarParts(dateKey: string): { year: number; month: number; day: number; weekday: number } {
  const noon = new Date(`${dateKey}T12:00:00+08:00`);
  return {
    year: noon.getUTCFullYear(),
    month: noon.getUTCMonth(),
    day: noon.getUTCDate(),
    weekday: noon.getUTCDay()
  };
}

/** 日期分组标签（原型口径）：今天/昨天带前后缀，更早=「9月8日 周二」/「Tue 8 Sep」 */
export function dayLabels(publishDateKey: string, todayKey: string): { zh: string; en: string } {
  const { month, day, weekday } = hktCalendarParts(publishDateKey);
  const zhDate = `${month + 1}月${day}日 周${WEEKDAYS_ZH[weekday]}`;
  const enDate = `${WEEKDAYS_EN[weekday]} ${day} ${MONTHS_EN[month]}`;
  const dayDiff = Math.round(
    (Date.parse(`${publishDateKey}T00:00:00+08:00`) - Date.parse(`${todayKey}T00:00:00+08:00`)) / 86400000
  );
  if (dayDiff === 0) {
    return { zh: `今天 · ${zhDate}`, en: `Today · ${enDate}` };
  }
  if (dayDiff === -1) {
    return { zh: `昨天 · ${zhDate}`, en: `Yesterday · ${enDate}` };
  }
  return { zh: zhDate, en: enDate };
}

/**
 * 「数据更新至」统计位（主题详情页；格式对齐原型定版「9月10日 14:22」/「10 Sep 14:22」）。
 * 日期部分经 hktDateKey 固定日键后取 HKT 日历分量（正午 HKT=04:00Z，UTC getter 恒等于
 * HKT 分量），时钟部分 hktClockSafe 本就钉 Asia/Hong_Kong——全链路无本地时区依赖。
 */
export function formatUpdatedLabel(date: Date, lang: "zh" | "en"): string {
  const { month, day } = hktCalendarParts(hktDateKey(date));
  const clock = hktClockSafe(date);
  return lang === "zh"
    ? `数据更新至 ${month + 1}月${day}日 ${clock}`
    : `Updated ${day} ${MONTHS_EN[month]} ${clock}`;
}

/**
 * 顶栏实时日期（替换 newsMockData 写死常量）：桌面长形态
 * 「9月13日 · 周日 · 2026」/「Sun · 13 Sep 2026」、移动短形态「9月13日 · 周日」/「13 Sep · Sun」
 * ——形状逐字承接原 mock 定版常量；日期部分经 hktDateKey 固定日键后取 HKT 日历分量
 * （同 formatUpdatedLabel 口径，全时区不漂）。
 */
export function feedDateLabels(date: Date, lang: "zh" | "en"): { long: string; short: string } {
  const { year, month, day, weekday } = hktCalendarParts(hktDateKey(date));
  const weekdayZh = `周${WEEKDAYS_ZH[weekday]}`;
  const weekdayEn = WEEKDAYS_EN[weekday];
  if (lang === "zh") {
    return { long: `${month + 1}月${day}日 · ${weekdayZh} · ${year}`, short: `${month + 1}月${day}日 · ${weekdayZh}` };
  }
  return {
    long: `${weekdayEn} · ${day} ${MONTHS_EN[month]} ${year}`,
    short: `${day} ${MONTHS_EN[month]} · ${weekdayEn}`
  };
}

function mapSource(vo: NewsItemVO["source"]): NewsSource {
  if (!vo) {
    return { sourceKey: "unknown", platform: "official", labelZh: "未知来源", labelEn: "Unknown source", color: FALLBACK_SOURCE_COLOR, official: false };
  }
  const registered = NEWS_SOURCES[vo.sourceKey];
  if (registered) {
    return registered;
  }
  // 注册表外新信源（后续加 t_news_source 行即出现）：以库内双语名兜底、灰点中性色
  return {
    sourceKey: vo.sourceKey,
    platform: (vo.platform as NewsSource["platform"]) || "official",
    labelZh: vo.displayName || vo.sourceKey,
    labelEn: vo.displayNameEn || vo.displayName || vo.sourceKey,
    color: FALLBACK_SOURCE_COLOR,
    official: vo.official ?? false
  };
}

export function mapNewsItem(vo: NewsItemVO, now: Date = new Date()): NewsItem {
  const publish = vo.publishTime ? new Date(vo.publishTime) : now;
  const publishDateKey = hktDateKey(publish);
  const labels = dayLabels(publishDateKey, hktDateKey(now));
  // #275：date 精度=publish_time 是 23:59:59 归期代表值——时钟显示置空，只显日期
  const precision = (vo.publishTimePrecision ?? "unknown") as NewsItem["publishTimePrecision"];
  return {
    id: String(vo.id),
    url: vo.url,
    category: (vo.category as NewsItem["category"]) || "other",
    topics: vo.topics ?? [],
    heat: vo.heat ?? 0,
    publishDate: publishDateKey,
    publishTimePrecision: precision,
    publishTime: precision === "date" ? "" : hktClockSafe(publish),
    dayLabelZh: labels.zh,
    dayLabelEn: labels.en,
    source: mapSource(vo.source),
    titleZh: vo.titleZh ?? vo.titleEn ?? "",
    titleEn: vo.titleEn ?? vo.titleZh ?? "",
    summaryZh: vo.summaryZh ?? "",
    summaryEn: vo.summaryEn ?? "",
    clusterSourceCount: vo.clusterSourceCount ?? undefined
  };
}

export function mapHotEntry(vo: NewsHotRankEntryVO): HotRankEntry {
  return {
    titleZh: vo.titleZh ?? vo.titleEn ?? "",
    titleEn: vo.titleEn ?? vo.titleZh ?? "",
    heat: vo.heat ?? 0,
    tags: (vo.tags as HotRankEntry["tags"]) ?? [],
    sources: vo.sources ?? [],
    // 代表条目 id：榜单行点击入详情（2026-09-12 修复）
    itemId: vo.itemId
  };
}

/** topicGroup 字符串枚举 → 三维分组号（t_news_topic 种子口径） */
function topicGroupIndex(topicGroup: string): NewsTopic["group"] {
  switch (topicGroup) {
    case "FACULTY":
      return 0;
    case "RESEARCH":
      return 1;
    default:
      return 2;
  }
}

export function mapTopic(vo: NewsTopicVO): NewsTopic {
  // 图标只存于前端注册表（原型分组 1/2 主题带 icon）；库内无此列，按 slug 回填
  const registered = NEWS_TOPICS.find((topic) => topic.slug === vo.slug);
  return {
    slug: vo.slug,
    nameZh: vo.nameZh,
    nameEn: vo.nameEn ?? vo.nameZh,
    descZh: vo.descriptionZh ?? "",
    descEn: vo.descriptionEn ?? "",
    itemCount: vo.itemCount ?? 0,
    group: topicGroupIndex(vo.topicGroup),
    icon: registered?.icon
  };
}

/** ==================== 日报（#212）：/public/news/daily/** VO → 前端类型 ==================== */

import type { NewsDailyDigest, NewsDailyDigestItem, NewsDailyDigestSummary } from "@/types/news";

/** 后端 NewsDailyDigestItemVO 形状 */
export interface NewsDailyDigestItemVO {
  itemId: number;
  seq: number;
  url: string;
  titleZh: string | null;
  titleEn: string | null;
  summaryZh: string | null;
  summaryEn: string | null;
  category: string;
  topics?: string[] | null;
  publishTime: string | null;
  publishTimePrecision?: string | null;
  source: NewsItemVO["source"];
}

/** 后端 NewsDailyDigestVO 形状 */
export interface NewsDailyDigestVO {
  digestDate: string;
  windowStart: string;
  windowEnd: string;
  introZh: string | null;
  introEn: string | null;
  storedIntroSource: string;
  introDegraded: boolean;
  itemCount: number;
  visibleCount: number;
  disqualifiedCount: number;
  items: NewsDailyDigestItemVO[];
  buildTime: string;
}

/** 后端 NewsDailyDigestSummaryVO 形状 */
export interface NewsDailyDigestSummaryVO {
  digestDate: string;
  itemCount: number;
  introSource: string;
  buildTime: string;
  /** #240 目录首条标题字段（空期=null） */
  firstTitleZh?: string | null;
  firstTitleEn?: string | null;
}

export function mapDailyDigestSummary(vo: NewsDailyDigestSummaryVO): NewsDailyDigestSummary {
  return {
    digestDate: vo.digestDate,
    itemCount: vo.itemCount ?? 0,
    introSource: vo.introSource,
    buildTime: vo.buildTime,
    firstTitleZh: vo.firstTitleZh ?? null,
    firstTitleEn: vo.firstTitleEn ?? null
  };
}

/** 快照条目直映（快照列即展示字段，与 t_news_item 现值无关） */
export function mapDailyDigestItem(vo: NewsDailyDigestItemVO): NewsDailyDigestItem {
  return {
    itemId: vo.itemId,
    seq: vo.seq,
    url: vo.url,
    titleZh: vo.titleZh,
    titleEn: vo.titleEn,
    summaryZh: vo.summaryZh,
    summaryEn: vo.summaryEn,
    category: (vo.category as NewsDailyDigestItem["category"]) || "other",
    topics: vo.topics ?? [],
    publishTime: vo.publishTime,
    publishTimePrecision: (vo.publishTimePrecision ?? "unknown") as NewsDailyDigestItem["publishTimePrecision"],
    source: vo.source ?? null
  };
}

export function mapDailyDigest(vo: NewsDailyDigestVO): NewsDailyDigest {
  return {
    digestDate: vo.digestDate,
    windowStart: vo.windowStart,
    windowEnd: vo.windowEnd,
    introZh: vo.introZh ?? "",
    introEn: vo.introEn ?? "",
    storedIntroSource: vo.storedIntroSource,
    introDegraded: vo.introDegraded ?? false,
    itemCount: vo.itemCount ?? 0,
    visibleCount: vo.visibleCount ?? 0,
    disqualifiedCount: vo.disqualifiedCount ?? 0,
    items: (vo.items ?? []).map((item) => mapDailyDigestItem(item)),
    buildTime: vo.buildTime
  };
}

/**
 * 日报快照条目 → NewsCard 的 NewsItem 形（复用资讯流卡片）：
 * 时钟/日期标签按快照 publishTime 以 HKT 归一（同 mapNewsItem 口径）；
 * heat=0（快照无热度语义），已读标记以 itemId 为键与资讯流互通。
 */
export function digestItemToNewsItem(item: NewsDailyDigestItem, now: Date = new Date()): NewsItem {
  const publish = item.publishTime ? new Date(item.publishTime) : now;
  const publishDateKey = hktDateKey(publish);
  const labels = dayLabels(publishDateKey, hktDateKey(now));
  // #275：date 精度=快照时刻是归期代表值——时钟显示置空
  const precision = item.publishTimePrecision ?? "unknown";
  const sourceVo: NewsItemVO["source"] = item.source
    ? {
        sourceKey: item.source.sourceKey,
        platform: item.source.platform,
        official: item.source.official,
        displayName: item.source.displayName,
        displayNameEn: item.source.displayNameEn
      }
    : null;
  return {
    id: String(item.itemId),
    url: item.url,
    category: item.category,
    topics: item.topics,
    heat: 0,
    publishDate: publishDateKey,
    publishTimePrecision: precision,
    publishTime: precision === "date" ? "" : hktClockSafe(publish),
    dayLabelZh: labels.zh,
    dayLabelEn: labels.en,
    source: mapSource(sourceVo),
    titleZh: item.titleZh ?? item.titleEn ?? "",
    titleEn: item.titleEn ?? item.titleZh ?? "",
    summaryZh: item.summaryZh ?? "",
    summaryEn: item.summaryEn ?? ""
  };
}

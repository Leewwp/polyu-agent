import type { HotRankEntry, NewsItem, NewsSource, NewsTopic } from "@/types/news";
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
const MONTHS_EN = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

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
  return {
    id: String(vo.id),
    url: vo.url,
    category: (vo.category as NewsItem["category"]) || "other",
    topics: vo.topics ?? [],
    heat: vo.heat ?? 0,
    publishDate: publishDateKey,
    publishTime: hktClockSafe(publish),
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

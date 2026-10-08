import axios from "axios";

import type { KeyDateBoard, KeyDateItem } from "@/types/keyDate";
import { MOCK_KEY_DATE_BOARD } from "@/services/keyDateMockData";

const CALENDAR_API_BASE_URL = import.meta.env.VITE_API_BASE_URL || "";

/**
 * 公开校历数据专用 axios 实例（newsApi 同范式，与 api.ts 物理隔离）：
 * 不注入 Authorization、不挂 401 硬跳——匿名读 /public/calendar/**；
 * 拦截只做 Results 信封解包，失败原样 reject，页面自渲染错误/空态。
 * flag rag.calendar.enabled 关（默认）时后端 404——本层 reject，卡片隐藏/页面降级。
 */
export const calendarApi = axios.create({
  baseURL: CALENDAR_API_BASE_URL,
  timeout: 15000
});

calendarApi.interceptors.response.use(
  (response) => {
    const payload = response.data;
    if (payload && typeof payload === "object" && "code" in payload) {
      if (payload.code !== "0") {
        return Promise.reject(new Error(payload.message || "校历加载失败"));
      }
      return payload.data;
    }
    return payload;
  },
  (error) => Promise.reject(error)
);

/** mock 先行；vitest 的 MODE="test" 自动回落 mock 分支（newsService 同判例） */
const USE_MOCK = import.meta.env.MODE === "test" || import.meta.env.VITE_CALENDAR_USE_MOCK === "1";

/**
 * 关键日期看板（独立查询口唯一出参形状；首页卡片取 currentAndUpcoming 前 K
 * 条即同一序）。daysUntil/phase/today 均为后端 HKT 锚点计算结果——前端零
 * 日期运算，不因客户端时区漂移。
 */
export async function fetchKeyDateBoard(): Promise<KeyDateBoard> {
  if (USE_MOCK) {
    return MOCK_KEY_DATE_BOARD;
  }
  return calendarApi.get<KeyDateBoard, KeyDateBoard>("/public/calendar/key-dates");
}

/** 首页卡片条数（前 K 条=临近度序最前的当前/即将事件；HotPanel Top5 同量级口径） */
export const KEY_DATE_CARD_LIMIT = 4;

const WEEKDAYS_ZH = ["日", "一", "二", "三", "四", "五", "六"];
const WEEKDAYS_EN = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
const MONTHS_EN = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/**
 * ISO 日期键（YYYY-MM-DD）→ 日历分量：经正午 +08:00 构造再取 UTC 分量
 * （正午 HKT=04:00Z 同一历日——newsMapping.hktCalendarParts 同判例，
 * 禁用本地 getter 防时区漂移）。
 */
function isoParts(isoDate: string): { month: number; day: number; weekday: number; year: number } {
  const noon = new Date(`${isoDate}T12:00:00+08:00`);
  return {
    year: noon.getUTCFullYear(),
    month: noon.getUTCMonth(),
    day: noon.getUTCDate(),
    weekday: noon.getUTCDay()
  };
}

/** 单日标签：9月30日 周三 / Wed 30 Sep */
function dayLabel(isoDate: string, zh: boolean): string {
  const { month, day, weekday } = isoParts(isoDate);
  return zh ? `${month + 1}月${day}日 周${WEEKDAYS_ZH[weekday]}` : `${WEEKDAYS_EN[weekday]} ${day} ${MONTHS_EN[month]}`;
}

/** 紧凑日期（区间侧/卡片行）：9月30日 / 30 Sep */
function dayCompact(isoDate: string, zh: boolean): string {
  const { month, day } = isoParts(isoDate);
  return zh ? `${month + 1}月${day}日` : `${day} ${MONTHS_EN[month]}`;
}

/**
 * 日期呈现（纯函数，卡片/页面/测试共用）：keyDateLabel 只取
 * precision/dateStart/dateEnd/fuzzyHint 结构子集——日报校历栏目快照
 * （#316）同形直入，不要求 KeyDateItem 全字段：
 * - exact-day：9月30日 周三；exact-range：9月28日 – 10月4日；onwards：10月12日起
 *   （开放起点=官方真实日期，非伪造）；fuzzy：原文窗桶（不伪造具体日）。
 */
export function keyDateLabel(
  item: Pick<KeyDateItem, "precision" | "dateStart" | "dateEnd" | "fuzzyHint">,
  lang: "zh" | "en"
): string {
  const zh = lang === "zh";
  if (item.precision === "fuzzy" || !item.dateStart) {
    return item.fuzzyHint || (zh ? "日期待公布" : "Date to be announced");
  }
  if (item.precision === "exact-range" && item.dateEnd) {
    return `${dayCompact(item.dateStart, zh)} – ${dayCompact(item.dateEnd, zh)}`;
  }
  if (item.precision === "onwards") {
    return `${dayCompact(item.dateStart, zh)}${zh ? " 起" : " onwards"}`;
  }
  return dayLabel(item.dateStart, zh);
}

/**
 * 倒计时徽章文案（纯函数）：今日/进行中/明天/N 天后。只取 phase/daysUntil
 * 结构子集（#316 日报栏目快照按 ongoing/daysUntil 派生 phase 后同入口复用）。
 * 倒计时门（合同§4）——仅 exact-day/exact-range（daysUntil 非 null）进倒计时；
 * onwards/fuzzy 返回 null（不伪造精确截止语义），页面/卡片按普通日期行呈现。
 */
export function countdownBadge(
  item: Pick<KeyDateItem, "phase" | "daysUntil">,
  lang: "zh" | "en"
): string | null {
  const zh = lang === "zh";
  if (item.phase === "today") {
    return zh ? "今日" : "Today";
  }
  if (item.phase === "ongoing") {
    return zh ? "进行中" : "Ongoing";
  }
  if (item.phase !== "upcoming" || item.daysUntil == null) {
    return null;
  }
  if (item.daysUntil === 1) {
    return zh ? "明天" : "Tomorrow";
  }
  return zh ? `${item.daysUntil} 天后` : `in ${item.daysUntil} days`;
}

/** HKT 墙钟串（后端 LocalDateTime ISO 无时区后缀=HKT 墙时）→ 9月29日 07:31 / 29 Sep 07:31 */
export function formatSyncTime(iso: string | null, lang: "zh" | "en"): string {
  if (!iso) {
    return lang === "zh" ? "未同步" : "never synced";
  }
  const [date, time = ""] = iso.split("T");
  const { month, day } = isoParts(date);
  const clock = time.slice(0, 5);
  return lang === "zh" ? `${month + 1}月${day}日 ${clock}` : `${day} ${MONTHS_EN[month]} ${clock}`;
}

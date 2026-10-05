import type { NewsDailyDigest } from "@/types/news";

/**
 * 已看期会话缓存（#273）：同一 tab 文档存活期内复用最近看过的日报详情，
 * 刷新即清空（模块重载），不写 localStorage/sessionStorage、不预取存档。
 *
 * - plain Map 实现 LRU=10：Map 迭代序=插入序，命中 delete+set 提升为最近
 *   使用，超限逐出首键（最久未使用，不是日期最早/最早插入）；
 * - 仅「新鲜成功详情」入缓存：不存在、业务错误、请求失败都不缓存；
 * - 新鲜度与 C（#274）的 HTTP 缓存共用同一预算：按源响应
 *   Date/Age/Cache-Control 计算剩余寿命（RFC 9111 §4.2.3 简化——
 *   年龄取 apparent/Age 较大者+驻留时间，剩余= max-age−年龄），
 *   浏览器已存活 299s 的副本在内存里只剩约 1s，不给旧副本再续 300s；
 * - 命中提升使用顺序但**不续期**（expiresAt 不变）；buildTime 是刊物
 *   生成时间，不充当 HTTP 新鲜度；
 * - 元数据缺失或无法解释（无 max-age、no-store/no-cache、Date 与 Age
 *   全缺、剩余 ≤0）一律不建可复用内存项。
 */

/** LRU 容量上限（含当前期；#273 票面定值） */
export const DAILY_CACHE_MAX = 10;

/** 响应级 HTTP 新鲜度元数据（newsApi 拦截器在解包产物上挂的 symbol 键） */
export const HTTP_RESPONSE_META: unique symbol = Symbol("httpResponseMeta");

export interface HttpResponseMeta {
  /** 响应到达（拦截器执行）时刻，epoch ms */
  responseTime: number;
  /** Date 头解析值；缺失/不可解析为 null */
  dateHeaderMs: number | null;
  /** Age 头秒数；缺失/不可解析为 null */
  ageHeaderSec: number | null;
  /** Cache-Control 头原值；缺失为 null */
  cacheControl: string | null;
}

interface CacheEntry {
  value: NewsDailyDigest;
  /** epoch ms；命中不续期 */
  expiresAt: number;
}

const cache = new Map<string, CacheEntry>();

/**
 * 解析 Cache-Control 的 max-age 秒数；无 max-age、值非正数或头含
 * no-store/no-cache（需验证的响应不适合作确定新鲜度依据）返回 null。
 */
export function parseMaxAgeSec(cacheControl: string | null): number | null {
  if (!cacheControl || /no-store|no-cache/i.test(cacheControl)) {
    return null;
  }
  const match = /max-age\s*=\s*(\d+)/i.exec(cacheControl);
  if (!match) {
    return null;
  }
  const seconds = Number(match[1]);
  return Number.isFinite(seconds) && seconds > 0 ? seconds : null;
}

/**
 * 计算源响应在 now 时刻的剩余新鲜寿命（ms）。无法解释或已不新鲜返回
 * null——调用方不得建立可复用内存项。
 *
 * 年龄口径（RFC 9111 §4.2.3 保守简化）：
 * apparent_age=max(0, 响应到达−Date)；corrected_initial_age=
 * max(apparent_age, Age)；current_age=corrected_initial_age+驻留时间。
 */
export function computeRemainingFreshness(meta: HttpResponseMeta, now: number = Date.now()): number | null {
  const maxAgeSec = parseMaxAgeSec(meta.cacheControl);
  if (maxAgeSec == null) {
    return null;
  }
  if (meta.dateHeaderMs == null && meta.ageHeaderSec == null) {
    return null;
  }
  const apparentAgeSec = meta.dateHeaderMs != null
    ? Math.max(0, (meta.responseTime - meta.dateHeaderMs) / 1000)
    : 0;
  const correctedInitialAgeSec = Math.max(apparentAgeSec, meta.ageHeaderSec ?? 0);
  const residentSec = Math.max(0, (now - meta.responseTime) / 1000);
  const remainingMs = (maxAgeSec - (correctedInitialAgeSec + residentSec)) * 1000;
  return remainingMs > 0 ? remainingMs : null;
}

/**
 * 查缓存：命中且仍新鲜返回值并提升 LRU 顺序（不续期）；过期项惰性除名
 * 返回 null；未命中返回 null。
 */
export function peekCachedDigest(digestDate: string, now: number = Date.now()): NewsDailyDigest | null {
  const entry = cache.get(digestDate);
  if (!entry) {
    return null;
  }
  if (entry.expiresAt <= now) {
    cache.delete(digestDate);
    return null;
  }
  cache.delete(digestDate);
  cache.set(digestDate, entry);
  return entry.value;
}

/**
 * 写缓存：仅新鲜成功详情（remainingMs 有效）入缓存；命中/重写提升为
 * 最近使用，超上限逐出最久未使用项。
 */
export function storeCachedDigest(
  digestDate: string,
  value: NewsDailyDigest,
  remainingMs: number | null,
  now: number = Date.now()
): void {
  if (remainingMs == null || remainingMs <= 0) {
    return;
  }
  cache.delete(digestDate);
  cache.set(digestDate, { value, expiresAt: now + remainingMs });
  while (cache.size > DAILY_CACHE_MAX) {
    const oldest = cache.keys().next().value;
    if (oldest === undefined) {
      break;
    }
    cache.delete(oldest);
  }
}

/** 清空缓存（刷新语义由模块重载天然达成；测试与显式清场用） */
export function clearDailyDigestCache(): void {
  cache.clear();
}

/** 读解包产物上的 HTTP 元数据（拦截器仅对对象形 data 挂载） */
export function readResponseMeta(data: unknown): HttpResponseMeta | null {
  if (!data || typeof data !== "object") {
    return null;
  }
  const meta = (data as Record<symbol, unknown>)[HTTP_RESPONSE_META];
  return meta ?? null;
}

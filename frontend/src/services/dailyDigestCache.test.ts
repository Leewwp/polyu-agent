import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  DAILY_CACHE_MAX,
  clearDailyDigestCache,
  computeRemainingFreshness,
  parseMaxAgeSec,
  peekCachedDigest,
  storeCachedDigest,
  type HttpResponseMeta
} from "./dailyDigestCache";
import type { NewsDailyDigest } from "@/types/news";

/** 最小可缓存详情（LRU/新鲜度测试不关心版面细节） */
function digest(date: string): NewsDailyDigest {
  return {
    digestDate: date,
    windowStart: `${date}T08:00:00+08:00`,
    windowEnd: `${date}T08:00:00+08:00`,
    introZh: "导语",
    introEn: "Intro",
    storedIntroSource: "llm",
    introDegraded: false,
    itemCount: 1,
    visibleCount: 1,
    disqualifiedCount: 0,
    items: [],
    buildTime: `${date}T08:40:00+08:00`
  };
}

function meta(overrides: Partial<HttpResponseMeta> = {}): HttpResponseMeta {
  return {
    responseTime: 1_000_000,
    dateHeaderMs: null,
    ageHeaderSec: null,
    cacheControl: "public, max-age=300",
    ...overrides
  };
}

describe("parseMaxAgeSec", () => {
  it("parses max-age from a public directive", () => {
    expect(parseMaxAgeSec("public, max-age=300")).toBe(300);
    expect(parseMaxAgeSec("max-age=60")).toBe(60);
  });

  it("rejects absent, zero, no-store and no-cache directives", () => {
    expect(parseMaxAgeSec(null)).toBeNull();
    expect(parseMaxAgeSec("public")).toBeNull();
    expect(parseMaxAgeSec("public, max-age=0")).toBeNull();
    expect(parseMaxAgeSec("no-store, max-age=300")).toBeNull();
    expect(parseMaxAgeSec("no-cache, max-age=300")).toBeNull();
  });
});

describe("computeRemainingFreshness", () => {
  it("a copy that already lived 299s of its 300s keeps only about 1s (#273 core)", () => {
    const remaining = computeRemainingFreshness(meta({ ageHeaderSec: 299 }), 1_000_000);
    expect(remaining).toBeGreaterThan(0);
    expect(remaining!).toBeLessThanOrEqual(1500);
  });

  it("derives age from the Date header when Age is absent", () => {
    // Date 头比响应到达早 250s → apparent age 250s，300s 预算剩 ~50s
    const remaining = computeRemainingFreshness(meta({ dateHeaderMs: 1_000_000 - 250_000 }), 1_000_000);
    expect(remaining).toBeGreaterThan(48_000);
    expect(remaining!).toBeLessThanOrEqual(50_000);
  });

  it("takes the larger of apparent age and Age header", () => {
    const remaining = computeRemainingFreshness(
      meta({ dateHeaderMs: 1_000_000 - 200_000, ageHeaderSec: 280 }),
      1_000_000
    );
    expect(remaining!).toBeGreaterThan(19_000);
    expect(remaining!).toBeLessThanOrEqual(21_000);
  });

  it("counts resident time since arrival", () => {
    const remaining = computeRemainingFreshness(meta({ ageHeaderSec: 0 }), 1_000_000 + 100_000);
    expect(remaining!).toBeGreaterThan(198_000);
    expect(remaining!).toBeLessThanOrEqual(200_000);
  });

  it("returns null when freshness cannot be established or already expired", () => {
    expect(computeRemainingFreshness(meta({ cacheControl: null }))).toBeNull();
    expect(computeRemainingFreshness(meta({ cacheControl: "no-store" }))).toBeNull();
    expect(computeRemainingFreshness(meta({ cacheControl: "public, max-age=300", dateHeaderMs: null, ageHeaderSec: null }))).toBeNull();
    expect(computeRemainingFreshness(meta({ ageHeaderSec: 301 }))).toBeNull();
  });
});

describe("daily digest LRU cache", () => {
  beforeEach(() => {
    clearDailyDigestCache();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("stores and hits within freshness without extending expiry", () => {
    const now = 5_000_000;
    storeCachedDigest("2026-10-03", digest("2026-10-03"), 300_000, now);
    expect(peekCachedDigest("2026-10-03", now + 299_000)?.digestDate).toBe("2026-10-03");
    // 命中不续期：到期时刻不因命中而后移
    expect(peekCachedDigest("2026-10-03", now + 301_000)).toBeNull();
  });

  it("evicts least-recently-used, not FIFO or date order (A→B→A then fill to 11)", () => {
    const now = 5_000_000;
    storeCachedDigest("2026-10-01", digest("2026-10-01"), 600_000, now); // A
    storeCachedDigest("2026-10-02", digest("2026-10-02"), 600_000, now); // B（更晚日期，但更久未使用）
    peekCachedDigest("2026-10-01", now + 1); // A 命中提升为最近使用
    // 再插入 8 期到上限 10，随后第 11 期触发逐出
    for (let i = 10; i <= 17; i++) {
      storeCachedDigest(`2026-10-${String(i).padStart(2, "0")}`, digest(`2026-10-${String(i).padStart(2, "0")}`), 600_000, now);
    }
    storeCachedDigest("2026-10-18", digest("2026-10-18"), 600_000, now);
    expect(peekCachedDigest("2026-10-18", now + 2)).not.toBeNull(); // 触发逐出的第 11 期
    expect(peekCachedDigest("2026-10-01", now + 2)).not.toBeNull(); // A 因命中提升而幸存
    expect(peekCachedDigest("2026-10-02", now + 2)).toBeNull(); // B=最久未使用，被逐出（非日期序/非纯 FIFO）
  });

  it("never creates entries without usable freshness", () => {
    storeCachedDigest("2026-10-03", digest("2026-10-03"), null);
    storeCachedDigest("2026-10-04", digest("2026-10-04"), 0);
    storeCachedDigest("2026-10-05", digest("2026-10-05"), -5);
    expect(peekCachedDigest("2026-10-03")).toBeNull();
    expect(peekCachedDigest("2026-10-04")).toBeNull();
    expect(peekCachedDigest("2026-10-05")).toBeNull();
  });

  it("caps at the documented limit including the current issue", () => {
    expect(DAILY_CACHE_MAX).toBe(10);
    const now = 5_000_000;
    for (let i = 1; i <= 10; i++) {
      storeCachedDigest(`2026-10-${String(i).padStart(2, "0")}`, digest(`2026-10-${String(i).padStart(2, "0")}`), 600_000, now);
    }
    // 不做中间命中（避免提升顺序干扰）：第 11 期直接触发逐出首键 10-01
    storeCachedDigest("2026-10-11", digest("2026-10-11"), 600_000, now); // 含当前期在内的第 11 期
    expect(peekCachedDigest("2026-10-01", now + 2)).toBeNull();
    expect(peekCachedDigest("2026-10-02", now + 2)).not.toBeNull();
    expect(peekCachedDigest("2026-10-11", now + 2)).not.toBeNull();
  });
});

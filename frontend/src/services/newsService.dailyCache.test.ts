import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { clearDailyDigestCache } from "./dailyDigestCache";
import type { NewsDailyDigestVO } from "./newsMapping";

/**
 * #273 fetchDailyDigest 会话缓存接线（真数据分支集成）：
 * vitest 的 MODE="test" 让 newsService 恒走 mock 分支——用 resetModules
 * + stubEnv("MODE") 动态重载模块拿到真数据分支，再以自定义 axios adapter
 * 假造带 HTTP 头的信封响应，走完整拦截器链（解包+元数据挂载）。
 * 断言外部行为：adapter 调用次数（零重取/过期重拉）、rejection 语义、
 * 不缓存面；缓存实现细节（Map 结构）不在本文件断言。
 */

const T0 = Date.parse("2026-10-05T10:00:00Z");

function vo(date = "2026-10-03"): NewsDailyDigestVO {
  return {
    digestDate: date,
    windowStart: "2026-10-02T08:00:00+08:00",
    windowEnd: "2026-10-03T08:00:00+08:00",
    introZh: "本期看点。",
    introEn: "Two things worth noting.",
    storedIntroSource: "llm",
    introDegraded: false,
    itemCount: 1,
    visibleCount: 1,
    disqualifiedCount: 0,
    items: [
      {
        itemId: 11,
        seq: 1,
        url: "https://www.polyu.edu.hk/a",
        titleZh: "研究突破甲",
        titleEn: "Research A",
        summaryZh: "摘要",
        summaryEn: "Summary",
        category: "research",
        topics: [],
        publishTime: "2026-10-03T09:00:00+08:00",
        source: {
          sourceKey: "official-media-release",
          platform: "official",
          official: true,
          displayName: "理大官网",
          displayNameEn: "PolyU official"
        }
      }
    ],
    buildTime: "2026-10-03T08:40:00+08:00"
  };
}

/** 假 adapter：记录调用次数，按脚本回信封（headers 即源响应头） */
function fakeAdapter(headers: Record<string, string>, body: unknown) {
  const calls = { count: 0 };
  const adapter = async (config: unknown) => {
    calls.count += 1;
    return {
      data: body,
      status: 200,
      statusText: "OK",
      headers,
      config
    };
  };
  return { adapter, calls };
}

const OK_ENVELOPE = (data: unknown) => ({ code: "0", data, message: null, requestId: null, success: true });

/** 动态加载真数据分支的 newsService（绕开 MODE=test 的 mock 分支） */
async function loadRealService(adapter: NonNullable<unknown>) {
  vi.resetModules();
  vi.stubEnv("MODE", "production");
  const mod = await import("./newsService");
  // @ts-expect-error 测试注入假 adapter（axios 适配器签名兼容）
  mod.newsApi.defaults.adapter = adapter;
  return mod;
}

describe("fetchDailyDigest session cache wiring (#273)", () => {
  beforeEach(() => {
    clearDailyDigestCache();
    vi.useFakeTimers({ now: T0 });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllEnvs();
    clearDailyDigestCache();
  });

  it("serves repeat views within freshness with zero refetch, bilingual object reused", async () => {
    const { adapter, calls } = fakeAdapter(
      { "cache-control": "public, max-age=300", date: new Date(T0).toUTCString() },
      OK_ENVELOPE(vo())
    );
    const mod = await loadRealService(adapter);

    const first = await mod.fetchDailyDigest("2026-10-03");
    expect(first.digestDate).toBe("2026-10-03");
    expect(calls.count).toBe(1);

    // 新鲜期内复看（模拟 100s 后、再模拟切走又切回）：零详情请求
    vi.setSystemTime(T0 + 100_000);
    const second = await mod.fetchDailyDigest("2026-10-03");
    expect(calls.count).toBe(1);
    // 复用双语对象（语言切换共享同一缓存条目）
    expect(second.items[0].titleZh).toBe("研究突破甲");
    expect(second.items[0].titleEn).toBe("Research A");
    expect(second).toBe(first);
  });

  it("re-fetches after the source copy's remaining freshness runs out (299s lived → ~1s left)", async () => {
    const { adapter, calls } = fakeAdapter(
      { "cache-control": "public, max-age=300", age: "299" },
      OK_ENVELOPE(vo())
    );
    const mod = await loadRealService(adapter);

    await mod.fetchDailyDigest("2026-10-03");
    expect(calls.count).toBe(1);

    // 浏览器/代理已交出的副本在内存缓存里只剩约 1s——2s 后必须重新请求，
    // 不给旧副本再续 300s
    vi.setSystemTime(T0 + 2_000);
    await mod.fetchDailyDigest("2026-10-03");
    expect(calls.count).toBe(2);
  });

  it("does not cache a missing issue (data=null envelope) and keeps re-asking", async () => {
    const { adapter, calls } = fakeAdapter(
      { "cache-control": "public, max-age=300", date: new Date(T0).toUTCString() },
      OK_ENVELOPE(null)
    );
    const mod = await loadRealService(adapter);

    await expect(mod.fetchDailyDigest("2026-08-01")).rejects.toThrow("日报不存在");
    await expect(mod.fetchDailyDigest("2026-08-01")).rejects.toThrow("日报不存在");
    expect(calls.count).toBe(2);
  });

  it("does not cache business failures and keeps re-asking", async () => {
    const { adapter, calls } = fakeAdapter(
      { "cache-control": "public, max-age=300", date: new Date(T0).toUTCString() },
      { code: "A000001", data: null, message: "资讯加载失败", requestId: null, success: false }
    );
    const mod = await loadRealService(adapter);

    await expect(mod.fetchDailyDigest("2026-10-03")).rejects.toThrow("资讯加载失败");
    await expect(mod.fetchDailyDigest("2026-10-03")).rejects.toThrow("资讯加载失败");
    expect(calls.count).toBe(2);
  });

  it("does not build a reusable memory entry when freshness metadata is unusable", async () => {
    // 无 Cache-Control 头（也无法定年龄）→ 无法解释新鲜度 → 不建缓存项
    const { adapter, calls } = fakeAdapter({}, OK_ENVELOPE(vo()));
    const mod = await loadRealService(adapter);

    await mod.fetchDailyDigest("2026-10-03");
    await mod.fetchDailyDigest("2026-10-03");
    expect(calls.count).toBe(2);
  });

  it("respects no-store responses as non-cacheable", async () => {
    const { adapter, calls } = fakeAdapter(
      { "cache-control": "no-store", date: new Date(T0).toUTCString() },
      OK_ENVELOPE(vo())
    );
    const mod = await loadRealService(adapter);

    await mod.fetchDailyDigest("2026-10-03");
    await mod.fetchDailyDigest("2026-10-03");
    expect(calls.count).toBe(2);
  });
});

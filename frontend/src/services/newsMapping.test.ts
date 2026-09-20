import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from "vitest";

import { dayLabels, feedDateLabels, formatUpdatedLabel, hktClockSafe, hktDateKey, mapHotEntry, mapNewsItem, mapTopic } from "./newsMapping";

/**
 * 真数据映射层单测：HKT 日期件（切日/今天昨天/普通日双语）、
 * 信源注册表命中与 miss 兜底、双语 fallback 链、topicGroup→分组号+icon 回填。
 */

// 固定「现在」：HKT 2026-09-11 21:30（UTC 13:30）
const NOW = new Date("2026-09-11T13:30:00Z");
const NOW_KEY = "2026-09-11";

afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe("dayLabels（HKT 口径）", () => {
  it("labels today and yesterday with prefix", () => {
    expect(dayLabels("2026-09-11", NOW_KEY)).toEqual({ zh: "今天 · 9月11日 周五", en: "Today · Fri 11 Sep" });
    expect(dayLabels("2026-09-10", NOW_KEY)).toEqual({ zh: "昨天 · 9月10日 周四", en: "Yesterday · Thu 10 Sep" });
  });

  it("labels older dates as plain date (zh/en)", () => {
    expect(dayLabels("2026-09-02", NOW_KEY)).toEqual({ zh: "9月2日 周三", en: "Wed 2 Sep" });
    expect(dayLabels("2026-08-31", NOW_KEY)).toEqual({ zh: "8月31日 周一", en: "Mon 31 Aug" });
  });
});

describe("hktDateKey（UTC→HKT 切日边界）", () => {
  it("keeps HKT date when UTC is previous day", () => {
    // UTC 2026-09-10 16:30 = HKT 2026-09-11 00:30
    expect(hktDateKey(new Date("2026-09-10T16:30:00Z"))).toBe("2026-09-11");
  });

  it("does not jump before HKT midnight", () => {
    // UTC 2026-09-10 15:59 = HKT 2026-09-10 23:59
    expect(hktDateKey(new Date("2026-09-10T15:59:00Z"))).toBe("2026-09-10");
  });
});

describe("formatUpdatedLabel（主题详情「数据更新至」统计位）", () => {
  it("formats zh/en per prototype fixed strings", () => {
    expect(formatUpdatedLabel(new Date("2026-09-10T06:22:00+08:00"), "zh")).toBe("数据更新至 9月10日 06:22");
    expect(formatUpdatedLabel(new Date("2026-09-10T06:22:00+08:00"), "en")).toBe("Updated 10 Sep 06:22");
  });

  it("follows the HKT day boundary for the date part", () => {
    // UTC 2026-09-10 16:00 = HKT 2026-09-11 00:00（线上 lastPublishTime 实测形态）
    expect(formatUpdatedLabel(new Date("2026-09-10T16:00:00Z"), "zh")).toBe("数据更新至 9月11日 00:00");
    expect(formatUpdatedLabel(new Date("2026-09-10T16:00:00Z"), "en")).toBe("Updated 11 Sep 00:00");
  });
});

describe("mapNewsItem", () => {
  const baseVo = {
    id: 52,
    url: "https://www.polyu.edu.hk/media/release/2026/0910/",
    titleZh: "中文标题",
    titleEn: "English title",
    summaryZh: "中文摘要",
    summaryEn: "English summary",
    category: "research",
    publishTime: "2026-09-10T06:22:00.000+00:00",
    heat: 4,
    clusterSourceCount: 2,
    topics: ["ai", "research"],
    source: {
      sourceKey: "official-media-release",
      platform: "official",
      official: true,
      displayName: "媒体发布",
      displayNameEn: "Media Releases"
    }
  };

  it("maps full VO with registered source and topics", () => {
    const item = mapNewsItem(baseVo, NOW);
    expect(item.id).toBe("52");
    expect(item.topics).toEqual(["ai", "research"]);
    expect(item.heat).toBe(4);
    expect(item.clusterSourceCount).toBe(2);
    // UTC 2026-09-10 06:22 = HKT 同日 14:22
    expect(item.publishDate).toBe("2026-09-10");
    expect(item.publishTime).toBe("14:22");
    expect(item.dayLabelZh).toBe("昨天 · 9月10日 周四");
    expect(item.source.labelZh).toBe("官网 · 媒体发布");
    expect(item.source.color).toBe("#A6192E");
  });

  it("falls back on missing bilingual fields, empty topics, and unregistered source", () => {
    const item = mapNewsItem(
      {
        ...baseVo,
        titleEn: null,
        summaryEn: null,
        topics: null,
        clusterSourceCount: null,
        publishTime: null,
        source: {
          sourceKey: "new-source-x",
          platform: "prn",
          official: false,
          displayName: "新信源",
          displayNameEn: "New Source"
        }
      },
      NOW
    );
    expect(item.titleEn).toBe("中文标题");
    expect(item.summaryEn).toBe("");
    expect(item.topics).toEqual([]);
    expect(item.clusterSourceCount).toBeUndefined();
    expect(item.heat).toBe(4);
    expect(item.source.color).toBe("#6B7280");
    expect(item.source.labelZh).toBe("新信源");
    expect(item.publishDate).toBe(NOW_KEY);
  });

  it("maps null source to unknown placeholder", () => {
    const item = mapNewsItem({ ...baseVo, source: null }, NOW);
    expect(item.source.sourceKey).toBe("unknown");
    expect(item.source.labelZh).toBe("未知来源");
  });
});

describe("mapHotEntry", () => {
  it("maps tags and sources as-is with bilingual fallback", () => {
    const entry = mapHotEntry({
      itemId: 1,
      titleZh: null,
      titleEn: "English only",
      heat: 7,
      tags: ["fresh"],
      sources: ["官网 · 媒体发布", "YouTube · 官方频道"]
    });
    expect(entry.titleZh).toBe("English only");
    expect(entry.titleEn).toBe("English only");
    expect(entry.heat).toBe(7);
    expect(entry.tags).toEqual(["fresh"]);
    expect(entry.sources).toHaveLength(2);
  });
});

describe("mapTopic", () => {
  it("maps topic groups and backfills icon from registry", () => {
    const faculty = mapTopic({ slug: "eng", nameZh: "工学院", nameEn: "Faculty of Engineering", topicGroup: "FACULTY", descriptionZh: "d", descriptionEn: "d", itemCount: 28 });
    expect(faculty.group).toBe(0);
    expect(faculty.icon).toBeUndefined();

    const research = mapTopic({ slug: "ai", nameZh: "人工智能", nameEn: "AI", topicGroup: "RESEARCH", descriptionZh: "d", descriptionEn: "d", itemCount: 7 });
    expect(research.group).toBe(1);
    expect(research.icon).toBe("🤖");

    const affairs = mapTopic({ slug: "admission", nameZh: "招生", nameEn: null, topicGroup: "STUDENT_AFFAIRS", descriptionZh: null, descriptionEn: null, itemCount: 3 });
    expect(affairs.group).toBe(2);
    expect(affairs.nameEn).toBe("招生");
    expect(affairs.descZh).toBe("");
  });
});

describe("hktClockSafe", () => {
  it("formats HKT clock", () => {
    expect(hktClockSafe(new Date("2026-09-10T22:22:00Z"))).toBe("06:22");
  });
});

describe("L40 跨时区（America/Los_Angeles）——日期分量不随本地时区漂移", () => {
  // 病灶：正午 HKT=04:00Z，LA（UTC-7）本地 getter 读成前一本地日（9/10→9/9），
  // 与 dayDiff「今天/昨天」前缀自相矛盾。修复=HKT 日键取 UTC 分量（恒等于 HKT 历日）。
  // Node 在 POSIX 下支持运行时改 process.env.TZ（CI=ubuntu/本机=darwin 均适用）。
  const originalTz = process.env.TZ;

  beforeAll(() => {
    process.env.TZ = "America/Los_Angeles";
  });

  afterAll(() => {
    if (originalTz === undefined) {
      delete process.env.TZ;
    } else {
      process.env.TZ = originalTz;
    }
  });

  it("dayLabels keeps HKT date under LA local timezone", () => {
    // 正午 HKT 2026-09-10 = LA 前一日 21:00——旧实现此处会输出 9月9日 周三
    expect(dayLabels("2026-09-10", "2026-09-10")).toEqual({ zh: "今天 · 9月10日 周四", en: "Today · Thu 10 Sep" });
    expect(dayLabels("2026-09-09", "2026-09-10")).toEqual({ zh: "昨天 · 9月9日 周三", en: "Yesterday · Wed 9 Sep" });
    expect(dayLabels("2026-09-02", "2026-09-10")).toEqual({ zh: "9月2日 周三", en: "Wed 2 Sep" });
  });

  it("formatUpdatedLabel keeps HKT date part under LA local timezone", () => {
    expect(formatUpdatedLabel(new Date("2026-09-10T06:22:00+08:00"), "zh")).toBe("数据更新至 9月10日 06:22");
    expect(formatUpdatedLabel(new Date("2026-09-10T06:22:00+08:00"), "en")).toBe("Updated 10 Sep 06:22");
  });

  it("feedDateLabels keeps HKT weekday/date under LA local timezone", () => {
    // 2026-09-13 HKT = 周日；正午 HKT 在 LA 是 9/12 21:00（周六）——旧实现会输出 周六
    const labels = feedDateLabels(new Date("2026-09-13T18:00:00+08:00"), "zh");
    expect(labels.long).toBe("9月13日 · 周日 · 2026");
    expect(labels.short).toBe("9月13日 · 周日");
    const en = feedDateLabels(new Date("2026-09-13T18:00:00+08:00"), "en");
    expect(en.long).toBe("Sun · 13 Sep 2026");
    expect(en.short).toBe("13 Sep · Sun");
  });
});

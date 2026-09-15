import { describe, expect, it } from "vitest";

import {
  MOCK_HOT_RANK,
  MOCK_NEWS_ITEMS,
  NEWS_SOURCES,
  NEWS_TOPIC_GROUPS,
  NEWS_TOPICS
} from "./newsMockData";
import { TOPIC_MISSING_MESSAGE, fetchHotRank, fetchNewsFeed, fetchTopicDetail, fetchTopics, newsApi } from "./newsService";

/**
 * 服务层与 mock 完整性机检面：
 * fixture 与定版原型逐项对齐（15 卡/12+3 分组、20 主题 6+6+8、热点榜 10 条降序、双语字段非空），
 * newsApi 实例与 api.ts 物理隔离（零请求拦截器=无 token 注入）。
 */
describe("newsMockData fixtures", () => {
  it("has 15 items: 12 today + 3 yesterday (prototype parity)", () => {
    expect(MOCK_NEWS_ITEMS).toHaveLength(15);
    const today = MOCK_NEWS_ITEMS.filter((item) => item.publishDate === "2026-09-10");
    const yesterday = MOCK_NEWS_ITEMS.filter((item) => item.publishDate === "2026-09-09");
    expect(today).toHaveLength(12);
    expect(yesterday).toHaveLength(3);
  });

  it("every item carries complete bilingual fields, official outbound url and registry source", () => {
    const slugs = new Set(NEWS_TOPICS.map((topic) => topic.slug));
    for (const item of MOCK_NEWS_ITEMS) {
      expect(item.titleZh.length).toBeGreaterThan(0);
      expect(item.titleEn.length).toBeGreaterThan(0);
      expect(item.summaryZh.length).toBeGreaterThan(0);
      expect(item.summaryEn.length).toBeGreaterThan(0);
      expect(item.dayLabelZh.length).toBeGreaterThan(0);
      expect(item.url).toMatch(/^https:\/\//);
      expect(NEWS_SOURCES[item.source.sourceKey]).toEqual(item.source);
      expect(item.topics.length).toBeGreaterThan(0);
      for (const slug of item.topics) {
        expect(slugs.has(slug)).toBe(true);
      }
    }
  });

  it("has 20 topics in three groups 6/6/8 with bilingual names (prototype TOPICS registry)", () => {
    expect(NEWS_TOPICS).toHaveLength(20);
    expect(NEWS_TOPICS.filter((t) => t.group === 0)).toHaveLength(6);
    expect(NEWS_TOPICS.filter((t) => t.group === 1)).toHaveLength(6);
    expect(NEWS_TOPICS.filter((t) => t.group === 2)).toHaveLength(8);
    expect(NEWS_TOPIC_GROUPS).toHaveLength(3);
    for (const topic of NEWS_TOPICS) {
      expect(topic.nameZh.length).toBeGreaterThan(0);
      expect(topic.nameEn.length).toBeGreaterThan(0);
      expect(topic.descZh.length).toBeGreaterThan(0);
      expect(topic.descEn.length).toBeGreaterThan(0);
    }
  });

  it("hot rank has 10 entries, heat descending, tags within boom/fresh/rise (prototype RANK_DATA)", () => {
    expect(MOCK_HOT_RANK).toHaveLength(10);
    for (let i = 1; i < MOCK_HOT_RANK.length; i++) {
      expect(MOCK_HOT_RANK[i - 1].heat).toBeGreaterThanOrEqual(MOCK_HOT_RANK[i].heat);
    }
    for (const entry of MOCK_HOT_RANK) {
      expect(entry.titleEn.length).toBeGreaterThan(0);
      expect(entry.sources.length).toBeGreaterThan(0);
      for (const tag of entry.tags) {
        expect(["boom", "fresh", "rise"]).toContain(tag);
      }
    }
  });
});

describe("newsService (mock-backed)", () => {
  it("fetchNewsFeed returns all 15 items in the first page by default", async () => {
    const page = await fetchNewsFeed();
    expect(page.records).toHaveLength(15);
    expect(page.total).toBe(15);
    expect(page.hasMore).toBe(false); // 15 < 页大小 20 → 单页
  });

  it("fetchNewsFeed filters by category (page shape)", async () => {
    expect((await fetchNewsFeed({ category: "research" })).records).toHaveLength(4);
    expect((await fetchNewsFeed({ category: "admission" })).records).toHaveLength(2);
  });

  it("fetchHotRank returns top slice ordered by heat", async () => {
    const top5 = await fetchHotRank(5);
    expect(top5).toHaveLength(5);
    expect(top5[0].heat).toBe(138);
    expect(top5[0].tags).toContain("boom");
    expect(await fetchHotRank()).toHaveLength(10);
  });

  it("fetchTopics returns 20 topics and 3 groups", async () => {
    const { groups, topics } = await fetchTopics();
    expect(topics).toHaveLength(20);
    expect(groups.map((g) => g.nameZh)).toEqual(["学院与部门", "研究领域与话题", "学生事务"]);
  });

  it("fetchTopicDetail returns topic-scoped items with same-source count", async () => {
    const detail = await fetchTopicDetail("ai");
    expect(detail.topic.slug).toBe("ai");
    // 计数与列表同源：itemCount=主题内条目数
    expect(detail.topic.itemCount).toBe(detail.page.records.length);
    expect(detail.page.total).toBe(detail.page.records.length);
    expect(detail.page.hasMore).toBe(false);
    // 全部条目确属该主题（专用端点契约）
    for (const item of detail.page.records) {
      expect(item.topics).toContain("ai");
    }
    // 「数据更新至」锚点=主题内最新发布时刻（fixture ai 最新=mock-007 09:50 HKT）
    expect(detail.lastPublishTime).not.toBeNull();
    expect(detail.lastPublishTime!.toISOString()).toBe(new Date("2026-09-10T09:50:00+08:00").toISOString());
  });

  it("fetchTopicDetail rejects unknown slugs with the backend contract message", async () => {
    await expect(fetchTopicDetail("no-such-topic")).rejects.toThrow(TOPIC_MISSING_MESSAGE);
  });
});

describe("newsApi instance isolation (api.ts zero-touch red line)", () => {
  it("installs no request interceptor (no token injection)", () => {
    // axios 未把 handlers 列入公开类型（运行时存在），此处做结构断言须显式收窄
    const manager = newsApi.interceptors.request as unknown as { handlers: unknown[] };
    expect(manager.handlers).toHaveLength(0);
  });
});

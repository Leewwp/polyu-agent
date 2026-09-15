import axios from "axios";

import type { HotRankEntry, NewsCategory, NewsItem, NewsTopic, NewsTopicGroup } from "@/types/news";
import { mapHotEntry, mapNewsItem, mapTopic } from "@/services/newsMapping";
import type { NewsHotRankEntryVO, NewsItemVO, NewsPageVO, NewsTopicDetailVO, NewsTopicVO } from "@/services/newsMapping";
import { MOCK_HOT_RANK, MOCK_NEWS_ITEMS, NEWS_TOPICS, NEWS_TOPIC_GROUPS } from "@/services/newsMockData";

const NEWS_API_BASE_URL = import.meta.env.VITE_API_BASE_URL || "";

/**
 * 公开资讯数据专用 axios 实例（与 api.ts 物理隔离）：
 * - 不注入 Authorization、不挂 401/会话过期硬跳拦截——匿名访问资讯流不会被拉去登录页；
 * - 公开页数据一律走本实例（api.ts 零改动红线）。
 * 响应拦截只做 Results 信封解包（{code:'0',data}），失败原样 reject，由页面自渲染错误/空态。
 */
export const newsApi = axios.create({
  baseURL: NEWS_API_BASE_URL,
  timeout: 15000
});

newsApi.interceptors.response.use(
  (response) => {
    const payload = response.data;
    if (payload && typeof payload === "object" && "code" in payload) {
      if (payload.code !== "0") {
        return Promise.reject(new Error(payload.message || "资讯加载失败"));
      }
      return payload.data;
    }
    return payload;
  },
  (error) => Promise.reject(error)
);

/**
 * mock 先行；**真数据接线已切换**：生产/开发一律走 /public/news/**
 * 真实接口；mock fixture 降级为 vitest 专用（newsMockData 保留不删）——
 * vitest 的 MODE="test" 自动回落 mock 分支，显式 VITE_NEWS_USE_MOCK=1 可强制（调试用）。
 */
const USE_MOCK = import.meta.env.MODE === "test" || import.meta.env.VITE_NEWS_USE_MOCK === "1";

export interface NewsFeedQuery {
  category?: NewsCategory | "all";
}

/** 首屏页大小（首屏 20 条+加载更多，不做页码） */
export const FEED_PAGE_SIZE = 20;

/** 资讯列表分页载荷（与后端 NewsPageVO 的 records/total/hasMore 对齐） */
export interface NewsFeedPage {
  records: NewsItem[];
  total: number;
  hasMore: boolean;
}

/**
 * 资讯列表（分页：首屏 20 条+加载更多）。
 * mock 分支在 fixture 上模拟同形分页（15 条 < 20 → 单页无更多）；
 * 真数据分支 VO→NewsItem 映射见 newsMapping。
 */
export async function fetchNewsFeed(query: NewsFeedQuery & { page?: number } = {}): Promise<NewsFeedPage> {
  const { category = "all", page = 1 } = query;
  if (USE_MOCK) {
    const filtered = category === "all" ? MOCK_NEWS_ITEMS : MOCK_NEWS_ITEMS.filter((item) => item.category === category);
    const start = (page - 1) * FEED_PAGE_SIZE;
    return {
      records: filtered.slice(start, start + FEED_PAGE_SIZE),
      total: filtered.length,
      hasMore: page * FEED_PAGE_SIZE < filtered.length
    };
  }
  const data = await newsApi.get<NewsPageVO, NewsPageVO>("/public/news/list", {
    params: {
      category: category === "all" ? undefined : category,
      page
    }
  });
  return {
    records: (data.records ?? []).map((vo: NewsItemVO) => mapNewsItem(vo)),
    total: data.total ?? 0,
    hasMore: data.hasMore ?? false
  };
}

/** 热点榜（默认 10 条；首页热点卡取前 5） */
export async function fetchHotRank(limit = 10): Promise<HotRankEntry[]> {
  if (USE_MOCK) {
    return MOCK_HOT_RANK.slice(0, limit);
  }
  const data = await newsApi.get<NewsHotRankEntryVO[], NewsHotRankEntryVO[]>("/public/news/hot");
  return data.map(mapHotEntry).slice(0, limit);
}

/**
 * 资讯详情单条：分享/直链场景可达；不存在/已下架后端同形报
 * 「资讯不存在」——本层原样 reject，页面落 not-found 态。
 */
export async function fetchNewsDetail(id: string): Promise<NewsItem> {
  if (USE_MOCK) {
    const found = MOCK_NEWS_ITEMS.find((item) => item.id === id);
    if (!found) {
      throw new Error("资讯不存在");
    }
    return found;
  }
  const data = await newsApi.get<NewsItemVO, NewsItemVO>("/public/news/detail", { params: { id } });
  return mapNewsItem(data);
}

/** 检索排序档：time=发布时间倒序（默认）/relevance=标题命中优先 */
export type NewsSearchSort = "time" | "relevance";

/**
 * 全局检索：标题+摘要四列匹配（大小写不敏感），mock 分支在 fixture 上
 * 模拟同语义；relevance 档标题命中优先、同分保持 fixture 既有时间倒序。
 */
export async function searchNewsFeed(query: { q: string; sort?: NewsSearchSort; page?: number }): Promise<NewsFeedPage> {
  const { q, sort = "time", page = 1 } = query;
  if (USE_MOCK) {
    const needle = q.trim().toLowerCase();
    const titleHit = (item: NewsItem) =>
      item.titleZh.toLowerCase().includes(needle) || item.titleEn.toLowerCase().includes(needle);
    const matched = MOCK_NEWS_ITEMS.filter(
      (item) =>
        titleHit(item) ||
        item.summaryZh.toLowerCase().includes(needle) ||
        item.summaryEn.toLowerCase().includes(needle)
    );
    const sorted =
      sort === "relevance"
        ? matched.map((item, index) => ({ item, index })).sort((a, b) => {
            const rank = (entry: { item: NewsItem }) => (titleHit(entry.item) ? 0 : 1);
            return rank(a) - rank(b) || a.index - b.index;
          }).map((entry) => entry.item)
        : matched;
    const start = (page - 1) * FEED_PAGE_SIZE;
    return {
      records: sorted.slice(start, start + FEED_PAGE_SIZE),
      total: sorted.length,
      hasMore: page * FEED_PAGE_SIZE < sorted.length
    };
  }
  const data = await newsApi.get<NewsPageVO, NewsPageVO>("/public/news/search", {
    params: { q, sort, page }
  });
  return {
    records: (data.records ?? []).map((vo: NewsItemVO) => mapNewsItem(vo)),
    total: data.total ?? 0,
    hasMore: data.hasMore ?? false
  };
}

/** 主题目录（20 主题注册表 + 三维分组头；主题地图页消费） */
export async function fetchTopics(): Promise<{ groups: NewsTopicGroup[]; topics: NewsTopic[] }> {
  if (USE_MOCK) {
    return { groups: NEWS_TOPIC_GROUPS, topics: NEWS_TOPICS };
  }
  const data = await newsApi.get<NewsTopicVO[], NewsTopicVO[]>("/public/news/topics");
  return { groups: NEWS_TOPIC_GROUPS, topics: data.map(mapTopic) };
}

/** 后端「主题不存在」业务文案（跨栈契约：未知 slug → 详情页回 /topics；NewsQueryServiceImpl 同文案） */
export const TOPIC_MISSING_MESSAGE = "主题不存在";

/**
 * 主题详情页单页大小：后端 MAX_PAGE_SIZE=50，现最大主题 44 条一页装下；
 * hasMore 为真时页面仍显示「加载更多」护栏，超限主题不分页丢失。
 */
export const TOPIC_PAGE_SIZE = 50;

/** 主题详情载荷（/public/news/topic/{slug} 专用端点）：计数/更新时间/条目同源 */
export interface TopicDetailData {
  topic: NewsTopic;
  /** 主题内最近一条发布时刻（HKT 展示格式化在页面层；空主题 null） */
  lastPublishTime: Date | null;
  page: NewsFeedPage;
}

/**
 * 主题详情：统计头计数、近期焦点、最新动态全部取本端点，
 * 不再拉全局流客户端过滤（/topics/ai 44 vs 9、csm 空态同根因）。
 */
export async function fetchTopicDetail(slug: string, page = 1): Promise<TopicDetailData> {
  if (USE_MOCK) {
    const topic = NEWS_TOPICS.find((candidate) => candidate.slug === slug);
    if (!topic) {
      throw new Error(TOPIC_MISSING_MESSAGE);
    }
    const matched = MOCK_NEWS_ITEMS.filter((item) => item.topics.includes(slug));
    const newest = matched.reduce<Date | null>((acc, item) => {
      const published = new Date(`${item.publishDate}T${item.publishTime}:00+08:00`);
      return !acc || published > acc ? published : acc;
    }, null);
    const start = (page - 1) * TOPIC_PAGE_SIZE;
    return {
      // itemCount 与列表同源（端点契约=同谓词计数；原型注册表 n 为旧快照不回填）
      topic: { ...topic, itemCount: matched.length },
      lastPublishTime: newest,
      page: {
        records: matched.slice(start, start + TOPIC_PAGE_SIZE),
        total: matched.length,
        hasMore: page * TOPIC_PAGE_SIZE < matched.length
      }
    };
  }
  const data = await newsApi.get<NewsTopicDetailVO, NewsTopicDetailVO>(`/public/news/topic/${encodeURIComponent(slug)}`, {
    params: { page, size: TOPIC_PAGE_SIZE }
  });
  return {
    topic: mapTopic(data.topic),
    lastPublishTime: data.lastPublishTime ? new Date(data.lastPublishTime) : null,
    page: {
      records: (data.items?.records ?? []).map((vo: NewsItemVO) => mapNewsItem(vo)),
      total: data.items?.total ?? 0,
      hasMore: data.items?.hasMore ?? false
    }
  };
}

/**
 * 资讯流类型：mock 先行，字段对齐 /public/news/** 未来 VO
 * （双语字段 + 来源元数据）。双语策略=数据级（VO 双语字段），不引入 i18n 框架。
 */

/** 固定 8 类 + other 兜底（筛选 chips 维度，禁止自由标签） */
export type NewsCategory =
  | "admission"
  | "scholarship"
  | "research"
  | "campus"
  | "event"
  | "career"
  | "exchange"
  | "admin"
  | "other";

/** 平台标识（t_news_source.platform 对应物） */
export type NewsPlatform = "official" | "youtube" | "events" | "prn";

export interface NewsSource {
  sourceKey: string;
  platform: NewsPlatform;
  /** 信源徽章文案（中文=原型原样；英文为 mock 补充对照） */
  labelZh: string;
  labelEn: string;
  /** 徽章圆点颜色（原型信源五色） */
  color: string;
  official: boolean;
}

export interface NewsItem {
  id: string;
  /** 永久原文外链（卡片永远外链原文，不进详情页） */
  url: string;
  category: NewsCategory;
  /** 主题 slug 列表（t_news_item_topic 对应物；slug ⊆ 20 主题注册表） */
  topics: string[];
  heat: number;
  /** YYYY-MM-DD */
  publishDate: string;
  /** HH:mm（HKT） */
  publishTime: string;
  /** 日期分组标签（如「今天 · 9月10日 周四」；真数据接线后按 HKT 生成，mock 为原型原样） */
  dayLabelZh: string;
  dayLabelEn: string;
  source: NewsSource;
  titleZh: string;
  titleEn: string;
  summaryZh: string;
  summaryEn: string;
  /** 故事线聚合簇：「热点 · 另有 N 个来源」；undefined=未入簇 */
  clusterSourceCount?: number;
}

/** 热点榜标签（爆=短时间密集报道 / 新=首报 6h 内 / 发酵中=信源仍在增加） */
export type HotRankTag = "boom" | "fresh" | "rise";

export interface HotRankEntry {
  titleZh: string;
  titleEn: string;
  heat: number;
  tags: HotRankTag[];
  /** 信源名单（原型 RANK_DATA 标签原样） */
  sources: string[];
  /**
   * 代表条目 id（→ /news/:id 详情；榜单行可点）。
   * mock fixture 无此字段（榜单渲染为纯文本），真数据映射自 NewsHotRankEntryVO.itemId。
   */
  itemId?: number;
}

/** 主题（t_news_topic 种子词表对应物；三维分组见 group） */
export interface NewsTopic {
  slug: string;
  nameZh: string;
  nameEn: string;
  descZh: string;
  descEn: string;
  /** 条目计数（原型注册表 n 字段） */
  itemCount: number;
  /** 0=学院与部门 / 1=研究领域与话题 / 2=学生事务（原型 g 字段） */
  group: 0 | 1 | 2;
  /** 分组 1/2 主题带图标（原型 icon 字段） */
  icon?: string;
}

/** 主题三维分组目录头 */
export interface NewsTopicGroup {
  nameZh: string;
  nameEn: string;
  subZh: string;
  subEn: string;
}

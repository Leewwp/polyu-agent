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

/** ==================== 日报（#212） ==================== */

/** 日报条目快照（后端 NewsDailyDigestItemVO：全部字段来自快照列，与 t_news_item 现值无关） */
export interface NewsDailyDigestItem {
  /** 溯源条目 id（点击进 /news/{id} 详情；源行被保留清理后详情 404 属预期） */
  itemId: number;
  /** 刊内序（1 起，发布时间倒序） */
  seq: number;
  /** 原文 URL 快照（永久外链） */
  url: string;
  titleZh: string | null;
  titleEn: string | null;
  summaryZh: string | null;
  summaryEn: string | null;
  category: NewsCategory;
  /** 主题 slug 快照（生成时刻关联） */
  topics: string[];
  /** ISO 时间串或 null */
  publishTime: string | null;
  /** 信源元数据快照 */
  source: {
    sourceKey: string;
    platform: string;
    official: boolean | null;
    displayName: string | null;
    displayNameEn: string | null;
  } | null;
}

/** 日报详情（后端 NewsDailyDigestVO：读取期下架复检后的生效口径） */
export interface NewsDailyDigest {
  /** YYYY-MM-DD（HKT 窗口闭端日） */
  digestDate: string;
  windowStart: string;
  windowEnd: string;
  /** 生效导语（有失格条目时已是模板回退，不含被下架内容） */
  introZh: string;
  introEn: string;
  /** 刊头导语产出方式：llm / fallback / empty */
  storedIntroSource: string;
  /** true=有快照条目失格，导语已回退模板 */
  introDegraded: boolean;
  /** 快照总条数（生成时刻） */
  itemCount: number;
  /** 读取期复检后可见条数 */
  visibleCount: number;
  disqualifiedCount: number;
  items: NewsDailyDigestItem[];
  buildTime: string;
}

/** 日报目录行（后端 NewsDailyDigestSummaryVO：不携带导语正文） */
export interface NewsDailyDigestSummary {
  digestDate: string;
  itemCount: number;
  introSource: string;
  buildTime: string;
  /**
   * #240 目录首条标题字段（每期第一个可见条目的双语标题快照，空期=null）：
   * #241 消费面=日报报刊 rail/翻期格的标题预览。与详情头条（items[0]）
   * 同源（同一快照列），保证「头条与目录 firstTitle 同源一致」。
   */
  firstTitleZh?: string | null;
  firstTitleEn?: string | null;
}

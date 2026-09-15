/**
 * 资讯流 mock 数据（mock 先行、与后端并行）。
 * 全部双语字段从定版原型页原样提取：
 * 15 张卡（今天 12 + 昨天 3）、TOPICS 20 主题注册表、RANK_DATA 热点榜 10 条。
 * 真数据接线（/public/news/**）落地后由 newsService 切换，本模块随之退役。
 */

import type { HotRankEntry, NewsCategory, NewsItem, NewsSource, NewsTopic, NewsTopicGroup } from "@/types/news";

/** 信源注册表（原型卡片徽章五色五源；EN 对照为 mock 补充） */
export const NEWS_SOURCES: Record<string, NewsSource> = {
  "official-media-release": {
    sourceKey: "official-media-release",
    platform: "official",
    labelZh: "官网 · 媒体发布",
    labelEn: "Official site · Media releases",
    color: "#A6192E",
    official: true
  },
  "official-focus": {
    sourceKey: "official-focus",
    platform: "official",
    labelZh: "官网 · 焦点",
    labelEn: "Official site · Focus",
    color: "#A6192E",
    official: true
  },
  "official-events": {
    sourceKey: "official-events",
    platform: "events",
    labelZh: "官网 · 活动日历",
    labelEn: "Official site · Events calendar",
    color: "#7C3AED",
    official: true
  },
  "youtube-main": {
    sourceKey: "youtube-main",
    platform: "youtube",
    labelZh: "YouTube · 官方频道",
    labelEn: "YouTube · Official channel",
    color: "#FF0000",
    official: true
  },
  "prn-polyu": {
    sourceKey: "prn-polyu",
    platform: "prn",
    labelZh: "PR Newswire · 理大",
    labelEn: "PR Newswire · PolyU",
    color: "#0F766E",
    official: false
  }
};

/** 分类筛选 chips（原型 #chips 顺序原样） */
export const NEWS_CATEGORY_CHIPS: { key: NewsCategory | "all"; labelZh: string; labelEn: string }[] = [
  { key: "all", labelZh: "全部", labelEn: "All" },
  { key: "admission", labelZh: "招生", labelEn: "Admissions" },
  { key: "research", labelZh: "科研", labelEn: "Research" },
  { key: "campus", labelZh: "校园", labelEn: "Campus" },
  { key: "event", labelZh: "活动", labelEn: "Events" },
  { key: "career", labelZh: "就业", labelEn: "Careers" },
  { key: "exchange", labelZh: "交流", labelEn: "Exchange" },
  { key: "scholarship", labelZh: "奖学金", labelEn: "Scholarships" },
  { key: "admin", labelZh: "公告", labelEn: "Notices" }
];

/** 分类中文短标签（卡片 c-cat 徽章文案） */
export const NEWS_CATEGORY_LABELS_ZH: Record<NewsCategory, string> = {
  admission: "招生",
  scholarship: "奖学金",
  research: "科研",
  campus: "校园",
  event: "活动",
  career: "就业",
  exchange: "交流",
  admin: "公告",
  other: "其他"
};

/** 分类英文短标签（卡片 c-cat 徽章 EN 文案；与 chips labelEn 对齐） */
export const NEWS_CATEGORY_LABELS_EN: Record<NewsCategory, string> = {
  admission: "Admissions",
  scholarship: "Scholarships",
  research: "Research",
  campus: "Campus",
  event: "Events",
  career: "Careers",
  exchange: "Exchange",
  admin: "Notices",
  other: "Other"
};

/** 顶栏日期/「更新至」常量已删（2026-09-13）：生产消费点改实时值——
 *  顶栏=feedDateLabels()、热点榜副标题=formatUpdatedLabel()（newsMapping.ts，HKT 工具族
 *  同源防本地时区跨日）；mock 注册表本身不动（仅供 dev 环境）。 */

const DAY_TODAY_ZH = "今天 · 9月10日 周四";
const DAY_TODAY_EN = "Today · Thu 10 Sep";
const DAY_YESTERDAY_ZH = "昨天 · 9月9日 周三";
const DAY_YESTERDAY_EN = "Yesterday · Wed 9 Sep";

/**
 * 15 张资讯卡 fixture（DOM 顺序=原型 viewFeed；url 为占位外链，真数据接线后替换）。
 */
export const MOCK_NEWS_ITEMS: NewsItem[] = [
  {
    id: "mock-001",
    url: "https://www.polyu.edu.hk/media/media-releases/2026/0910/perovskite-stability/",
    category: "research",
    topics: ["energy", "materials", "eng"],
    heat: 138,
    publishDate: "2026-09-10",
    publishTime: "07:58",
    dayLabelZh: DAY_TODAY_ZH,
    dayLabelEn: DAY_TODAY_EN,
    source: NEWS_SOURCES["official-media-release"],
    titleZh: "理大团队破解钙钛矿太阳能电池稳定性难题，成果刊于《自然·能源》",
    titleEn: "PolyU team cracks perovskite solar-cell stability problem, published in Nature Energy",
    summaryZh:
      "理大应用物理系团队提出新型界面钝化策略，将钙钛矿太阳能电池在高温高湿下的运行寿命显著延长，转换效率同时获得提升，为产业化落地扫清关键障碍。",
    summaryEn:
      "A PolyU applied physics team proposes a novel interface passivation strategy that significantly extends perovskite cell lifetime under heat and humidity while boosting efficiency.",
    clusterSourceCount: 2
  },
  {
    id: "mock-002",
    url: "https://www.polyu.edu.hk/media/media-releases/2026/0910/alumni-week-2026/",
    category: "campus",
    topics: ["campus", "gba"],
    heat: 126,
    publishDate: "2026-09-10",
    publishTime: "09:12",
    dayLabelZh: DAY_TODAY_ZH,
    dayLabelEn: DAY_TODAY_EN,
    source: NEWS_SOURCES["official-media-release"],
    titleZh: "首届「理大校友周」汇聚逾 1,200 名校友返校共聚",
    titleEn: "Inaugural “PolyU Alumni Week” gathers over 1,200 alumni back on campus",
    summaryZh:
      "为期一周的活动涵盖学院开放日、行业分享会与校园导览，逾 1,200 名不同年代的校友报名参与，为历次规模最大的校友集中返校活动。",
    summaryEn:
      "The week-long programme featured faculty open days, industry sharing and campus tours, with 1,200+ alumni registering.",
    clusterSourceCount: 3
  },
  {
    id: "mock-003",
    url: "https://www.polyu.edu.hk/media/media-releases/2026/0910/diangens-joint-lab/",
    category: "research",
    topics: ["biomed", "eng", "bus"],
    heat: 97,
    publishDate: "2026-09-10",
    publishTime: "08:37",
    dayLabelZh: DAY_TODAY_ZH,
    dayLabelEn: DAY_TODAY_EN,
    source: NEWS_SOURCES["official-media-release"],
    titleZh: "理大与 Diagens Tech 成立联合实验室，推动纳米孔基因测序技术产业化",
    titleEn: "PolyU and Diagens Tech establish joint lab to industrialise nanopore sequencing",
    summaryZh:
      "联合实验室将聚焦纳米孔基因测序的芯片与算法研发，结合理大在微电子与生物医学工程方面的积累，目标三年内推出面向临床的低成本测序方案。",
    summaryEn: "The joint lab focuses on nanopore sequencing chips and algorithms, aiming for a low-cost clinical solution within three years."
  },
  {
    id: "mock-004",
    url: "https://www.polyu.edu.hk/recent-focus/mainland-admissions-2026-27/",
    category: "admission",
    topics: ["admission"],
    heat: 71,
    publishDate: "2026-09-10",
    publishTime: "12:08",
    dayLabelZh: DAY_TODAY_ZH,
    dayLabelEn: DAY_TODAY_EN,
    source: NEWS_SOURCES["official-focus"],
    titleZh: "一文读懂 2026/27 内地本科生招生安排",
    titleEn: "Mainland undergraduate admissions 2026/27, explained",
    summaryZh:
      "招生办发布长文，系统梳理 2026/27 学年内地本科申请的时间线、专业选择与加分项，并集中回答了高热度咨询问题。",
    summaryEn: "Admissions published a long-form guide to the 2026/27 application timeline, programme choices and bonuses."
  },
  {
    id: "mock-005",
    url: "https://www.polyu.edu.hk/recent-focus/usfhk-basketball-double-2026/",
    category: "campus",
    topics: ["campus"],
    heat: 84,
    publishDate: "2026-09-10",
    publishTime: "11:20",
    dayLabelZh: DAY_TODAY_ZH,
    dayLabelEn: DAY_TODAY_EN,
    source: NEWS_SOURCES["official-focus"],
    titleZh: "理大男女篮双双卫冕大专杯，庆祝图集登上校园焦点",
    titleEn: "PolyU basketball teams defend both USFHK titles; gallery featured",
    summaryZh:
      "校园焦点栏目发布九图庆祝图集：男篮女篮在决赛中双双取胜实现卫冕，图集收获大批校友留言祝贺。",
    summaryEn: "The campus focus gallery posted nine celebration photos after both teams defended their titles."
  },
  {
    id: "mock-006",
    url: "https://www.youtube.com/watch?v=polyu-flexible-sensors",
    category: "research",
    topics: ["biomed", "materials", "eng"],
    heat: 52,
    publishDate: "2026-09-10",
    publishTime: "07:45",
    dayLabelZh: DAY_TODAY_ZH,
    dayLabelEn: DAY_TODAY_EN,
    source: NEWS_SOURCES["youtube-main"],
    titleZh: "视频：新一代柔性传感器如何守护健康（研究揭秘）",
    titleEn: "Video: How next-gen flexible sensors safeguard your health",
    summaryZh: "官方频道发布 4 分钟科普短片，介绍理大在可穿戴柔性电子领域的三项代表性成果及其临床应用场景。",
    summaryEn: "A 4-minute explainer on three flagship wearable-flexible-electronics breakthroughs."
  },
  {
    id: "mock-007",
    url: "https://www.polyu.edu.hk/events/ai-urban-resilience-lecture/",
    category: "event",
    topics: ["ai", "city", "event", "ce"],
    heat: 31,
    publishDate: "2026-09-10",
    publishTime: "09:50",
    dayLabelZh: DAY_TODAY_ZH,
    dayLabelEn: DAY_TODAY_EN,
    source: NEWS_SOURCES["official-events"],
    titleZh: "讲座预告：人工智能与城市韧性 · Infrastructure 系列第 4 讲（9 月 12 日）",
    titleEn: "Lecture: AI and urban resilience — Infrastructure Series #4 (12 Sep)",
    summaryZh:
      "建设及环境学院主办的公开讲座，将探讨 AI 在城市基础设施韧性评估中的应用，免费报名、面向全校及公众开放。",
    summaryEn: "A public lecture on AI in urban infrastructure resilience; free registration, open to all."
  },
  {
    id: "mock-008",
    url: "https://www.prnasia.com/news/releases/polyu-annual-report-2025-26/",
    category: "admin",
    topics: ["admin", "gba"],
    heat: 39,
    publishDate: "2026-09-10",
    publishTime: "06:30",
    dayLabelZh: DAY_TODAY_ZH,
    dayLabelEn: DAY_TODAY_EN,
    source: NEWS_SOURCES["prn-polyu"],
    titleZh: "理大发布 2025/26 年度报告：创新研究与社会影响并进",
    titleEn: "PolyU releases 2025/26 annual report",
    summaryZh: "年度报告综述过去一学年研究资助、专利授权与社会服务数据，并公布下一学年三大战略重点方向。",
    summaryEn: "The annual report reviews funding, patents and social-service figures, plus three strategic priorities."
  },
  {
    id: "mock-009",
    url: "https://www.polyu.edu.hk/events/autumn-careers-fair-2026/",
    category: "career",
    topics: ["career", "bus"],
    heat: 47,
    publishDate: "2026-09-10",
    publishTime: "14:22",
    dayLabelZh: DAY_TODAY_ZH,
    dayLabelEn: DAY_TODAY_EN,
    source: NEWS_SOURCES["official-events"],
    titleZh: "秋季校园招聘会 10 月开锣，首批 80 家企业名录公布",
    titleEn: "Autumn careers fair opens in October; first 80 employers announced",
    summaryZh: "活动日历更新秋招会条目：首批参展企业涵盖工程、金融与科技行业，简历投递通道将于下周开放。",
    summaryEn: "The calendar updated the fair entry; the CV submission channel opens next week."
  },
  {
    id: "mock-010",
    url: "https://www.polyu.edu.hk/events/mid-autumn-garden-party-2026/",
    category: "event",
    topics: ["event", "campus"],
    heat: 28,
    publishDate: "2026-09-10",
    publishTime: "16:40",
    dayLabelZh: DAY_TODAY_ZH,
    dayLabelEn: DAY_TODAY_EN,
    source: NEWS_SOURCES["official-events"],
    titleZh: "中秋游园会报名开启：冰皮月饼手作 + 灯笼工作坊",
    titleEn: "Mid-Autumn garden party: mooncake & lantern workshops, registration open",
    summaryZh: "学生会主办的中秋游园会将于 9 月 24 日在邵逸夫楼平台举行，两项工作坊各限 40 人，先到先得。",
    summaryEn: "The garden party lands on the Shaw Podium on 24 Sep; each workshop caps at 40."
  },
  {
    id: "mock-011",
    url: "https://www.youtube.com/watch?v=polyu-campus-tour-2026",
    category: "admission",
    topics: ["admission", "campus"],
    heat: 22,
    publishDate: "2026-09-10",
    publishTime: "06:12",
    dayLabelZh: DAY_TODAY_ZH,
    dayLabelEn: DAY_TODAY_EN,
    source: NEWS_SOURCES["youtube-main"],
    titleZh: "Campus Tour 2026：跟着学长逛理大宿舍与校园（英文）",
    titleEn: "Campus Tour 2026: halls and campus with a student guide",
    summaryZh: "招生办发布全新 12 分钟校园导览视频，覆盖两所代表性宿舍、图书馆与核心教学楼，配多语言字幕。",
    summaryEn: "A new 12-minute tour covering two halls, the library and core teaching buildings."
  },
  {
    id: "mock-012",
    url: "https://www.polyu.edu.hk/recent-focus/swiss-summer-exchange-2026/",
    category: "exchange",
    topics: ["exchange", "campus"],
    heat: 25,
    publishDate: "2026-09-10",
    publishTime: "10:05",
    dayLabelZh: DAY_TODAY_ZH,
    dayLabelEn: DAY_TODAY_EN,
    source: NEWS_SOURCES["official-focus"],
    titleZh: "理大学生赴瑞士参与夏季交换计划，分享一学期游学见闻",
    titleEn: "PolyU students share a semester on Swiss summer exchange",
    summaryZh: "三位来自不同学院的学生记录了在苏黎世与洛桑的交换生活，涵盖选课、住宿与跨文化体验的实用建议。",
    summaryEn: "Three students document their exchange in Zurich and Lausanne with practical tips."
  },
  {
    id: "mock-013",
    url: "https://www.polyu.edu.hk/media/media-releases/2026/0909/entrance-scholarships-2026-27/",
    category: "scholarship",
    topics: ["scholarship", "admission"],
    heat: 18,
    publishDate: "2026-09-09",
    publishTime: "17:02",
    dayLabelZh: DAY_YESTERDAY_ZH,
    dayLabelEn: DAY_YESTERDAY_EN,
    source: NEWS_SOURCES["official-media-release"],
    titleZh: "多项入学奖学金申请通道开放，最高全额学费加生活津贴",
    titleEn: "Entrance scholarships open, up to full tuition plus stipend",
    summaryZh: "2026/27 入学奖学金即日起接受申请，涵盖学业卓越、领导力与专项才能三类，官网提供统一申请入口。",
    summaryEn: "Entrance scholarships for 2026/27 are now open across three tracks with one portal."
  },
  {
    id: "mock-014",
    url: "https://www.polyu.edu.hk/media/media-releases/2026/0909/nsfc-record-funding/",
    category: "research",
    topics: ["ai", "eng", "ce", "csm"],
    heat: 63,
    publishDate: "2026-09-09",
    publishTime: "09:26",
    dayLabelZh: DAY_YESTERDAY_ZH,
    dayLabelEn: DAY_YESTERDAY_EN,
    source: NEWS_SOURCES["official-media-release"],
    titleZh: "理大获国家自然科学基金资助项目数再创新高",
    titleEn: "PolyU hits a record high in NSFC-funded projects",
    summaryZh: "理大在最新一轮国家自然科学基金评审中获批项目数量与金额均创历史新高，重点分布在人工智能与智慧城市领域。",
    summaryEn: "PolyU secured record funding in the latest NSFC round, concentrated in AI and smart-city research."
  },
  {
    id: "mock-015",
    url: "https://www.polyu.edu.hk/recent-focus/off-campus-housing-guide/",
    category: "campus",
    topics: ["housing", "campus"],
    heat: 15,
    publishDate: "2026-09-09",
    publishTime: "15:41",
    dayLabelZh: DAY_YESTERDAY_ZH,
    dayLabelEn: DAY_YESTERDAY_EN,
    source: NEWS_SOURCES["official-focus"],
    titleZh: "理大周边租房实用指南：红磡与佐敦篇（焦点特稿）",
    titleEn: "Off-campus housing guide: Hung Hom & Jordan",
    summaryZh: "焦点特稿集中梳理理大周边租房的常见问题，提醒签约前核实租约条款与水电分摊方式。",
    summaryEn: "A focus feature rounds up renting FAQs around Hung Hom and Jordan."
  }
];

/** 热点榜 fixture（原型 RANK_DATA 原样，热度降序） */
export const MOCK_HOT_RANK: HotRankEntry[] = [
  {
    titleZh: "理大团队破解钙钛矿太阳能电池稳定性难题",
    titleEn: "PolyU team cracks perovskite stability problem",
    heat: 138,
    tags: ["boom", "rise"],
    sources: ["官网 · 媒体发布", "官网 · 焦点", "官网 · 活动日历", "PR Newswire", "YouTube · 官方频道"]
  },
  {
    titleZh: "首届「理大校友周」汇聚逾 1,200 名校友返校",
    titleEn: "Inaugural Alumni Week gathers 1,200+ alumni",
    heat: 126,
    tags: ["fresh", "rise"],
    sources: ["官网 · 媒体发布", "官网 · 焦点", "YouTube · 官方频道", "PR Newswire"]
  },
  {
    titleZh: "理大与 Diagens Tech 成立联合实验室",
    titleEn: "PolyU and Diagens Tech launch joint lab",
    heat: 97,
    tags: ["rise"],
    sources: ["官网 · 媒体发布", "PR Newswire", "官网 · 焦点"]
  },
  {
    titleZh: "理大男女篮双夺大专杯冠军",
    titleEn: "PolyU teams win double USFHK titles",
    heat: 84,
    tags: [],
    sources: ["官网 · 媒体发布", "YouTube · 官方频道"]
  },
  {
    titleZh: "2026/27 内地本科招生安排发布",
    titleEn: "2026/27 mainland admissions released",
    heat: 71,
    tags: ["fresh"],
    sources: ["官网 · 媒体发布", "官网 · 焦点", "YouTube · 官方频道"]
  },
  {
    titleZh: "理大获国家自然科学基金资助项目数创新高",
    titleEn: "Record NSFC funding for PolyU",
    heat: 63,
    tags: ["fresh"],
    sources: ["官网 · 媒体发布", "PR Newswire"]
  },
  {
    titleZh: "新一代柔性传感器守护健康（视频）",
    titleEn: "Flexible sensors for health (video)",
    heat: 52,
    tags: [],
    sources: ["YouTube · 官方频道"]
  },
  {
    titleZh: "秋季校园招聘会首批 80 家企业公布",
    titleEn: "Autumn fair: first 80 employers",
    heat: 47,
    tags: [],
    sources: ["官网 · 活动日历", "官网 · 媒体发布"]
  },
  {
    titleZh: "理大发布 2025/26 年度报告",
    titleEn: "PolyU releases 2025/26 annual report",
    heat: 39,
    tags: [],
    sources: ["官网 · 媒体发布", "PR Newswire"]
  },
  {
    titleZh: "讲座：人工智能与城市韧性",
    titleEn: "Lecture: AI and urban resilience",
    heat: 31,
    tags: ["fresh"],
    sources: ["官网 · 活动日历"]
  }
];

/** 热点标签文案（原型 TAG_ZH 原样） */
export const HOT_TAG_LABELS: Record<"boom" | "fresh" | "rise", { zh: string; en: string }> = {
  boom: { zh: "爆", en: "BOOM" },
  fresh: { zh: "新", en: "NEW" },
  rise: { zh: "发酵中", en: "RISING" }
};

/** 20 主题注册表（原型 TOPICS 原样；n=条目计数、g=分组 0/1/2） */
export const NEWS_TOPICS: NewsTopic[] = [
  { slug: "eng", nameZh: "工学院", nameEn: "Faculty of Engineering", descZh: "工学院及旗下学系的科研、课程与活动动态", descEn: "Research, programmes and events from FENG and its departments", itemCount: 28, group: 0 },
  { slug: "bus", nameZh: "工商管理学院", nameEn: "Faculty of Business", descZh: "商学院及旗下学系与中心的动态", descEn: "Updates from FB and its schools and centres", itemCount: 22, group: 0 },
  { slug: "csm", nameZh: "计算及数理科学学院", nameEn: "Faculty of Computing & Math Sciences", descZh: "计算机科学、数学与数据科学方向的动态", descEn: "Computing, mathematics and data science updates", itemCount: 18, group: 0 },
  { slug: "ce", nameZh: "建设及环境学院", nameEn: "Faculty of Construction & Environment", descZh: "建筑、土木、环境与测量方向的动态", descEn: "Built environment and civil updates", itemCount: 16, group: 0 },
  { slug: "hss", nameZh: "医疗及社会科学院", nameEn: "Faculty of Health & Social Sciences", descZh: "医疗健康与社会科学方向的动态", descEn: "Health and social sciences updates", itemCount: 14, group: 0 },
  { slug: "htm", nameZh: "酒店及旅游业管理学院", nameEn: "School of Hotel & Tourism Mgmt", descZh: "酒店与旅游管理教育研究的动态", descEn: "Hospitality and tourism education and research", itemCount: 9, group: 0 },
  { slug: "ai", nameZh: "人工智能", nameEn: "Artificial Intelligence", descZh: "AI 算法、应用与治理方向的科研与活动", descEn: "Research and events on AI algorithms, applications and governance", itemCount: 74, group: 1, icon: "🤖" },
  { slug: "biomed", nameZh: "生物医药与健康", nameEn: "Biomedicine & Health", descZh: "医学、生物工程与健康科学方向的进展", descEn: "Advances in medicine, bioengineering and health sciences", itemCount: 42, group: 1, icon: "🧬" },
  { slug: "energy", nameZh: "新能源与可持续", nameEn: "Energy & Sustainability", descZh: "新能源、碳中和与可持续发展动态", descEn: "New energy, carbon neutrality and sustainability", itemCount: 31, group: 1, icon: "⚡" },
  { slug: "city", nameZh: "智慧城市", nameEn: "Smart City", descZh: "智慧城市与城市韧性相关研究与实践", descEn: "Smart-city and urban resilience research", itemCount: 26, group: 1, icon: "🏙" },
  { slug: "materials", nameZh: "新材料", nameEn: "Advanced Materials", descZh: "新材料研发与应用的进展", descEn: "Advanced materials research and applications", itemCount: 23, group: 1, icon: "🧱" },
  { slug: "gba", nameZh: "大湾区合作", nameEn: "Greater Bay Area", descZh: "理大与大湾区机构的合作与交流", descEn: "Collaborations and exchanges across the GBA", itemCount: 19, group: 1, icon: "🌉" },
  { slug: "admission", nameZh: "招生入学", nameEn: "Admissions", descZh: "本科与研究生申请、截止日与录取动态", descEn: "Ug and pg applications, deadlines and admissions", itemCount: 46, group: 2, icon: "🎓" },
  { slug: "campus", nameZh: "校园生活", nameEn: "Campus Life", descZh: "体育、社团、宿舍与校园日常", descEn: "Sports, clubs, halls and everyday campus", itemCount: 64, group: 2, icon: "🏫" },
  { slug: "event", nameZh: "活动讲座", nameEn: "Events & Lectures", descZh: "公开讲座、工作坊与报名中的活动", descEn: "Public lectures, workshops and open events", itemCount: 51, group: 2, icon: "📅" },
  { slug: "career", nameZh: "就业实习", nameEn: "Careers & Internships", descZh: "招聘会、岗位信息与职业发展", descEn: "Career fairs, openings and development", itemCount: 39, group: 2, icon: "💼" },
  { slug: "exchange", nameZh: "国际交流", nameEn: "Exchange & Study Abroad", descZh: "交换计划、游学与海外学习机会", descEn: "Exchange programmes and overseas study", itemCount: 32, group: 2, icon: "✈️" },
  { slug: "housing", nameZh: "宿舍与生活", nameEn: "Housing & Living", descZh: "宿舍申请、住宿生活与周边租房", descEn: "Hall applications and off-campus housing", itemCount: 21, group: 2, icon: "🏠" },
  { slug: "scholarship", nameZh: "奖学金资助", nameEn: "Scholarships", descZh: "入学奖学金、专项资助与申请通道", descEn: "Entrance scholarships, grants and applications", itemCount: 18, group: 2, icon: "🏆" },
  { slug: "admin", nameZh: "校务公告", nameEn: "Official Notices", descZh: "校历变更、政策与服务调整", descEn: "Calendar, policy and service updates", itemCount: 22, group: 2, icon: "📣" }
];

/** 主题三维分组目录头（原型 TOPIC_GROUPS 原样，顺序=group 0/1/2） */
export const NEWS_TOPIC_GROUPS: NewsTopicGroup[] = [
  { nameZh: "学院与部门", nameEn: "Faculties & Departments", subZh: "按学院视角聚合的科研、课程与活动动态", subEn: "Through each faculty’s lens" },
  { nameZh: "研究领域与话题", nameEn: "Research Areas & Themes", subZh: "跨学院的科研方向与社会议题", subEn: "Cross-faculty directions and themes" },
  { nameZh: "学生事务", nameEn: "Student Affairs", subZh: "入学到毕业的服务型主题", subEn: "Service topics from enrolment to graduation" }
];

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

/** 分类短标签 NEWS_CATEGORY_LABELS_ZH/EN 已迁 newsMapping.ts（#259：生产词表不落 mock 模块名下） */

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
  // research：2026-09-30 经 #202 治理转正（组 RESEARCH）——库内无 icon 列、按 slug 回填，
  // 转正主题须在此登记图标，否则目录卡名称前缀缺图（P3 缺口首例）
  { slug: "research", nameZh: "综合科研", nameEn: "Research", descZh: "跨学科综合科研动态与全校范围的研究要闻", descEn: "Cross-disciplinary and university-wide research updates", itemCount: 0, group: 1, icon: "🔬" },
  { slug: "admission", nameZh: "招生入学", nameEn: "Admissions", descZh: "本科与研究生申请、截止日与录取动态", descEn: "Ug and pg applications, deadlines and admissions", itemCount: 46, group: 2, icon: "🎓" },
  { slug: "campus", nameZh: "校园生活", nameEn: "Campus Life", descZh: "体育、社团、宿舍与校园日常", descEn: "Sports, clubs, halls and everyday campus", itemCount: 64, group: 2, icon: "🏫" },
  { slug: "event", nameZh: "活动讲座", nameEn: "Events & Lectures", descZh: "公开讲座、工作坊与报名中的活动", descEn: "Public lectures, workshops and open events", itemCount: 51, group: 2, icon: "📅" },
  { slug: "career", nameZh: "就业实习", nameEn: "Careers & Internships", descZh: "招聘会、岗位信息与职业发展", descEn: "Career fairs, openings and development", itemCount: 39, group: 2, icon: "💼" },
  { slug: "exchange", nameZh: "国际交流", nameEn: "Exchange & Study Abroad", descZh: "交换计划、游学与海外学习机会", descEn: "Exchange programmes and overseas study", itemCount: 32, group: 2, icon: "✈️" },
  { slug: "housing", nameZh: "宿舍与生活", nameEn: "Housing & Living", descZh: "宿舍申请、住宿生活与周边租房", descEn: "Hall applications and off-campus housing", itemCount: 21, group: 2, icon: "🏠" },
  { slug: "scholarship", nameZh: "奖学金资助", nameEn: "Scholarships", descZh: "入学奖学金、专项资助与申请通道", descEn: "Entrance scholarships, grants and applications", itemCount: 18, group: 2, icon: "🏆" },
  { slug: "admin", nameZh: "校务公告", nameEn: "Official Notices", descZh: "校历变更、政策与服务调整", descEn: "Calendar, policy and service updates", itemCount: 22, group: 2, icon: "📣" },
  // alumni：预置登记（提案引用已近转正阈值）——提前入注册表，转正上线即带图标零二次发布
  { slug: "alumni", nameZh: "校友", nameEn: "Alumni", descZh: "校友活动、校友故事与校友服务动态", descEn: "Alumni events, stories and services", itemCount: 0, group: 2, icon: "🤝" }
];

/** 主题三维分组目录头（原型 TOPIC_GROUPS 原样，顺序=group 0/1/2） */
export const NEWS_TOPIC_GROUPS: NewsTopicGroup[] = [
  { nameZh: "学院与部门", nameEn: "Faculties & Departments", subZh: "按学院视角聚合的科研、课程与活动动态", subEn: "Through each faculty’s lens" },
  { nameZh: "研究领域与话题", nameEn: "Research Areas & Themes", subZh: "跨学院的科研方向与社会议题", subEn: "Cross-faculty directions and themes" },
  { nameZh: "学生事务", nameEn: "Student Affairs", subZh: "入学到毕业的服务型主题", subEn: "Service topics from enrolment to graduation" }
];

/** ==================== 日报 mock（#241：30 天确定性仿真 fixture，vitest 专用） ==================== */

import type { NewsDailyDigest, NewsDailyDigestItem, NewsDailyDigestSummary } from "@/types/news";

/**
 * #238 原型专用 30 天仿真（2026-09-05 → 2026-10-04，30 期含休刊 7 期），
 * 覆盖呈批所需全部形态：
 * - 多类目头版（10-04，11 条 7 类目）；快讯溢出触发期（09-28，research 12 条 → 8+4）
 * - 周末休刊（09-05/06/13/19/26/27、10-03）；透明口径 chips（09-15 introDegraded）
 * - 目录首条标题预览（firstTitleZh/En 为后端只读字段的 mock 等价物）
 * 生成完全确定性（无随机数），窗口按 [D-1 08:00, D 08:00) HKT 落点。
 * 原型转正（#241）：生产/开发一律真实 API（USE_MOCK 仅 vitest 与显式 env 强制）；
 * 本 fixture 另供 vite dev 截图仿真中间件读取（VITE_DAILY_SIM=1，见 vite.config.ts）。
 */

type SimSourceKey = keyof typeof NEWS_SOURCES;

interface SimItem {
  category: NewsCategory;
  topics: string[];
  sourceKey: SimSourceKey;
  zh: string;
  en: string;
  sz: string;
  se: string;
}

/** 池条目（轻量日填充；每类目独立游标顺序消费，30 天内零重复） */
interface PoolEntry {
  cat: NewsCategory;
  zh: string;
  en: string;
  sz: string;
  se: string;
  topics: string[];
  sourceKey: SimSourceKey;
}

const P = (
  cat: NewsCategory,
  zh: string,
  en: string,
  sz: string,
  se: string,
  topics: string[],
  sourceKey: SimSourceKey
): PoolEntry => ({ cat, zh, en, sz, se, topics, sourceKey });

const SIM_POOL: PoolEntry[] = [
  // ---- research（20）----
  P("research", "理大研发新型导热界面材料，助力数据中心散热降耗", "PolyU develops new thermal interface material to cut data-centre cooling energy", "新材料可显著降低高功率芯片结温，已进入服务器厂商验证阶段。", "The material sharply cuts chip junction temperatures and enters server-vendor validation.", ["materials", "eng"], "official-media-release"),
  P("research", "理大团队提出城市热岛缓解新方案，获国际规划学会嘉奖", "PolyU team's urban heat-island mitigation plan honoured by planning society", "方案融合遥感数据与街区尺度模拟，为高密度城市降温提供路径。", "The plan blends remote-sensing data with block-scale simulation to cool dense cities.", ["city", "energy"], "official-media-release"),
  P("research", "理大与合作团队绘制大湾区空气质量高分辨率图谱", "High-resolution air-quality atlas of the Greater Bay Area published", "图谱揭示臭氧与颗粒物的跨界输送通道，支持区域协同治理。", "The atlas reveals cross-boundary transport of ozone and particulates for joint governance.", ["city", "gba"], "prn-polyu"),
  P("research", "理大研发可穿戴肌电传感器，助力中风康复训练", "Wearable EMG sensor developed at PolyU aids stroke rehabilitation", "传感器可实时反馈肌肉激活程度，居家康复依从性明显提升。", "Real-time muscle-activation feedback markedly improves home rehabilitation adherence.", ["biomed", "hss"], "official-media-release"),
  P("research", "理大团队破解锂电池快充析锂难题，成果刊于《焦耳》", "Fast-charging lithium plating problem cracked, published in Joule", "新型电解质添加剂抑制负极析锂，快充寿命提升逾五成。", "A new electrolyte additive suppresses lithium plating, extending fast-charge life by half.", ["energy", "materials"], "official-media-release"),
  P("research", "理大开发AI辅助青光眼筛查系统，进入社区试点", "AI glaucoma screening system enters community pilot", "系统对眼底照片的判读灵敏度接近专科医师，将覆盖十余间社区中心。", "Sensitivity approaches that of specialists; the pilot will cover a dozen community centres.", ["ai", "biomed"], "prn-polyu"),
  P("research", "理大学者在拓扑光子学取得理论突破", "Theoretical breakthrough in topological photonics", "研究阐明光子晶格中边界态的调控机制，为光子芯片设计提供新自由度。", "The work clarifies boundary-state control in photonic lattices, informing photonic-chip design.", ["research", "eng"], "official-media-release"),
  P("research", "理大团队研发海堤生态改良模块，兼顾防洪与生物多样性", "Eco-engineered seawall modules balance flood defence and biodiversity", "模块表面结构有利幼鱼与藻类附着，已在吐露港试验段安装。", "Surface textures encourage juvenile fish and algal settlement; installed at Tolo Harbour trial sections.", ["ce", "city"], "official-media-release"),
  P("research", "理大与合作方开发低轨卫星信道估计算法", "New channel-estimation algorithm for LEO satellites", "算法将高速移动场景下的通信中断率显著降低。", "The algorithm sharply reduces outages in high-mobility satellite links.", ["eng", "ai"], "prn-polyu"),
  P("research", "理大团队制备超疏水涂层，延长海洋结构物寿命", "Superhydrophobic coating extends marine structure lifespan", "涂层抗盐雾腐蚀与生物污损，维护周期可延长一倍。", "Resistant to salt fog and biofouling, doubling maintenance intervals.", ["materials", "ce"], "official-media-release"),
  P("research", "理大研究揭示久坐行为与青少年心理健康关联", "Study links sedentary behaviour to adolescent mental health", "追踪逾两千名中学生的数据显示课间轻度活动与情绪指标正相关。", "Data from 2,000+ secondary students links light between-class activity to better mood.", ["hss", "biomed"], "official-media-release"),
  P("research", "理大团队优化氢燃料电池催化剂，铂用量减半", "Hydrogen fuel-cell catalyst needs half the platinum", "核壳结构催化剂维持性能的同时大幅降低成本。", "A core-shell catalyst keeps performance while slashing cost.", ["energy", "materials"], "prn-polyu"),
  P("research", "理大开发联邦学习框架，医疗数据不出院即可训模型", "Federated learning framework trains models without moving hospital data", "框架通过隐私保护聚合连接多家医院，影像模型泛化能力提升。", "Privacy-preserving aggregation links hospitals and improves imaging-model generalisation.", ["ai", "biomed"], "official-media-release"),
  P("research", "理大学者参与深度月壤研究，分析水冰分布线索", "PolyU researchers join lunar soil study on water-ice distribution", "团队对月壤颗粒的分析为极区水冰赋存状态提供新证据。", "Analysis of lunar grains yields new evidence on polar water-ice occurrence.", ["research", "eng"], "official-media-release"),
  P("research", "理大团队研制软体抓手，可采撷深海脆弱生物", "Soft robotic gripper gently collects fragile deep-sea organisms", "抓手以仿生结构适应不规则形态，深海海试成功。", "A bio-inspired structure adapts to irregular shapes; sea trials succeeded.", ["eng", "biomed"], "prn-polyu"),
  P("research", "理大研究提出大湾区跨境数据流动治理框架", "GBA cross-border data governance framework proposed", "框架平衡科研数据共享与合规要求，获政策研讨会采纳讨论。", "The framework balances research data sharing with compliance and was tabled at a policy workshop.", ["gba", "ai"], "official-media-release"),
  P("research", "理大团队提升钙钛矿-硅叠层电池封装可靠性", "Perovskite-silicon tandem packaging made more reliable", "新型封装工艺抑制湿热老化衰减，户外实测寿命显著延长。", "New packaging suppresses damp-heat degradation, extending outdoor lifetime.", ["energy", "materials"], "official-media-release"),
  P("research", "理大开发无人机群协同巡检算法，用于桥梁安全监测", "Drone-swarm inspection algorithm monitors bridge safety", "多机协同覆盖率达单机的三倍，巡检周期缩短。", "Multi-drone coverage triples that of a single drone, shortening inspection cycles.", ["eng", "city"], "prn-polyu"),
  P("research", "理大团队揭示城市噪声暴露与睡眠质量的剂量效应", "Dose-response link between urban noise exposure and sleep quality", "研究为住宅隔音标准修订提供量化依据。", "The study quantifies evidence for revising residential sound-insulation standards.", ["city", "hss"], "official-media-release"),
  P("research", "理大研发快速筛查耐药菌的微流控芯片", "Microfluidic chip rapidly screens drug-resistant bacteria", "芯片将药敏试验从两天压缩到四小时。", "The chip compresses susceptibility testing from two days to four hours.", ["biomed", "eng"], "official-media-release"),
  // ---- campus（12）----
  P("campus", "赛马会学生宿舍村新增绿化中庭，正式开放", "New green courtyard opens at Jockey Club student village", "中庭设遮荫座椅与雨水花园，夜间有柔和照明。", "The courtyard adds shaded seating, a rain garden and soft night lighting.", ["campus"], "official-focus"),
  P("campus", "图书馆延长考试季开放时间至午夜", "Library extends hours to midnight for exam season", "十二月起连续四周，凭学生证入馆。", "For four weeks from December, entry with student ID.", ["campus"], "official-focus"),
  P("campus", "校园餐厅推出健康轻食新档口", "New healthy-eating outlet opens in campus restaurants", "档口提供热量标注餐单，营养师每周驻场咨询。", "Calorie-labelled menus with weekly dietitian sessions.", ["campus"], "official-focus"),
  P("campus", "理大龙舟队在香港龙舟锦标赛夺银", "PolyU dragon boat team takes silver at Hong Kong championships", "队伍以0.3秒之差憾失金牌，创历年最佳战绩。", "Missing gold by 0.3 seconds, the team logged its best-ever finish.", ["campus"], "youtube-main"),
  P("campus", "校内充电桩扩容，新增两处电动车停车位", "More EV charging points added on campus", "新车位位于李楼地下停车场，先到先得。", "New bays at the Li Building car park, first-come-first-served.", ["campus"], "official-focus"),
  P("campus", "学生会中秋游园会逾千人参与", "Students' Union Mid-Autumn fair draws over a thousand", "晚会设灯笼工作坊与非遗摊位，气氛热烈。", "Lantern workshops and heritage stalls drew lively crowds.", ["campus", "event"], "official-focus"),
  P("campus", "理大艺术空间展出学生摄影作品「城市肌理」", "Student photo exhibition \"Urban Fabric\" on show", "展览收录四十幅作品，聚焦香港街头的几何与光影。", "Forty works focus on geometry and light in Hong Kong streets.", ["campus"], "youtube-main"),
  P("campus", "校园步道无障碍改造完成", "Accessibility upgrade of campus walkways completed", "改造覆盖主要连廊坡道与 tactile guide 系统。", "Upgrades cover main ramps and the tactile guidance system.", ["campus"], "official-focus"),
  P("campus", "体育馆引入夜跑时段预约制", "Bookable night-running slots open at sports complex", "跑道十点后分段开放，保障安全与互不干扰。", "Track segments open after 10 pm for safety and mutual convenience.", ["campus"], "official-focus"),
  P("campus", "「理大农场」屋顶农圃迎来首季收成", "Rooftop farm logs first harvest", "蔬果将捐赠社区伙伴，种植团队全程由学生运营。", "Produce goes to community partners; the farm is fully student-run.", ["campus", "energy"], "youtube-main"),
  P("campus", "学生活动中心研讨室线上预约系统上线", "Online booking launches for student hub study rooms", "系统支持提前三天预约，爽约计入信用记录。", "Book up to three days ahead; no-shows feed a credit record.", ["campus"], "official-focus"),
  P("campus", "校史馆新增互动时间轴展项", "Interactive timeline exhibit added to heritage gallery", "访客可按年代检索九十年校史影像。", "Visitors can browse nine decades of archival footage by era.", ["campus", "event"], "official-focus"),
  // ---- event（10）----
  P("event", "杰出学人讲座：量子材料前沿本月开讲", "Distinguished lecture on quantum materials this month", "讲座面向全校开放，设线上直播通道。", "Open to all with a livestream channel.", ["event"], "official-events"),
  P("event", "理大创新开放日吸引逾三千中学生参观", "Innovation Open Day draws 3,000+ secondary students", "实验室导赏与动手工作坊全日爆满。", "Lab tours and hands-on workshops were fully booked all day.", ["event"], "official-events"),
  P("event", "创业系列工作坊接受报名，聚焦大湾区市场", "Startup workshop series opens for GBA-focused founders", "六节课程覆盖出海合规与供应链布局。", "Six sessions cover cross-border compliance and supply chains.", ["event", "gba"], "official-events"),
  P("event", "理大合唱团秋季音乐会将上演粤语合唱新作", "Choir autumn concert to premiere new Cantonese choral work", "音乐会于校内礼堂举行，收益捐助学生应急基金。", "Proceeds support the student emergency fund.", ["event"], "youtube-main"),
  P("event", "「科学与社会」跨学科论坛下月举行", "Interdisciplinary forum on science and society set for next month", "论坛汇聚人文学者与科学家对谈技术伦理。", "Humanists and scientists discuss the ethics of technology.", ["event", "hss"], "official-events"),
  P("event", "理大举办中学生机器人大赛，报名开启", "Robotics contest for secondary schools opens", "今年赛题加入AI视觉任务，决赛日在校园直播。", "This year adds AI-vision tasks; finals streamed on campus.", ["event", "ai"], "official-events"),
  P("event", "数据科学夏令营结营，展出学生项目", "Data science summer camp closes with project showcase", "十二组项目涵盖交通预测与文本挖掘。", "Twelve projects span traffic forecasting and text mining.", ["event", "ai"], "youtube-main"),
  P("event", "理大博物馆新展回顾校园九十年变迁", "Museum exhibition traces nine decades of campus history", "展览以物件与口述史并置呈现。", "Objects and oral histories sit side by side.", ["event", "campus"], "official-events"),
  P("event", "圆桌沙龙：生成式AI时代的教学创新", "Roundtable on teaching in the generative-AI era", "八位教师分享课程设计中的AI融入经验。", "Eight teachers share how they weave AI into course design.", ["event", "ai"], "official-events"),
  P("event", "理大交响乐团慈善音乐会售票开启", "PolyU orchestra charity concert tickets on sale", "曲目涵盖德沃夏克与当代委约新作。", "Programme spans Dvořák and a newly commissioned work.", ["event"], "official-events"),
  // ---- admission（6）----
  P("admission", "2027/28学年授课式硕士课程目录上线", "Taught postgraduate catalogue for 2027/28 goes live", "目录新增三个跨学科专业，申请通道明年初开放。", "Three interdisciplinary programmes added; applications open early next year.", ["admission"], "official-focus"),
  P("admission", "内地本科生招生说明会（线上）接受预约", "Mainland admission webinar open for booking", "说明会分理工与商科两场，设实时答疑。", "Two sessions for STEM and business with live Q&A.", ["admission"], "official-focus"),
  P("admission", "理大参与国际教育展，介绍联合学位项目", "PolyU to present joint-degree programmes at education fair", "招生团队将现场解答学分互认问题。", "Admissions staff will answer credit-recognition questions on site.", ["admission", "exchange"], "official-focus"),
  P("admission", "自资课程秋季入学截止日期临近", "Application deadline nears for self-financed autumn intake", "有意申请者须于月底前完成线上提交。", "Applicants must submit online by month-end.", ["admission"], "official-focus"),
  P("admission", "体艺特长生招生通道说明发布", "Admission scheme for sports and arts talents explained", "通道涵盖杰出运动员与视觉艺术专才两类。", "The scheme covers elite athletes and visual-arts talents.", ["admission", "campus"], "official-focus"),
  P("admission", "2027本科联合课程宣讲会接受报名", "Joint undergraduate curriculum briefing opens for registration", "宣讲会将介绍与海外伙伴合办的双学位结构。", "The briefing covers dual-degree structures with overseas partners.", ["admission", "exchange"], "official-focus"),
  // ---- scholarship（4）----
  P("scholarship", "研究生境外会议资助计划接受申请", "Postgraduate conference travel fund opens", "每人每学年上限一次，优先考虑报告论文者。", "Once per academic year, prioritising paper presenters.", ["scholarship"], "official-focus"),
  P("scholarship", "「明日领袖」奖学金新增大湾区企业赞助席位", "Tomorrow's Leaders scholarship adds GBA-sponsored seats", "赞助席位含暑期实习与导师配对。", "Sponsored seats include summer internships and mentors.", ["scholarship", "gba"], "official-focus"),
  P("scholarship", "政府奖学金计划校内提名启动", "Government scholarship internal nomination begins", "提名须经学院初审，材料截止下月中旬。", "Nominations require faculty screening; materials due mid-next-month.", ["scholarship"], "official-focus"),
  P("scholarship", "校友会急难助学金放宽申请门槛", "Alumni emergency grant widens eligibility", "家庭突发变故学生可随时提交申请。", "Students hit by sudden family hardship may apply anytime.", ["scholarship", "alumni"], "official-focus"),
  // ---- career（6）----
  P("career", "理大就业博览新增AI与绿色科技专区", "Career fair adds AI and green-tech zones", "两区合计逾四十家机构设摊。", "The two zones host 40+ organisations.", ["career", "ai"], "official-focus"),
  P("career", "校友职业分享会：从工学院到创科初创", "Alumni career talk: from engineering to startup", "两位创始人分享技术转化的第一手经验。", "Two founders share first-hand lessons in tech transfer.", ["career", "alumni"], "official-focus"),
  P("career", "暑期实习计划合作企业名单扩至逾二百家", "Summer internship partner list tops 200", "新增多家跨境远程实习岗位。", "New cross-border remote placements added.", ["career"], "official-focus"),
  P("career", "简历诊所一对一辅导时段开放预约", "CV clinic one-on-one sessions open", "顾问来自人力资源与行业导师团队。", "Advisers come from HR and industry mentor teams.", ["career"], "official-focus"),
  P("career", "毕业生就业调查显示起薪中位数上升", "Graduate employment survey shows rising median starting pay", "受访率创新高，数据经独立机构核验。", "A record response rate, verified by an independent body.", ["career"], "official-media-release"),
  P("career", "职场语言工作坊聚焦跨文化沟通", "Workplace language workshop spotlights cross-cultural communication", "工作坊含模拟谈判与邮件写作实训。", "Mock negotiations and email-writing drills included.", ["career", "exchange"], "official-focus"),
  // ---- exchange（7）----
  P("exchange", "与京都大学交换计划新增春季批次", "Kyoto University exchange adds spring cohort", "春季批次名额五名，学分转换细则同步更新。", "Five spring places; credit-transfer rules updated in step.", ["exchange"], "official-focus"),
  P("exchange", "「一带一路」暑期游学计划成果展举办", "Belt and Road summer programme showcase held", "展览呈现八条路线的学生调研成果。", "Student research from eight routes on display.", ["exchange", "gba"], "official-focus"),
  P("exchange", "理大与巴黎政治学院签署学生交流协议", "Student exchange pact signed with Sciences Po", "协议涵盖学期交换与暑期学校两档。", "The pact covers semester exchange and summer school.", ["exchange"], "official-media-release"),
  P("exchange", "海外服务学习计划招募志愿者", "Overseas service-learning programme recruits volunteers", "来年项目覆盖东南亚四个社区。", "Next year's projects span four Southeast Asian communities.", ["exchange", "hss"], "official-focus"),
  P("exchange", "交换生学分转换新指引发布", "New guidelines for exchange credit transfer", "指引明确课程匹配度评估流程与时限。", "The guidelines set course-matching assessment flow and timelines.", ["exchange", "admin"], "official-focus"),
  P("exchange", "理大学生赴新加坡参加青年领袖论坛", "Students join youth leadership forum in Singapore", "团队就城市韧性议题提交政策建议书。", "The team tabled policy briefs on urban resilience.", ["exchange"], "official-focus"),
  P("exchange", "寒假文化沉浸项目开放申请", "Winter cultural immersion programmes open", "项目分语言学习与田野考察两类。", "Tracks cover language study and field research.", ["exchange"], "official-focus"),
  // ---- admin（7）----
  P("admin", "十一月初校历调整：停课一日安排公布", "Calendar adjustment: one-day class suspension in early November", "涉及补课安排已在校历系统标注。", "Make-up arrangements are flagged in the calendar system.", ["admin"], "official-focus"),
  P("admin", "校园网络维护将影响周末部分服务", "Weekend IT maintenance to affect some services", "维护窗口为周日凌晨至六时。", "The window runs Sunday 00:00-06:00.", ["admin"], "official-focus"),
  P("admin", "学生事务处办公时间临时调整", "SAO opening hours temporarily adjusted", "周三下午暂停柜台服务，线上渠道不受影响。", "Counter service pauses Wednesday afternoon; online channels unaffected.", ["admin"], "official-focus"),
  P("admin", "校园扩建工程交通改道指引更新", "Traffic diversion guide updated for campus works", "北门行车路线调整，工期约八周。", "North gate routes change for about eight weeks.", ["admin", "campus"], "official-focus"),
  P("admin", "电子成绩单服务上线，可在线申请", "E-transcript service launches online", "申请后一个工作日内发出验证链接。", "Verified links issued within one working day.", ["admin"], "official-focus"),
  P("admin", "台风季应急指引更新，请注意最新安排", "Typhoon-season emergency guidance updated", "指引明确八号风球下的考试与活动处理原则。", "The guidance covers exams and events under storm signal No. 8.", ["admin"], "official-focus"),
  P("admin", "校车服务时刻表新学期起调整", "Campus shuttle timetable adjusts from the new term", "高峰班次加密，末班车延后半小时。", "Peak-hour frequency rises; last bus runs 30 minutes later.", ["admin", "campus"], "official-focus")
];

/** 轻量日计划：日期 → 类目序列（条目从池内顺序取用） */
const LIGHT_DAYS: { date: string; cats: NewsCategory[]; degraded?: boolean }[] = [
  { date: "2026-10-02", cats: ["research", "campus", "admin", "event"] },
  { date: "2026-10-01", cats: ["admission", "research", "event", "career"] },
  { date: "2026-09-30", cats: ["research", "research", "campus", "event", "exchange"] },
  { date: "2026-09-29", cats: ["research", "campus", "career"] },
  { date: "2026-09-25", cats: ["research", "exchange", "admin"] },
  { date: "2026-09-24", cats: ["research", "campus", "scholarship"] },
  { date: "2026-09-23", cats: ["event", "research", "admission", "campus"] },
  { date: "2026-09-21", cats: ["research", "career", "admin"] },
  { date: "2026-09-20", cats: ["research", "event"] },
  { date: "2026-09-17", cats: ["research", "campus", "exchange"] },
  { date: "2026-09-16", cats: ["research", "admission", "event"] },
  { date: "2026-09-15", cats: ["research", "campus", "career", "admin"], degraded: true },
  { date: "2026-09-14", cats: ["research", "event", "scholarship"] },
  { date: "2026-09-12", cats: ["research"] },
  { date: "2026-09-11", cats: ["research", "campus", "exchange"] },
  { date: "2026-09-09", cats: ["research", "campus"] },
  { date: "2026-09-08", cats: ["event", "admin"] },
  { date: "2026-09-07", cats: ["research", "exchange"] }
];

/** 展示日：手写条目（数组顺序 = 快照序，第 0 条即头条） */
const SHOWCASE_DAYS: { date: string; items: SimItem[] }[] = [
  {
    // 头版展示期：11 条 · 7 类目
    date: "2026-10-04",
    items: [
      { category: "research", topics: ["energy", "research"], sourceKey: "official-media-release", zh: "理大团队研发非贵金属海水制氢催化剂，制氢成本显著降低，成果刊于《自然·催化》", en: "PolyU team develops noble-metal-free seawater hydrogen catalyst, published in Nature Catalysis", sz: "理大团队以镍铁基复合材料替代贵金属催化剂，在模拟海水中实现稳定电解制氢，为规模化绿氢生产提供低成本路径。", se: "A nickel-iron composite replaces precious-metal catalysts, delivering stable seawater electrolysis and a low-cost route to green hydrogen." },
      { category: "event", topics: ["event"], sourceKey: "official-events", zh: "理大举办2026「杰出创科杯」颁奖典礼，24支学生团队获嘉奖", en: "PolyU hosts 2026 Innovation & Technology Cup awards; 24 student teams honoured", sz: "获奖项目涵盖医疗机器人与低碳建材，部分团队将代表理大出战区域赛。", se: "Winning projects span medical robots and low-carbon materials; some teams advance to regional finals." },
      { category: "admission", topics: ["admission"], sourceKey: "official-focus", zh: "2027/28学年本科招生线上宣讲会10月中旬开讲，覆盖五大学院课程", en: "Online admission briefings for 2027/28 open in mid-October across five faculties", sz: "宣讲会按学院分场，设实时答疑与校园生活分享环节。", se: "Sessions run per faculty with live Q&A and campus-life sharing." },
      { category: "research", topics: ["research", "materials"], sourceKey: "official-media-release", zh: "理大学者当选欧洲科学院院士，表彰其在智能材料领域贡献", en: "PolyU scholar elected Academia Europaea member for smart-materials contributions", sz: "当选学者的研究聚焦形状记忆合金与驱动器小型化。", se: "The scholar's work centres on shape-memory alloys and actuator miniaturisation." },
      { category: "campus", topics: ["campus"], sourceKey: "official-focus", zh: "图书馆推出24小时自习区预约服务，考试季前上线", en: "Library launches bookable 24-hour study zone ahead of exam season", sz: "自习区设一百二十个座位，扫码进出并计数管理。", se: "The zone seats 120 with QR-code entry and occupancy management." },
      { category: "career", topics: ["career"], sourceKey: "official-focus", zh: "秋季大型招聘会10月下旬举行，逾百家机构确认参展", en: "Autumn career fair set for late October with 100+ employers", sz: "招聘会分两日进行，首日面向工商与科技行业。", se: "The fair runs two days, opening with business and technology sectors." },
      { category: "exchange", topics: ["exchange", "eng"], sourceKey: "official-media-release", zh: "理大与慕尼黑工业大学扩展合作，新增双学位与联合科研通道", en: "PolyU and TUM expand ties with dual degrees and joint research tracks", sz: "合作首期聚焦先进制造与可持续能源两个方向。", se: "The first phase focuses on advanced manufacturing and sustainable energy." },
      { category: "admin", topics: ["admin"], sourceKey: "official-focus", zh: "10月13日重阳节：本校停课一天，校园服务调整安排公布", en: "Class suspension on 13 October Chung Yeung Festival; service changes announced", sz: "图书馆与体育设施按假日时间运作，校车暂停。", se: "Library and sports facilities follow holiday hours; shuttles suspended." },
      { category: "scholarship", topics: ["scholarship"], sourceKey: "official-focus", zh: "校长卓越奖学金开始接受提名，截止11月7日", en: "President's Excellence Scholarship nominations open until 7 November", sz: "提名由学院统一提交，奖励全面发展的本科新生。", se: "Faculties submit nominations for well-rounded undergraduate entrants." },
      { category: "event", topics: ["event", "ai"], sourceKey: "youtube-main", zh: "校长对话系列新片上线：跨学科教育如何应对AI时代", en: "New President's Dialogue episode: interdisciplinary education in the AI era", sz: "本期嘉宾为两位跨学科课程主任，片长约四十分钟。", se: "Two programme directors join the 40-minute conversation." },
      { category: "research", topics: ["eng", "biomed"], sourceKey: "prn-polyu", zh: "理大孵化初创获数千万元Pre-A融资，加速柔性传感器产业化", en: "PolyU-incubated startup raises tens of millions in Pre-A to scale flexible sensors", sz: "公司主打的贴附式生理监测贴片将扩产两倍。", se: "The wearable physiological-monitoring patch will triple capacity." }
    ]
  },
  {
    // 快讯溢出触发期：research 12 条 → 版面 8 + 快讯 4
    date: "2026-09-28",
    items: [
      { category: "research", topics: ["eng", "research"], sourceKey: "official-media-release", zh: "理大团队研制太赫兹超表面编码芯片，通信容量提升数倍", en: "PolyU's terahertz metasurface coding chip multiplies link capacity", sz: "芯片以可重构超表面实现波束赋形，实测速率提升四倍，为6G前传提供候选方案。", se: "A reconfigurable metasurface shapes beams and quadruples measured data rates, a candidate for 6G fronthaul." },
      { category: "admission", topics: ["admission"], sourceKey: "official-focus", zh: "研究生课程2027春季入学申请系统开放", en: "Spring 2027 postgraduate application portal opens", sz: "本轮开放三十余个授课式专业，截止十一月中。", se: "Over 30 taught programmes open, closing mid-November." },
      { category: "research", topics: ["ce", "energy"], sourceKey: "official-media-release", zh: "理大与合作团队揭示华南河口碳汇机制，成果刊于《自然·地球科学》", en: "Estuarine carbon-sink mechanism revealed in Nature Geoscience", sz: "研究量化红树林-滩涂交互带的碳埋藏通量，为蓝碳核算提供基准。", se: "The work quantifies carbon burial in mangrove-tidal flats, grounding blue-carbon accounting." },
      { category: "campus", topics: ["campus"], sourceKey: "official-focus", zh: "校园无障碍地图2.0上线，覆盖全部教学楼宇", en: "Accessibility map 2.0 covers every teaching building", sz: "地图标注无障碍入口、升降机与无障碍洗手间位置。", se: "The map marks accessible entrances, lifts and washrooms." },
      { category: "research", topics: ["biomed"], sourceKey: "prn-polyu", zh: "理大开发疟疾快速检测试纸，15分钟出结果", en: "Rapid malaria test strip delivers results in 15 minutes", sz: "试纸无需仪器即可判读，将在流行地区开展验证。", se: "Instrument-free readout; field validation begins in endemic regions." },
      { category: "event", topics: ["event", "ai"], sourceKey: "official-events", zh: "数据科学青年学者论坛在理大举行", en: "Young Scholars Forum on Data Science held at PolyU", sz: "论坛收到逾两百篇投稿，设三个分论坛。", se: "200+ submissions across three sub-forums." },
      { category: "research", topics: ["energy", "materials"], sourceKey: "official-media-release", zh: "理大团队提升固态电解质界面稳定性，固态电池循环寿命翻倍", en: "Solid-electrolyte interface stabilised, doubling solid-state battery cycle life", sz: "界面修饰层抑制枝晶生长，软包电池通过针刺测试。", se: "An interfacial coating suppresses dendrites; pouch cells passed nail-penetration tests." },
      { category: "admin", topics: ["admin"], sourceKey: "official-focus", zh: "校园网络升级维护公告：周日凌晨暂停部分服务", en: "Campus network upgrade: some services pause Sunday early hours", sz: "维护窗口为零时至六时，影响学习管理系统访问。", se: "The 00:00-06:00 window affects the learning management system." },
      { category: "research", topics: ["ce", "city"], sourceKey: "official-media-release", zh: "理大发布深海采矿沉积物再悬浮预测模型", en: "Deep-sea mining sediment re-suspension model released", sz: "模型为采矿环评提供羽流扩散范围估算工具。", se: "The model estimates plume spread for mining environmental reviews." },
      { category: "research", topics: ["biomed", "ai"], sourceKey: "official-media-release", zh: "理大与医院合作推出AI辅助骨折术后康复方案", en: "AI-assisted post-fracture rehabilitation programme launched with hospital partners", sz: "方案按影像随访自动调整负重建议，试点患者依从性提升三成。", se: "Imaging follow-ups auto-adjust loading advice; pilot adherence rose 30%." },
      { category: "research", topics: ["eng", "city"], sourceKey: "prn-polyu", zh: "理大团队研发高层建筑风振控制调谐装置", en: "Tuned device dampens wind-induced vibration in tall buildings", sz: "装置体积较传统TMD缩小四成，已装设于在建项目。", se: "The device is 40% smaller than conventional TMDs and installed on a live project." },
      { category: "research", topics: ["biomed", "materials"], sourceKey: "official-media-release", zh: "理大团队实现微塑料降解酶的定向进化", en: "Directed evolution yields enzymes that degrade microplastics", sz: "进化后的酶在常温下六小时降解率超过八成。", se: "Evolved enzymes break down over 80% within six hours at room temperature." },
      { category: "research", topics: ["hss", "biomed"], sourceKey: "official-media-release", zh: "理大发布老年跌倒风险居家感知系统", en: "Home sensing system flags elderly fall risk", sz: "系统以毫米波雷达识别步态异常，无须穿戴设备。", se: "Millimetre-wave radar spots gait anomalies without wearables." },
      { category: "research", topics: ["materials", "ce"], sourceKey: "prn-polyu", zh: "理大研制光催化自清洁混凝土，试点人行道落成", en: "Photocatalytic self-cleaning concrete piloted on a walkway", sz: "涂层分解氮氧化物并抗污，养护成本显著下降。", se: "The coating decomposes NOx and resists staining, cutting upkeep costs." },
      { category: "research", topics: ["eng", "ai"], sourceKey: "official-media-release", zh: "理大突破低轨卫星激光通信捕获跟踪技术", en: "Breakthrough in acquisition and tracking for LEO laser links", sz: "捕获时间缩短至秒级，支持星地高速链路。", se: "Acquisition shrinks to seconds, enabling high-rate ground links." },
      { category: "research", topics: ["ai", "biomed"], sourceKey: "prn-polyu", zh: "理大开放联邦学习医疗影像基准数据集", en: "PolyU releases federated medical-imaging benchmark", sz: "基准覆盖三家医院的脱敏胸片，供全球研究者复现。", se: "The benchmark spans de-identified chest X-rays from three hospitals for reproducible research." }
    ]
  },
  {
    // 版面分节展示期：9 条 · 6 类目
    date: "2026-09-18",
    items: [
      { category: "research", topics: ["materials", "biomed"], sourceKey: "official-media-release", zh: "理大研发智能织物，可连续监测心率变异性", en: "Smart fabric continuously monitors heart-rate variability", sz: "纤维电极经三十次水洗仍保持信号质量，适合日常穿戴。", se: "Fibre electrodes retain signal quality after 30 washes for everyday wear." },
      { category: "admission", topics: ["admission", "exchange"], sourceKey: "official-focus", zh: "2027本科联合课程宣讲会接受报名", en: "Joint undergraduate curriculum briefing opens for registration", sz: "宣讲会将介绍与海外伙伴合办的双学位结构与遴选方式。", se: "The briefing covers dual-degree structures and selection with overseas partners." },
      { category: "event", topics: ["event"], sourceKey: "official-events", zh: "理大创新开放日吸引逾三千中学生参观", en: "Innovation Open Day draws 3,000+ secondary students", sz: "实验室导赏与动手工作坊全日爆满，来年将增开场次。", se: "Fully booked tours and workshops; more sessions planned next year." },
      { category: "campus", topics: ["campus"], sourceKey: "official-focus", zh: "邵逸夫楼改造学习共享空间启用", en: "Refurbished learning commons opens in Shaw Building", sz: "空间设协作白板与静音舱，二十四小时开放。", se: "Collaborative whiteboards, focus pods and 24-hour access." },
      { category: "research", topics: ["energy", "materials"], sourceKey: "official-media-release", zh: "理大团队报道镁离子电池正极新进展", en: "New cathode progress reported for magnesium-ion batteries", sz: "层状正极材料的循环稳定性显著改善。", se: "Layered cathodes show markedly better cycle stability." },
      { category: "event", topics: ["event", "ai"], sourceKey: "official-events", zh: "圆桌沙龙：生成式AI时代的教学创新", en: "Roundtable on teaching in the generative-AI era", sz: "八位教师分享课程设计中的AI融入经验。", se: "Eight teachers share how they weave AI into course design." },
      { category: "career", topics: ["career"], sourceKey: "official-focus", zh: "简历诊所一对一辅导时段开放预约", en: "CV clinic one-on-one sessions open", sz: "顾问来自人力资源与行业导师团队，每周三晚开放。", se: "Advisers from HR and industry mentor teams; Wednesday evenings." },
      { category: "exchange", topics: ["exchange"], sourceKey: "official-focus", zh: "与京都大学交换计划新增春季批次", en: "Kyoto University exchange adds spring cohort", sz: "春季批次名额五名，学分转换细则同步更新。", se: "Five spring places; credit-transfer rules updated in step." },
      { category: "admin", topics: ["admin"], sourceKey: "official-focus", zh: "期末考试教室安排查询上线", en: "Final-exam room allocation lookup goes live", sz: "查询页按课程检索座位号与考场平面图。", se: "Search by course for seat numbers and venue maps." }
    ]
  },
  {
    // 中等展示期：8 条 · 5 类目
    date: "2026-09-22",
    items: [
      { category: "research", topics: ["eng", "city"], sourceKey: "official-media-release", zh: "理大团队研发声学超材料降噪窗", en: "Acoustic-metamaterial window cuts noise while ventilating", sz: "窗体在通风状态下降噪八分贝，适合临街住宅。", se: "The window cuts noise by 8 dB while staying ventilated." },
      { category: "campus", topics: ["campus"], sourceKey: "youtube-main", zh: "理大壁球校队蝉联大专锦标赛冠军", en: "PolyU squash team retains varsity championship", sz: "队伍在决赛以三比一胜出，实现两连冠。", se: "A 3-1 final win seals back-to-back titles." },
      { category: "research", topics: ["ce", "city"], sourceKey: "official-media-release", zh: "理大绘制城市暴雨内涝风险图", en: "Urban flash-flood risk map released", sz: "风险图融合排水模型与历史淹没记录，分辨率到街区。", se: "The map blends drainage models with flood records at block resolution." },
      { category: "event", topics: ["event"], sourceKey: "official-events", zh: "理大交响乐团慈善音乐会售票开启", en: "PolyU orchestra charity concert tickets on sale", sz: "曲目涵盖德沃夏克与当代委约新作。", se: "Programme spans Dvořák and a newly commissioned work." },
      { category: "admission", topics: ["admission", "exchange"], sourceKey: "official-focus", zh: "理大参与国际教育展，介绍联合学位项目", en: "PolyU to present joint-degree programmes at education fair", sz: "招生团队将现场解答学分互认问题。", se: "Admissions staff will answer credit-recognition questions on site." },
      { category: "career", topics: ["career"], sourceKey: "official-media-release", zh: "毕业生就业调查显示起薪中位数上升", en: "Graduate employment survey shows rising median starting pay", sz: "受访率创新高，数据经独立机构核验。", se: "A record response rate, verified by an independent body." },
      { category: "campus", topics: ["campus"], sourceKey: "official-focus", zh: "学生活动中心延长周末开放时间", en: "Student hub extends weekend hours", sz: "周末闭馆时间由六时延至十时。", se: "Weekend closing moves from 6 pm to 10 pm." },
      { category: "admin", topics: ["admin", "campus"], sourceKey: "official-focus", zh: "校车服务时刻表新学期起调整", en: "Campus shuttle timetable adjusts from the new term", sz: "高峰班次加密，末班车延后半小时。", se: "Peak-hour frequency rises; last bus runs 30 minutes later." }
    ]
  },
  {
    // 衔接真实 mock 资讯的展示期：钙钛矿头条
    date: "2026-09-10",
    items: [
      { category: "research", topics: ["energy", "materials", "eng"], sourceKey: "official-media-release", zh: "理大团队破解钙钛矿太阳能电池稳定性难题，成果刊于《自然·能源》", en: "PolyU team cracks perovskite solar-cell stability problem, published in Nature Energy", sz: "理大应用物理系团队提出新型界面钝化策略，将钙钛矿太阳能电池在高温高湿下的运行寿命显著延长，转换效率同时获得提升，为产业化落地扫清关键障碍。", se: "A new interfacial passivation strategy extends lifetime under heat and humidity while raising efficiency, clearing a key barrier to commercialisation." },
      { category: "admission", topics: ["admission"], sourceKey: "official-focus", zh: "2026/27学年春季入学补充录取开启", en: "Spring 2027 supplementary admissions open", sz: "少量名额面向转专业申请人开放。", se: "A small number of places open to transfer applicants." },
      { category: "event", topics: ["event", "campus"], sourceKey: "official-events", zh: "理大科学节周末开幕，免费向公众开放", en: "PolyU Science Festival opens this weekend, free to the public", sz: "设四十余项互动展项与科普讲座。", se: "Forty-plus interactive exhibits and popular-science talks." },
      { category: "admin", topics: ["admin", "campus"], sourceKey: "official-focus", zh: "邵逸夫体育馆维护，暂停开放两周", en: "Shaw Sports Complex closes two weeks for maintenance", sz: "改造期间课程调整至邻馆进行。", se: "Classes relocate to the neighbouring hall during works." }
    ]
  }
];

/** 休刊日（窗口内零可见条目；读侧弱化呈现） */
const EMPTY_DATES = ["2026-10-03", "2026-09-27", "2026-09-26", "2026-09-19", "2026-09-13", "2026-09-06", "2026-09-05"];

/** 池游标：每类目独立推进，30 天内零重复取用 */
const poolCursor: Partial<Record<NewsCategory, number>> = {};
function takeFromPool(cat: NewsCategory): PoolEntry {
  const pool = SIM_POOL.filter((entry) => entry.cat === cat);
  const used = poolCursor[cat] ?? 0;
  poolCursor[cat] = used + 1;
  return pool[used % pool.length];
}

/** 快照序时间梯：i=0（头条）最晚，逐条递减；跨零点后落到 D 日凌晨 */
function ladderTime(i: number): { dayOffset: 0 | 1; hh: number; mm: number } {
  const raw = 1360 - i * 71;
  const dayOffset: 0 | 1 = raw >= 480 ? 0 : 1;
  const norm = dayOffset === 0 ? raw : raw + 1440;
  return { dayOffset, hh: Math.floor(norm / 60), mm: norm % 60 };
}

function shiftDate(date: string, days: number): string {
  const d = new Date(`${date}T12:00:00+08:00`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

let simItemId = 900001;
function buildSimDay(date: string, simItems: SimItem[], degraded: boolean): NewsDailyDigest {
  const prevDate = shiftDate(date, -1);
  const items: NewsDailyDigestItem[] = simItems.map((sim, i) => {
    const { dayOffset, hh, mm } = ladderTime(i);
    const reg = NEWS_SOURCES[sim.sourceKey];
    return {
      itemId: simItemId++,
      seq: i + 1,
      url: "https://www.polyu.edu.hk/media/media-releases/",
      titleZh: sim.zh,
      titleEn: sim.en,
      summaryZh: sim.sz,
      summaryEn: sim.se,
      category: sim.category,
      topics: sim.topics,
      publishTime: `${dayOffset === 0 ? prevDate : date}T${String(hh).padStart(2, "0")}:${String(mm).padStart(2, "0")}:00+08:00`,
      source: {
        sourceKey: reg.sourceKey,
        platform: reg.platform,
        official: reg.official,
        displayName: reg.labelZh,
        displayNameEn: reg.labelEn
      }
    };
  });
  const month = Number(date.slice(5, 7));
  const day = Number(date.slice(8, 10));
  return {
    digestDate: date,
    windowStart: `${prevDate}T08:00:00+08:00`,
    windowEnd: `${date}T08:00:00+08:00`,
    introZh: `本期覆盖 ${month}月${day}日前一日 24 小时内的公开动态，共 ${items.length} 条；头版为发布时间最新的一条，以下按版面归类呈现。`,
    introEn: `This issue covers the 24 hours to 08:00 HKT on ${date} — ${items.length} items, led by the newest, grouped by section.`,
    storedIntroSource: "fallback",
    introDegraded: degraded,
    itemCount: items.length,
    visibleCount: items.length,
    disqualifiedCount: degraded ? 1 : 0,
    items,
    buildTime: `${date}T08:40:00+08:00`
  };
}

function buildEmptyDay(date: string): NewsDailyDigest {
  return {
    digestDate: date,
    windowStart: `${shiftDate(date, -1)}T08:00:00+08:00`,
    windowEnd: `${date}T08:00:00+08:00`,
    introZh: "",
    introEn: "",
    storedIntroSource: "empty",
    introDegraded: false,
    itemCount: 0,
    visibleCount: 0,
    disqualifiedCount: 0,
    items: [],
    buildTime: `${date}T08:40:00+08:00`
  };
}

function deriveMockDigests(): NewsDailyDigest[] {
  const byDate = new Map<string, { items: SimItem[]; degraded: boolean }>();
  for (const day of SHOWCASE_DAYS) {
    byDate.set(day.date, { items: day.items, degraded: false });
  }
  for (const day of LIGHT_DAYS) {
    byDate.set(day.date, { items: day.cats.map((cat) => {
      const p = takeFromPool(cat);
      return { category: p.cat, topics: p.topics, sourceKey: p.sourceKey, zh: p.zh, en: p.en, sz: p.sz, se: p.se };
    }), degraded: day.degraded ?? false });
  }
  const all = [...byDate.entries()].map(([date, plan]) => buildSimDay(date, plan.items, plan.degraded));
  for (const date of EMPTY_DATES) {
    all.push(buildEmptyDay(date));
  }
  // 日期倒序（最新一期在前），完整覆盖 2026-09-05 → 2026-10-04 共 30 期
  return all.sort((a, b) => (a.digestDate < b.digestDate ? 1 : -1));
}

/** 日报 mock fixture（#238 原型 30 天仿真，日期倒序） */
export const MOCK_DAILY_DIGESTS: NewsDailyDigest[] = deriveMockDigests();

/** 日报目录 mock（由 fixture 派生；firstTitle* 为后端只读字段的 mock 等价物） */
export const MOCK_DAILY_DIGEST_SUMMARIES: NewsDailyDigestSummary[] = MOCK_DAILY_DIGESTS.map((digest) => ({
  digestDate: digest.digestDate,
  itemCount: digest.itemCount,
  introSource: digest.storedIntroSource,
  buildTime: digest.buildTime,
  firstTitleZh: digest.items[0]?.titleZh ?? null,
  firstTitleEn: digest.items[0]?.titleEn ?? null
}));

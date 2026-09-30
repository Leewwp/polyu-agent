/**
 * 校历关键日期类型：字段对齐 /public/calendar/key-dates 的 KeyDateBoardVO
 * （#193；双语字段数据级直读，不引入 i18n 框架——资讯流判例）。
 * 分类/倒计数为后端 HKT 锚点计算结果（board.today 同源透出），前端零日期运算。
 */

/** 日期精度四档（合同§3；fuzzy 不伪造具体日） */
export type KeyDatePrecision = "exact-day" | "exact-range" | "onwards" | "fuzzy";

/** 查询侧时效分类（后端 HKT 锚点每次请求重算，不写回库） */
export type KeyDatePhase = "today" | "ongoing" | "upcoming" | "recent" | "archived" | "undated";

/** 源状态三态+人工停用（三态口径：退化/隔离不得展示为权威最新） */
export type KeyDateSourceState = "normal" | "degraded" | "isolated" | "manual_disabled";

export interface KeyDateItem {
  uid: string;
  academicYear: string;
  term: string;
  titleEn: string;
  /** 词表缺词 null=回退英文（#192 落库约定） */
  titleZh: string | null;
  precision: KeyDatePrecision;
  /** YYYY-MM-DD（fuzzy 为 null） */
  dateStart: string | null;
  /** YYYY-MM-DD（仅 exact-range） */
  dateEnd: string | null;
  /** 模糊窗原文桶（仅 fuzzy） */
  fuzzyHint: string | null;
  /** 官方人群限制原文（不得省略） */
  audienceText: string | null;
  sourceUrl: string;
  phase: KeyDatePhase;
  /** 今日→date_start 天数；仅 exact-day/exact-range（onwards/fuzzy 不倒计时） */
  daysUntil: number | null;
}

export interface KeyDateSourceStatus {
  sourceKey: string;
  role: "writer" | "verifier";
  enabled: boolean;
  state: KeyDateSourceState;
  coverageAcademicYear: string | null;
  lastSuccessAt: string | null;
  degradedStreak: number | null;
}

export interface KeyDateBoard {
  coverageAcademicYear: string | null;
  lastFullSyncAt: string | null;
  /** 服务端分类锚点（YYYY-MM-DD，HKT） */
  today: string;
  anySourceAbnormal: boolean;
  sources: KeyDateSourceStatus[];
  /** 进行中+今日+即将（date_start 升序；首页卡片取前 K 条同序） */
  currentAndUpcoming: KeyDateItem[];
  /** 过期 ≤N 天（结束日倒序） */
  recentPast: KeyDateItem[];
  /** 过期 >N 天（结束日倒序；封顶条数） */
  archived: KeyDateItem[];
  /** archived 全量计数（可大于 archived.length） */
  archivedTotal: number;
  /** fuzzy 模糊窗（无具体日） */
  undated: KeyDateItem[];
}

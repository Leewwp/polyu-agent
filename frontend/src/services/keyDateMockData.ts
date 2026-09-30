import type { KeyDateBoard } from "@/types/keyDate";

/**
 * 校历关键日期 mock fixture（vitest 专用；真数据接线后与 newsMockData 同判例保留）。
 *
 * 覆盖三态口径+倒计时门+分段断言所需形态：daysUntil/phase 均为「后端 HKT 锚点
 * 计算结果」的既成值（前端零日期运算，测试不依赖真实时钟）——today 锚点
 * 2026-09-30，onwards 行无 daysUntil（不倒计时）、fuzzy 行无 date_*。
 */
export const MOCK_KEY_DATE_BOARD: KeyDateBoard = {
  coverageAcademicYear: "2026/27",
  lastFullSyncAt: "2026-09-29T07:31:00",
  today: "2026-09-30",
  anySourceAbnormal: true,
  sources: [
    {
      sourceKey: "cal-academic-calendar",
      role: "writer",
      enabled: true,
      state: "normal",
      coverageAcademicYear: "2026/27",
      lastSuccessAt: "2026-09-29T07:31:00",
      degradedStreak: 0
    },
    {
      sourceKey: "cal-fee-payment-annual",
      role: "writer",
      enabled: true,
      state: "degraded",
      coverageAcademicYear: "2026/27",
      // 退化源：last_success_at 停在上次完整发布（不刷新=数据截至证据）
      lastSuccessAt: "2026-09-20T07:31:00",
      degradedStreak: 2
    },
    {
      sourceKey: "cal-timetable-exam-results",
      role: "writer",
      enabled: true,
      state: "isolated",
      coverageAcademicYear: "2026/27",
      lastSuccessAt: "2026-09-18T07:31:00",
      degradedStreak: 3
    },
    {
      sourceKey: "cal-exam-timetable",
      role: "verifier",
      enabled: true,
      state: "normal",
      coverageAcademicYear: "2026/27",
      lastSuccessAt: "2026-09-29T07:31:00",
      degradedStreak: 0
    },
    {
      sourceKey: "cal-assessment-results",
      role: "writer",
      enabled: true,
      state: "normal",
      coverageAcademicYear: "2026/27",
      lastSuccessAt: "2026-09-29T07:31:00",
      degradedStreak: 0
    }
  ],
  currentAndUpcoming: [
    {
      uid: "a".repeat(64),
      academicYear: "2026/27",
      term: "S1",
      titleEn: "Add/Drop Period (Semester One)",
      titleZh: "第一学期增退期",
      precision: "exact-range",
      dateStart: "2026-09-28",
      dateEnd: "2026-10-04",
      fuzzyHint: null,
      audienceText: null,
      sourceUrl: "https://www.polyu.edu.hk/ar/students-in-taught-programmes/academic-calendar/",
      phase: "ongoing",
      daysUntil: -2
    },
    {
      uid: "b".repeat(64),
      academicYear: "2026/27",
      term: "S1",
      titleEn: "China National Day (classes suspended)",
      titleZh: "国庆日（停课）",
      precision: "exact-day",
      dateStart: "2026-10-01",
      dateEnd: null,
      fuzzyHint: null,
      audienceText: null,
      sourceUrl: "https://www.polyu.edu.hk/ar/students-in-taught-programmes/academic-calendar/",
      phase: "upcoming",
      daysUntil: 1
    },
    {
      uid: "c".repeat(64),
      academicYear: "2026/27",
      term: "S1",
      titleEn: "First instalment payment deadline (Government Grant/Loan)",
      titleZh: null, // 词表缺词=回退英文（判例断言用）
      precision: "exact-day",
      dateStart: "2026-10-08",
      dateEnd: null,
      fuzzyHint: null,
      audienceText: "Government Grant/Loan students",
      sourceUrl: "https://www.polyu.edu.hk/ar/students-in-taught-programmes/annual-schedules/fee-payment/",
      phase: "upcoming",
      daysUntil: 8
    },
    {
      uid: "d".repeat(64),
      academicYear: "2026/27",
      term: "S1",
      titleEn: "Student Identity Card collection starts (expiry end of August)",
      titleZh: "学生证领取开始（八月底到期批次）",
      precision: "onwards",
      dateStart: "2026-10-12",
      dateEnd: null,
      fuzzyHint: null,
      audienceText: "students whose student identity cards are due to expire in end of August",
      sourceUrl: "https://www.polyu.edu.hk/ar/students-in-taught-programmes/annual-schedules/fee-payment/",
      phase: "upcoming",
      daysUntil: null // onwards 不进倒计时（开放起点不伪造截止语义）
    },
    {
      uid: "e".repeat(64),
      academicYear: "2026/27",
      term: "S1",
      titleEn: "Semester One Examination Period",
      titleZh: "第一学期考试期",
      precision: "exact-range",
      dateStart: "2026-12-14",
      dateEnd: "2026-12-23",
      fuzzyHint: null,
      audienceText: null,
      sourceUrl: "https://www.polyu.edu.hk/ar/students-in-taught-programmes/annual-schedules/timetable-exam-assessment/",
      phase: "upcoming",
      daysUntil: 75
    }
  ],
  recentPast: [
    {
      uid: "f".repeat(64),
      academicYear: "2026/27",
      term: "AY",
      titleEn: "Semester One commences",
      titleZh: "第一学期开学",
      precision: "exact-day",
      dateStart: "2026-09-21",
      dateEnd: null,
      fuzzyHint: null,
      audienceText: null,
      sourceUrl: "https://www.polyu.edu.hk/ar/students-in-taught-programmes/academic-calendar/",
      phase: "recent",
      daysUntil: -9
    }
  ],
  archived: [
    {
      uid: "1".repeat(64),
      academicYear: "2025/26",
      term: "S2",
      titleEn: "Semester Two Examination Period (2025/26)",
      titleZh: "第二学期考试期（2025/26）",
      precision: "exact-range",
      dateStart: "2026-05-11",
      dateEnd: "2026-05-23",
      fuzzyHint: null,
      audienceText: null,
      sourceUrl: "https://www.polyu.edu.hk/ar/students-in-taught-programmes/academic-calendar/",
      phase: "archived",
      daysUntil: -131
    }
  ],
  archivedTotal: 3,
  undated: [
    {
      uid: "9".repeat(64),
      academicYear: "2026/27",
      term: "S1",
      titleEn: "Examination timetable release (window)",
      titleZh: "考试时间表发布（窗口）",
      precision: "fuzzy",
      dateStart: null,
      dateEnd: null,
      fuzzyHint: "Late October 2026",
      audienceText: null,
      sourceUrl: "https://www.polyu.edu.hk/ar/students-in-taught-programmes/examination-information/examination-timetable-and-arrangements/",
      phase: "undated",
      daysUntil: null
    }
  ]
};

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { KeyDatesPage } from "./KeyDatesPage";
import { FeedLangProvider } from "@/components/feed/feedLang";
import { fetchKeyDateBoard } from "@/services/keyDateService";
import { MOCK_KEY_DATE_BOARD } from "@/services/keyDateMockData";
import type { KeyDateBoard } from "@/types/keyDate";

/**
 * 公开关键日期页（#193 票面验收逐条）：
 * - 三态口径不冒充最新：源异常横幅「部分来源近期同步异常——以下为最后完整
 *   版本（截至 …）」+ 逐源状态（正常/退化保留最后完整版本/自动隔离/人工停用）
 *   + 数据截至时间原样透出（lastFullSyncAt=2026-09-29T07:31 → 9月29日 07:31）；
 * - 四类 eStudent/邮件独发节点缺失声明呈现（个人考试时间表/个人缴费截止日/
 *   Add-Drop 结果/留位费截止日）+ 页脚「以 eStudent 及校务邮件为准」；
 * - 分段：即将到来（倒计时徽章）/近期已过/已归档（默认折叠+计数+封顶注）/
 *   日期待定（fuzzy 原文窗桶，不伪造具体日、不倒计时）；
 * - 覆盖学年+最近完整同步头部；失败态降级提示以 eStudent 为准。
 */

vi.mock("@/services/keyDateService", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/services/keyDateService")>();
  return { ...actual, fetchKeyDateBoard: vi.fn() };
});

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/key-dates"]}>
      <FeedLangProvider>
        <KeyDatesPage />
      </FeedLangProvider>
    </MemoryRouter>
  );
}

describe("KeyDatesPage", () => {
  beforeEach(() => {
    vi.mocked(fetchKeyDateBoard).mockReset();
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    window.localStorage.removeItem("polyu.feed.lang");
  });

  it("renders coverage year and last full sync header (正常态基线)", async () => {
    vi.mocked(fetchKeyDateBoard).mockResolvedValue(MOCK_KEY_DATE_BOARD);
    renderPage();

    await waitFor(() => expect(screen.getByText("覆盖学年 2026/27")).toBeTruthy());
    // lastFullSyncAt=2026-09-29T07:31:00 → 9月29日 07:31（最近完整同步口径）
    expect(screen.getByText("最近完整同步 9月29日 07:31")).toBeTruthy();
  });

  it("shows degraded banner as last complete version, not latest (三态不冒充最新)", async () => {
    vi.mocked(fetchKeyDateBoard).mockResolvedValue(MOCK_KEY_DATE_BOARD);
    renderPage();

    await waitFor(() => expect(screen.getByText(/部分来源近期同步异常/)).toBeTruthy());
    // 「以下为最后完整版本（截至 9月29日 07:31）」——数据截至明示，不冒充最新
    expect(screen.getByText(/以下为最后完整版本（截至 9月29日 07:31）/)).toBeTruthy();
    // 横幅与页脚两处都以 eStudent 及校务邮件为准（口径两处明示）
    expect(screen.getAllByText(/以 eStudent 及校务邮件为准/).length).toBeGreaterThanOrEqual(2);
  });

  it("lists per-source three states in source panel", async () => {
    vi.mocked(fetchKeyDateBoard).mockResolvedValue(MOCK_KEY_DATE_BOARD);
    renderPage();

    await waitFor(() => expect(screen.getByText("来源状态")).toBeTruthy());
    // 三个正常源（academic-calendar/exam-timetable/assessment-results）各一枚状态章
    expect(screen.getAllByText("正常").length).toBe(3);
    expect(screen.getByText("退化（保留最后完整版本）")).toBeTruthy();
    expect(screen.getByText("自动隔离（数据为隔离前版本）")).toBeTruthy();
    // 退化源的数据截至原样透出（lastSuccessAt=2026-09-20，不被任何展示逻辑改写）
    expect(screen.getByText(/上次完整同步 9月20日 07:31/)).toBeTruthy();
  });

  it("renders four personal-node missing declarations (四类缺失声明)", async () => {
    vi.mocked(fetchKeyDateBoard).mockResolvedValue(MOCK_KEY_DATE_BOARD);
    renderPage();

    await waitFor(() => expect(screen.getByText("本页不收录的个人信息")).toBeTruthy());
    expect(screen.getByText("个人考试时间表", { exact: false })).toBeTruthy();
    expect(screen.getByText("个人缴费截止日", { exact: false })).toBeTruthy();
    expect(screen.getByText("Add-Drop 结果", { exact: false })).toBeTruthy();
    expect(screen.getByText("留位费截止日", { exact: false })).toBeTruthy();
    expect(screen.getByText(/本页不做补录/)).toBeTruthy();
    // 页脚口径原文
    expect(
      screen.getByText("关键日期整理自理大公开页面，仅供快速参考——一切以 eStudent 及校务邮件为准。")
    ).toBeTruthy();
  });

  it("renders upcoming section with countdown badges and audience text", async () => {
    vi.mocked(fetchKeyDateBoard).mockResolvedValue(MOCK_KEY_DATE_BOARD);
    renderPage();

    await waitFor(() => expect(screen.getByText("即将到来 · 进行中")).toBeTruthy());
    expect(screen.getByText("第一学期增退期")).toBeTruthy();
    expect(screen.getByText("进行中")).toBeTruthy();
    expect(screen.getByText("明天")).toBeTruthy();
    expect(screen.getByText("8 天后")).toBeTruthy();
    // 受众原文不得省略（合同§2）
    expect(screen.getByText(/Government Grant\/Loan students/)).toBeTruthy();
  });

  it("fuzzy window shows original hint without fabricated date; onwards shows open start", async () => {
    vi.mocked(fetchKeyDateBoard).mockResolvedValue(MOCK_KEY_DATE_BOARD);
    renderPage();

    await waitFor(() => expect(screen.getByText("日期待定（官方未公布具体日）")).toBeTruthy());
    // fuzzy=原文窗桶直读，不伪造具体日
    expect(screen.getByText("Late October 2026")).toBeTruthy();
    // onwards=开放起点（官方真实日期非伪造）：10月12日 起，无倒计时徽章
    expect(screen.getByText("10月12日 起")).toBeTruthy();
    expect(screen.queryByText("12 天后")).toBeNull();
  });

  it("archives collapsed by default with count; expands on click with cap note", async () => {
    vi.mocked(fetchKeyDateBoard).mockResolvedValue(MOCK_KEY_DATE_BOARD);
    renderPage();

    await waitFor(() => expect(screen.getByText("已归档（3）")).toBeTruthy());
    // 默认折叠：归档行不渲染
    expect(screen.queryByText("第二学期考试期（2025/26）")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: /已归档（3）/ }));
    expect(screen.getByText("第二学期考试期（2025/26）")).toBeTruthy();
    // archivedTotal=3 > 返回 1 条 → 封顶注（显示最近 1 条）
    expect(screen.getByText("已归档共 3 条，显示最近 1 条")).toBeTruthy();
  });

  it("degrades gracefully on fetch failure with eStudent note (flag 关=404 口径)", async () => {
    vi.mocked(fetchKeyDateBoard).mockRejectedValue(new Error("404"));
    renderPage();

    await waitFor(() => expect(screen.getByText(/关键日期暂不可用/)).toBeTruthy());
    expect(screen.getByText(/以 eStudent 及校务邮件为准/)).toBeTruthy();
  });

  it("renders all-normal board without banner", async () => {
    const board: KeyDateBoard = {
      ...MOCK_KEY_DATE_BOARD,
      anySourceAbnormal: false,
      sources: MOCK_KEY_DATE_BOARD.sources.map((s) => ({ ...s, state: "normal", degradedStreak: 0 }))
    };
    vi.mocked(fetchKeyDateBoard).mockResolvedValue(board);
    renderPage();

    await waitFor(() => expect(screen.getByText("覆盖学年 2026/27")).toBeTruthy());
    expect(screen.queryByText(/部分来源近期同步异常/)).toBeNull();
    // 五源全部正常态（无横幅）
    expect(screen.getAllByText("正常").length).toBe(5);
  });
});

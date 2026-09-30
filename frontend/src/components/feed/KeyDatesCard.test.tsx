import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { KeyDatesCard } from "./KeyDatesCard";
import { FeedLangProvider } from "@/components/feed/feedLang";
import { fetchKeyDateBoard } from "@/services/keyDateService";
import { MOCK_KEY_DATE_BOARD } from "@/services/keyDateMockData";

/**
 * 首页关键日期卡（#193）：
 * - 临近度序前 4 条渲染（第 5 条截断）——currentAndUpcoming 即后端排好的
 *   临近度序，卡片只 slice；
 * - 倒计时徽章口径：今日/进行中/明天/N 天后；onwards 不倒计时（合同§4
 *   倒计时门=exact-day/exact-range）；
 * - 缺词回退：titleZh=null 行中文态回落英文；
 * - 源异常小字「数据截至 …」不冒充最新；「查看全部 ›」入 /key-dates；
 * - 抓取失败整卡隐藏（辅助卡不占错误面）。
 */

vi.mock("@/services/keyDateService", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/services/keyDateService")>();
  return { ...actual, fetchKeyDateBoard: vi.fn() };
});

function renderCard() {
  return render(
    <MemoryRouter>
      <FeedLangProvider>
        <KeyDatesCard />
      </FeedLangProvider>
    </MemoryRouter>
  );
}

describe("KeyDatesCard", () => {
  beforeEach(() => {
    vi.mocked(fetchKeyDateBoard).mockReset();
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    window.localStorage.removeItem("polyu.feed.lang");
  });

  it("renders top-4 by proximity with countdown badges (5th row truncated)", async () => {
    vi.mocked(fetchKeyDateBoard).mockResolvedValue(MOCK_KEY_DATE_BOARD);
    renderCard();

    await waitFor(() => expect(screen.getByText("关键日期")).toBeTruthy());
    // 前 4 条：增退期（进行中）/ 国庆日（明天）/ 缴费截止（8 天后）/ 学生证领取（onwards 无徽章）
    expect(screen.getByText("第一学期增退期")).toBeTruthy();
    expect(screen.getByText("国庆日（停课）")).toBeTruthy();
    expect(screen.getByText("学生证领取开始（八月底到期批次）")).toBeTruthy();
    // 徽章：进行中 / 明天 / 8 天后
    expect(screen.getByText("进行中")).toBeTruthy();
    expect(screen.getByText("明天")).toBeTruthy();
    expect(screen.getByText("8 天后")).toBeTruthy();
    // 第 5 条（考试期，75 天后）被 card-limit 截断
    expect(screen.queryByText("第一学期考试期")).toBeNull();
  });

  it("falls back to English title when titleZh is missing (词表缺词回退)", async () => {
    vi.mocked(fetchKeyDateBoard).mockResolvedValue(MOCK_KEY_DATE_BOARD);
    renderCard();

    await waitFor(() =>
      expect(screen.getByText("First instalment payment deadline (Government Grant/Loan)")).toBeTruthy()
    );
    // 中文态下缺词行不渲染中文标题
    expect(screen.queryByText(/^第一.*缴费/)).toBeNull();
  });

  it("shows data-as-of note when sources abnormal (不冒充最新)", async () => {
    vi.mocked(fetchKeyDateBoard).mockResolvedValue(MOCK_KEY_DATE_BOARD);
    renderCard();

    await waitFor(() =>
      // lastFullSyncAt=2026-09-29T07:31 → 数据截至 9月29日（部分来源同步异常）
      expect(screen.getByText(/部分来源同步异常，数据截至 9月29日/)).toBeTruthy()
    );
  });

  it("links to /key-dates full page", async () => {
    vi.mocked(fetchKeyDateBoard).mockResolvedValue(MOCK_KEY_DATE_BOARD);
    renderCard();

    await waitFor(() => expect(screen.getByText("关键日期")).toBeTruthy());
    const link = screen.getByRole("link", { name: "查看全部 ›" });
    expect(link.getAttribute("href")).toBe("/key-dates");
  });

  it("hides the whole card on fetch failure (flag off / network error)", async () => {
    vi.mocked(fetchKeyDateBoard).mockRejectedValue(new Error("404"));
    const { container } = renderCard();

    await waitFor(() => expect(vi.mocked(fetchKeyDateBoard)).toHaveBeenCalled());
    // 等一拍让 catch 分支 settle，再断言整卡未渲染
    await Promise.resolve();
    expect(container.querySelector("section")).toBeNull();
    expect(screen.queryByText("关键日期")).toBeNull();
  });

  it("renders English view when stored lang is en", async () => {
    window.localStorage.setItem("polyu.feed.lang", "en");
    vi.mocked(fetchKeyDateBoard).mockResolvedValue(MOCK_KEY_DATE_BOARD);
    renderCard();

    await waitFor(() => expect(screen.getByText("Key dates")).toBeTruthy());
    expect(screen.getByText("in 8 days")).toBeTruthy();
  });
});

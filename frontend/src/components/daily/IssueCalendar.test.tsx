import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { IssueCalendar } from "./IssueCalendar";
import { FeedLangProvider } from "@/components/feed/feedLang";
import type { NewsDailyDigestSummary } from "@/types/news";

/**
 * 报眼月历组件（#242）：各日格状态渲染+月切换限存档边界。
 * - 状态（data-state 可编程识别）：current=本期红底（aria-current="date"）、
 *   published=红点、recess=灰点（可点）、none=淡字不可点；今天=红框（data-today）；
 * - 月切换：存档范围=目录（倒序）首末两期所在月；越界方向 disabled 不可进入；
 * - selectedDate 变化（路由翻期）时月视图跟随；
 * - spacious 档触控目标 ≥44px 高宽（#232 口径，类名口径断言）。
 */

function summary(digestDate: string, itemCount: number): NewsDailyDigestSummary {
  return {
    digestDate,
    itemCount,
    introSource: itemCount === 0 ? "empty" : "llm",
    buildTime: `${digestDate}T08:40:00+08:00`,
    firstTitleZh: itemCount === 0 ? null : `标题${digestDate}`,
    firstTitleEn: itemCount === 0 ? null : `Title ${digestDate}`
  };
}

/**
 * 存档（日期倒序）：2026-09-28 ~ 2026-10-03——min 月 2026-09、max 月 2026-10。
 * 09-28 休刊（灰点）、10-01 无刊（目录外）、10-02 已出刊+今天、10-03 本期。
 */
const SUMMARIES: NewsDailyDigestSummary[] = [
  summary("2026-10-03", 11),
  summary("2026-10-02", 4),
  summary("2026-09-28", 0)
];

function renderCalendar(spacious = false, selectedDate = "2026-10-03") {
  return render(
    <MemoryRouter>
      <FeedLangProvider>
        <IssueCalendar summaries={SUMMARIES} selectedDate={selectedDate} spacious={spacious} />
      </FeedLangProvider>
    </MemoryRouter>
  );
}

function calendarRoot(): HTMLElement {
  return screen.getByLabelText("报眼月历");
}

/** 取日格（link 或淡字 span）：空白天与表头无 data-state，日格文本=当日数字 */
function dayCell(root: HTMLElement, date: string): HTMLElement {
  const day = String(Number(date.slice(8)));
  const hit = Array.from(root.querySelectorAll<HTMLElement>("[data-state]")).find((el) => el.textContent === day);
  if (!hit) {
    throw new Error(`day cell for ${date} not found`);
  }
  return hit;
}

describe("IssueCalendar", () => {
  beforeEach(() => {
    // 今天=2026-10-02（HKT）：红框态构造为确定值，不随真实时钟漂移
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-10-02T20:00:00+08:00"));
  });
  afterEach(() => {
    vi.useRealTimers();
    cleanup();
  });

  it("renders Monday-first 7-column grid with weekday header", () => {
    renderCalendar();
    expect(screen.getByText("2026年10月")).toBeTruthy();
    const grid = calendarRoot().querySelector(".grid");
    expect(grid).toBeTruthy();
    // 表头周一开头（一二三四五六日），非周日制
    expect(grid?.firstElementChild?.textContent).toBe("一");
    // 2026-10-01=周四：月首前 3 个空白天+31 天+尾 1 空=35 日区格（5 周），加表头 7 格=42
    expect(grid?.children.length).toBe(42);
    expect(grid?.children[7].getAttribute("data-state")).toBeNull(); // 首空白天
  });

  it("renders each day cell state: current red, published dot, recess gray dot, none plain", () => {
    renderCalendar();
    const root = calendarRoot();

    // 本期（10-03）：红底白字+aria-current="date"
    const current = dayCell(root, "2026-10-03");
    expect(current.tagName).toBe("A");
    expect(current.getAttribute("aria-current")).toBe("date");
    expect(current.className).toContain("bg-[var(--polyu-red)]");
    expect(current.className).toContain("text-white");
    expect(current.querySelector("span")).toBeNull(); // 本期不出点

    // 已出刊（10-02=今天）：红点+今天红框（与红点并存，不与本期并存）
    const published = dayCell(root, "2026-10-02");
    expect(published.getAttribute("data-state")).toBe("published");
    expect(published.getAttribute("data-today")).toBe("true");
    expect(published.className).toContain("border-[var(--polyu-red)]");
    const dot = published.querySelector("span");
    expect(dot?.className).toContain("bg-[var(--polyu-red)]");

    // 休刊（09-28，需先翻到 9 月）：灰点、仍可点（空期占位）
    fireEvent.click(screen.getByLabelText("上一月"));
    expect(screen.getByText("2026年9月")).toBeTruthy();
    const recess = dayCell(calendarRoot(), "2026-09-28");
    expect(recess.getAttribute("data-state")).toBe("recess");
    expect(recess.tagName).toBe("A");
    expect(recess.querySelector("span")?.className).toContain("bg-[var(--feed-text-tertiary)]");

    // 无刊（10-01 目录外，淡字不可点=非链接、无点）
    fireEvent.click(screen.getByLabelText("下一月"));
    const none = dayCell(calendarRoot(), "2026-10-01");
    expect(none.getAttribute("data-state")).toBe("none");
    expect(none.tagName).toBe("SPAN");
    expect(none.querySelector("span")).toBeNull();
    expect(none.className).toContain("text-[#d4d4d8]");
  });

  it("deep-links day cells to /daily/:date", () => {
    renderCalendar();
    const cell = dayCell(calendarRoot(), "2026-10-02");
    expect(cell.getAttribute("href")).toBe("/daily/2026-10-02");
  });

  it("limits month switching to archived months and disables out-of-range directions", () => {
    renderCalendar();
    // 初始 2026-10（max 月）：下一月禁用（原生 disabled 可编程识别）、上一月可用
    expect(screen.getByLabelText("下一月")).toHaveProperty("disabled", true);
    expect(screen.getByLabelText("上一月")).toHaveProperty("disabled", false);

    fireEvent.click(screen.getByLabelText("上一月"));
    expect(screen.getByText("2026年9月")).toBeTruthy();
    // 到达 min 月（2026-09）：上一月禁用、下一月恢复
    expect(screen.getByLabelText("上一月")).toHaveProperty("disabled", true);
    expect(screen.getByLabelText("下一月")).toHaveProperty("disabled", false);

    // 禁用钮点击不进入无存档月（2026-08）
    fireEvent.click(screen.getByLabelText("上一月"));
    expect(screen.getByText("2026年9月")).toBeTruthy();
    expect(screen.queryByText("2026年8月")).toBeNull();
  });

  it("follows the selected issue's month when the route date changes", () => {
    const { rerender } = renderCalendar();
    expect(screen.getByText("2026年10月")).toBeTruthy();
    rerender(
      <MemoryRouter>
        <FeedLangProvider>
          <IssueCalendar summaries={SUMMARIES} selectedDate="2026-09-28" />
        </FeedLangProvider>
      </MemoryRouter>
    );
    expect(screen.getByText("2026年9月")).toBeTruthy();
  });

  it("renders nothing for an empty catalog", () => {
    render(
      <MemoryRouter>
        <FeedLangProvider>
          <IssueCalendar summaries={[]} selectedDate="2026-10-03" />
        </FeedLangProvider>
      </MemoryRouter>
    );
    expect(screen.queryByLabelText("报眼月历")).toBeNull();
  });

  it("applies >=44px touch targets in spacious mode (height classes)", () => {
    renderCalendar(true);
    const root = calendarRoot();
    // 月切换钮与日格均带 44px 高宽类（#232：高与宽；宽由 7 列无列距轨道保证）
    expect(screen.getByLabelText("上一月").className).toContain("h-11");
    expect(screen.getByLabelText("上一月").className).toContain("w-11");
    const cell = dayCell(root, "2026-10-02");
    expect(cell.className).toContain("min-h-[44px]");
    expect(root.className).not.toContain("gap-x");
  });

  it("keeps compact sizing for the desktop rail mount", () => {
    renderCalendar(false);
    const cell = dayCell(calendarRoot(), "2026-10-02");
    expect(cell.className).toContain("h-[30px]");
    expect(cell.className).not.toContain("min-h-[44px]");
  });

  it("labels legend states and month controls bilingually", () => {
    renderCalendar();
    const root = calendarRoot();
    expect(within(root).getByText("出刊")).toBeTruthy();
    expect(within(root).getByText("休刊")).toBeTruthy();
    expect(within(root).getByText("今天")).toBeTruthy();
  });
});

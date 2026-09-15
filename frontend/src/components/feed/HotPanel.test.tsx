import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { HotPanel } from "./HotPanel";
import { FeedLangContext } from "./feedLang";
import { MOCK_HOT_RANK } from "@/services/newsMockData";

/** 首页热点卡——Top5 行、前 3 名红色名次徽章、双语标题、空榜不渲染；「查看全部 ›」链 /hot。 */

function renderPanel(lang: "zh" | "en" = "zh", entries = MOCK_HOT_RANK.slice(0, 5)) {
  return render(
    // HotPanel 内含 Link（查看全部 ›），需 Router 上下文
    <MemoryRouter>
      <FeedLangContext.Provider value={{ lang, setLang: () => {} }}>
        <HotPanel entries={entries} />
      </FeedLangContext.Provider>
    </MemoryRouter>
  );
}

describe("HotPanel", () => {
  afterEach(() => {
    cleanup();
  });

  it("renders top-5 rows with the first three ranks in polyu red", () => {
    const { container } = renderPanel("zh");

    expect(screen.getByText("今日热点")).toBeTruthy();
    expect(screen.getAllByText(/🔥 \d+/)).toHaveLength(5);
    expect(screen.getByText(MOCK_HOT_RANK[0].titleZh)).toBeTruthy();
    expect(screen.getByText("🔥 138")).toBeTruthy();

    // 2026-09-12 修复：行外包 Link/div（itemId 有无两态），名次徽章下沉一层
    const badges = container.querySelectorAll("ol > li > a > span:first-child, ol > li > div > span:first-child");
    expect(badges).toHaveLength(5);
    expect(badges[0].className).toContain("bg-[var(--polyu-red)]");
    expect(badges[2].className).toContain("bg-[var(--polyu-red)]");
    expect(badges[3].className).not.toContain("bg-[var(--polyu-red)]");
  });

  it("renders rows as plain text without itemId and links with itemId", () => {
    // mock fixture 无 itemId → 纯文本行；带 itemId 的真数据形态 → /news/:id 链接
    const withId = [{ ...MOCK_HOT_RANK[0], itemId: 50 }];
    const { container } = renderPanel("zh", withId);
    const link = container.querySelector("ol > li a[href='/news/50']");
    expect(link).toBeTruthy();
    expect(link?.textContent).toContain(MOCK_HOT_RANK[0].titleZh);
  });

  it("renders english header and titles in en mode", () => {
    renderPanel("en");
    expect(screen.getByText("Trending today")).toBeTruthy();
    expect(screen.getByText(MOCK_HOT_RANK[0].titleEn)).toBeTruthy();
  });

  it("renders nothing for an empty rank list", () => {
    const { container } = renderPanel("zh", []);
    expect(container.firstElementChild).toBeNull();
  });
});

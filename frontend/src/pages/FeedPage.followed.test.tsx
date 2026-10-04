import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { FeedPage } from "./FeedPage";
import { FeedLangProvider } from "@/components/feed/feedLang";
import { MOCK_NEWS_ITEMS, NEWS_CATEGORY_CHIPS } from "@/services/newsMockData";
import { NEWS_FOLLOWED_TOPICS_KEY, useNewsLocalStore } from "@/stores/newsLocalStore";

/**
 * 「只看关注」（#215 调整，2026-10-04 维护者设计）机检面：
 * - ?followed=1 + 已关注主题 → 列表仅显示关注主题条目（mock 分支与服务端同语义），
 *   开关钮 aria-pressed=true；
 * - 点钮关闭 → 回落全量列表；
 * - ?followed=1 + 零关注 → 引导卡（链接去 /topics），列表不渲染；
 * - 检索态（?q=）开关钮禁用。
 */

function seedFollowed(slugs: string[]) {
  window.localStorage?.setItem(NEWS_FOLLOWED_TOPICS_KEY, JSON.stringify(slugs));
  useNewsLocalStore.getState().hydrate();
}

function renderFeed(entry: string) {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <FeedLangProvider>
        <FeedPage />
      </FeedLangProvider>
    </MemoryRouter>
  );
}

describe("FeedPage followed-only filter", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    window.localStorage?.removeItem(NEWS_FOLLOWED_TOPICS_KEY);
    window.localStorage?.removeItem("polyu.feed.lang");
    useNewsLocalStore.getState().hydrate();
  });

  it("filters the feed to followed topics when ?followed=1", async () => {
    seedFollowed(["ai"]);
    const expected = MOCK_NEWS_ITEMS.filter((item) => item.topics.includes("ai")).length;
    expect(expected).toBeGreaterThan(0);

    const { container } = renderFeed("/?followed=1");

    await waitFor(() => {
      expect(container.querySelectorAll("article")).toHaveLength(expected);
    });
    const toggle = screen.getByRole("button", { name: "只看关注" });
    expect(toggle.getAttribute("aria-pressed")).toBe("true");
    // 未见未关注主题的条目数膨胀（回落全量 15 才会 >expected，防假绿）
    expect(expected).toBeLessThan(MOCK_NEWS_ITEMS.length);
  });

  it("toggling off restores the full feed", async () => {
    seedFollowed(["ai"]);
    const { container } = renderFeed("/?followed=1");
    await waitFor(() => {
      expect(container.querySelectorAll("article").length).toBeGreaterThan(0);
    });

    fireEvent.click(screen.getByRole("button", { name: "只看关注" }));

    await waitFor(() => {
      expect(container.querySelectorAll("article")).toHaveLength(MOCK_NEWS_ITEMS.length);
    });
    expect(screen.getByRole("button", { name: "只看关注" }).getAttribute("aria-pressed")).toBe("false");
  });

  it("shows guidance card (not the feed) when followed set is empty", async () => {
    seedFollowed([]);
    const { container } = renderFeed("/?followed=1");

    await waitFor(() => {
      expect(screen.getByText(/还没有关注的主题/)).toBeTruthy();
    });
    expect(screen.getByRole("link", { name: /去主题目录/ }).getAttribute("href")).toBe("/topics");
    expect(container.querySelectorAll("article")).toHaveLength(0);
  });

  it("disables the toggle in search mode", async () => {
    seedFollowed(["ai"]);
    renderFeed("/?q=奖学金");

    await waitFor(() => {
      expect(screen.getByText(/在全部理大资讯中搜索/)).toBeTruthy();
    });
    const toggle = screen.getByRole("button", { name: "只看关注" });
    expect(toggle.hasAttribute("disabled")).toBe(true);
    expect(toggle.getAttribute("aria-pressed")).toBe("false");
  });

  it("keeps category chips usable alongside followed filter (param preserved)", async () => {
    seedFollowed(["campus"]);
    const campusChip = NEWS_CATEGORY_CHIPS.find((chip) => chip.key === "campus");
    expect(campusChip).toBeTruthy();
    const expected = MOCK_NEWS_ITEMS.filter(
      (item) => item.topics.includes("campus") && item.category === "campus"
    ).length;

    const { container } = renderFeed("/?followed=1");

    await waitFor(() => {
      expect(container.querySelectorAll("article").length).toBeGreaterThan(0);
    });
    fireEvent.click(screen.getByRole("button", { name: "校园" }));

    await waitFor(() => {
      expect(container.querySelectorAll("article")).toHaveLength(expected);
    });
    // followed 位随分类切换保留（两过滤正交共存）
    expect(screen.getByRole("button", { name: "只看关注" }).getAttribute("aria-pressed")).toBe("true");
  });
});

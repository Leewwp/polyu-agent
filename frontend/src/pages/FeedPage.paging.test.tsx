import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { FeedPage } from "./FeedPage";
import { fetchHotRank, fetchNewsFeed } from "@/services/newsService";
import { MOCK_NEWS_ITEMS } from "@/services/newsMockData";

/**
 * 加载更多：服务化分页（首屏 2 条/页 2 条 fixture 模拟）——
 * 首屏渲染第 1 页+「加载更多」按钮，点击追加第 2 页，haust 后按钮消失。
 * 独立文件做 vi.mock（同文件 mock 会波及真实 fixture 用例）。
 */

vi.mock("@/services/newsService", () => ({
  fetchNewsFeed: vi.fn(),
  fetchHotRank: vi.fn(),
  searchNewsFeed: vi.fn()
}));

function renderFeed() {
  return render(
    <MemoryRouter initialEntries={["/"]}>
      <FeedPage />
    </MemoryRouter>
  );
}

describe("FeedPage load-more", () => {
  beforeEach(() => {
    vi.mocked(fetchNewsFeed).mockReset();
    vi.mocked(fetchHotRank).mockReset();
    vi.mocked(fetchHotRank).mockResolvedValue([]);
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("appends the next page on click and hides the button when exhausted", async () => {
    vi.mocked(fetchNewsFeed)
      .mockResolvedValueOnce({ records: MOCK_NEWS_ITEMS.slice(0, 2), total: 4, hasMore: true })
      .mockResolvedValueOnce({ records: MOCK_NEWS_ITEMS.slice(2, 4), total: 4, hasMore: false });

    const { container } = renderFeed();

    await waitFor(() => {
      expect(container.querySelectorAll("article")).toHaveLength(2);
    });
    expect(screen.getByRole("button", { name: "加载更多" })).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "加载更多" }));

    await waitFor(() => {
      expect(container.querySelectorAll("article")).toHaveLength(4);
    });
    // 第 2 页 hasMore=false → 按钮消失
    expect(screen.queryByRole("button", { name: "加载更多" })).toBeNull();
    // 两次取页：page=1 与 page=2
    const firstCall = vi.mocked(fetchNewsFeed).mock.calls[0][0];
    const secondCall = vi.mocked(fetchNewsFeed).mock.calls[1][0];
    expect(firstCall?.page).toBe(1);
    expect(secondCall?.page).toBe(2);
  });
});

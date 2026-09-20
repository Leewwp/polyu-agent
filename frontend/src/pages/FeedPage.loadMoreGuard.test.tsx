import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { FeedPage } from "./FeedPage";
import { fetchHotRank, fetchNewsFeed } from "@/services/newsService";
import { MOCK_NEWS_ITEMS } from "@/services/newsMockData";

/**
 * L31（#94）：loadMore 守卫与失败反馈——
 * 1. 失败不再静默吞错：按钮转「加载失败，点击重试」，重试成功后恢复；
 * 2. 串台守卫：切分类后迟到的旧 loadMore 响应不追加进新列表。
 */

vi.mock("@/services/newsService", () => ({
  fetchNewsFeed: vi.fn(),
  fetchHotRank: vi.fn(),
  searchNewsFeed: vi.fn()
}));

function renderFeed(entry = "/") {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <FeedPage />
    </MemoryRouter>
  );
}

describe("FeedPage loadMore 守卫与失败反馈（L31）", () => {
  beforeEach(() => {
    vi.mocked(fetchNewsFeed).mockReset();
    vi.mocked(fetchHotRank).mockReset();
    vi.mocked(fetchHotRank).mockResolvedValue([]);
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("surfaces loadMore failure as a retry entry and recovers on retry", async () => {
    vi.mocked(fetchNewsFeed)
      .mockResolvedValueOnce({ records: MOCK_NEWS_ITEMS.slice(0, 2), total: 6, hasMore: true })
      .mockRejectedValueOnce(new Error("network down"))
      .mockResolvedValueOnce({ records: MOCK_NEWS_ITEMS.slice(2, 4), total: 6, hasMore: false });

    const { container } = renderFeed();

    await waitFor(() => {
      expect(container.querySelectorAll("article")).toHaveLength(2);
    });

    fireEvent.click(screen.getByRole("button", { name: "加载更多" }));

    // 失败可见：不再静默吞错，按钮转重试入口
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "加载失败，点击重试" })).toBeTruthy();
    });
    expect(container.querySelectorAll("article")).toHaveLength(2);

    // 重试成功：追加第 2 页并按 hasMore=false 收尾
    fireEvent.click(screen.getByRole("button", { name: "加载失败，点击重试" }));
    await waitFor(() => {
      expect(container.querySelectorAll("article")).toHaveLength(4);
    });
    expect(screen.queryByRole("button", { name: /加载更多|重试/ })).toBeNull();
  });

  it("drops a late loadMore response after the category changed (stale guard)", async () => {
    // 首屏分类 A（2 条）；loadMore(page 2) 挂起期间切分类 B——
    // 旧响应到达后不得把分类 A 的第 2 页追加进分类 B 的列表
    vi.mocked(fetchNewsFeed)
      .mockResolvedValueOnce({ records: MOCK_NEWS_ITEMS.slice(0, 2), total: 6, hasMore: true });

    const { container } = renderFeed("/?category=research");
    await waitFor(() => {
      expect(container.querySelectorAll("article")).toHaveLength(2);
    });
    expect(fetchNewsFeed).toHaveBeenCalledWith({ category: "research", page: 1 });

    // 挂起的 loadMore（不 resolve）
    let releaseLoadMore: (value: { records: typeof MOCK_NEWS_ITEMS; total: number; hasMore: boolean }) => void = () => {};
    vi.mocked(fetchNewsFeed).mockReturnValueOnce(
      new Promise((resolve) => {
        releaseLoadMore = resolve;
      })
    );
    fireEvent.click(screen.getByRole("button", { name: "加载更多" }));

    // 切分类（分类 B=校园 只 1 条、无更多）
    vi.mocked(fetchNewsFeed).mockResolvedValueOnce({ records: MOCK_NEWS_ITEMS.slice(0, 1), total: 1, hasMore: false });
    fireEvent.click(screen.getByRole("button", { name: "校园" }));
    await waitFor(() => {
      expect(fetchNewsFeed).toHaveBeenLastCalledWith({ category: "campus", page: 1 });
    });

    // 旧 loadMore 响应此刻到达——守卫应丢弃，列表维持分类 B 的 1 条
    await act(async () => {
      releaseLoadMore({ records: MOCK_NEWS_ITEMS.slice(2, 4), total: 6, hasMore: false });
    });
    expect(container.querySelectorAll("article")).toHaveLength(1);
  });
});

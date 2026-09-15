import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { FeedPage } from "./FeedPage";
import { fetchHotRank, fetchNewsFeed, searchNewsFeed } from "@/services/newsService";
import { MOCK_NEWS_ITEMS } from "@/services/newsMockData";

/**
 * 检索态（?q= 驱动、不新建路由）：
 * - 检索态优先于 view/category 呈现：隐藏 AI 精选条与热点卡，提示条+排序切换+结果流；
 * - 清空 q 回落原视图；热点榜/主题页不放搜索框（两入口外无框——组件挂载位置由 FeedPage 决定）；
 * - 排序切换重取（sort 参数）、加载更多分页、无结果空态。
 * 独立文件做 vi.mock（同 paging.test 经验）；mock fixture 真分支持用另一组用例（无 vi.mock）。
 */

vi.mock("@/services/newsService", () => ({
  fetchNewsFeed: vi.fn(),
  fetchHotRank: vi.fn(),
  fetchNewsDetail: vi.fn(),
  searchNewsFeed: vi.fn()
}));

function renderFeed(entry = "/") {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <FeedPage />
    </MemoryRouter>
  );
}

describe("FeedPage search state (?q=)", () => {
  beforeEach(() => {
    vi.mocked(fetchNewsFeed).mockReset();
    vi.mocked(fetchHotRank).mockReset();
    vi.mocked(searchNewsFeed).mockReset();
    vi.mocked(fetchNewsFeed).mockResolvedValue({ records: [], total: 0, hasMore: false });
    vi.mocked(fetchHotRank).mockResolvedValue([]);
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("presents the search mode over the featured view: notice + sort toggle + results, no AI strip/hot panel", async () => {
    vi.mocked(searchNewsFeed).mockResolvedValue({
      records: MOCK_NEWS_ITEMS.slice(0, 2),
      total: 2,
      hasMore: false
    });
    const { container } = renderFeed("/?q=钙钛矿");

    await waitFor(() => {
      expect(container.querySelectorAll("article")).toHaveLength(2);
    });
    expect(screen.getByText(/在全部理大资讯中搜索「钙钛矿」/)).toBeTruthy();
    expect(screen.getByRole("button", { name: /按时间/ })).toBeTruthy();
    expect(screen.getByRole("button", { name: "按相关度" })).toBeTruthy();
    // 检索态隐藏精选态两件（与 ?view=all 同形口径）
    expect(screen.queryByText("AI 每日精选")).toBeNull();
    expect(screen.queryByText("今日热点")).toBeNull();
    expect(vi.mocked(fetchNewsFeed)).not.toHaveBeenCalled();
    expect(vi.mocked(fetchHotRank)).not.toHaveBeenCalled();
    expect(vi.mocked(searchNewsFeed).mock.calls[0][0]).toMatchObject({ q: "钙钛矿", sort: "time", order: "desc", page: 1 });
  });

  it("refetches page 1 with sort=relevance on toggle and paginates via load more", async () => {
    vi.mocked(searchNewsFeed)
      .mockResolvedValueOnce({ records: MOCK_NEWS_ITEMS.slice(0, 2), total: 4, hasMore: true })
      .mockResolvedValueOnce({ records: MOCK_NEWS_ITEMS.slice(0, 2), total: 4, hasMore: true })
      .mockResolvedValueOnce({ records: MOCK_NEWS_ITEMS.slice(2, 4), total: 4, hasMore: false });
    const { container } = renderFeed("/?q=理大");

    await waitFor(() => {
      expect(container.querySelectorAll("article")).toHaveLength(2);
    });
    fireEvent.click(screen.getByRole("button", { name: "按相关度" }));

    // 排序切换=重取第 1 页（结果替换不追加）
    await waitFor(() => {
      expect(vi.mocked(searchNewsFeed).mock.calls[1][0]).toMatchObject({ sort: "relevance", page: 1 });
    });
    expect(container.querySelectorAll("article")).toHaveLength(2);

    fireEvent.click(screen.getByRole("button", { name: "加载更多" }));
    await waitFor(() => {
      expect(vi.mocked(searchNewsFeed).mock.calls[2][0]).toMatchObject({ sort: "relevance", page: 2 });
    });
    await waitFor(() => {
      expect(container.querySelectorAll("article")).toHaveLength(4);
    });
    expect(screen.queryByRole("button", { name: "加载更多" })).toBeNull();
  });

  it("shows the no-result empty state", async () => {
    vi.mocked(searchNewsFeed).mockResolvedValue({ records: [], total: 0, hasMore: false });
    renderFeed("/?q=zzz");

    await waitFor(() => {
      expect(screen.getByText(/未找到「zzz」相关资讯/)).toBeTruthy();
    });
  });

  it("falls back to the featured view when q is cleared by submit", async () => {
    vi.mocked(searchNewsFeed).mockResolvedValue({ records: MOCK_NEWS_ITEMS.slice(0, 1), total: 1, hasMore: false });
    renderFeed("/?q=钙钛矿");

    await waitFor(() => {
      expect(screen.getByText(/搜索「钙钛矿」/)).toBeTruthy();
    });
    fireEvent.change(screen.getByLabelText("搜索理大资讯"), { target: { value: "" } });
    fireEvent.click(screen.getByRole("button", { name: "搜索" }));

    await waitFor(() => {
      expect(vi.mocked(fetchNewsFeed)).toHaveBeenCalledWith({ category: "all", page: 1 });
    });
    await waitFor(() => {
      expect(screen.queryByText(/搜索「钙钛矿」/)).toBeNull();
    });
  });

  /**
   * T21：关键词×分类互通——检索态点分类保留 q 限范围（原行为=退出检索清词，已改）
   */
  it("keeps q and scopes results to the picked category when a chip is clicked during search", async () => {
    vi.mocked(searchNewsFeed).mockResolvedValue({ records: [], total: 0, hasMore: false });
    renderFeed("/?q=钙钛矿");

    await waitFor(() => {
      expect(screen.getByText(/在全部理大资讯中搜索「钙钛矿」/)).toBeTruthy();
    });
    fireEvent.click(screen.getByRole("button", { name: "科研" }));

    await waitFor(() => {
      expect(vi.mocked(searchNewsFeed).mock.calls[1][0]).toMatchObject({
        q: "钙钛矿",
        category: "research"
      });
    });
    expect(screen.getByText(/在分类「科研」中搜索「钙钛矿」/)).toBeTruthy();
    // 「全部」chip 回全局检索（q 仍在）
    fireEvent.click(screen.getByRole("button", { name: "全部" }));
    await waitFor(() => {
      expect(vi.mocked(searchNewsFeed).mock.calls[2][0]).toMatchObject({
        q: "钙钛矿",
        category: "all"
      });
    });
  });

  /**
   * T21：时间键同键再点翻转方向（默认 desc=最新在前，再点 asc），相关度键单向不翻转
   */
  it("flips the time direction on re-click and sends order through", async () => {
    vi.mocked(searchNewsFeed).mockResolvedValue({ records: [], total: 0, hasMore: false });
    renderFeed("/?q=学生");

    await waitFor(() => {
      expect(vi.mocked(searchNewsFeed).mock.calls[0][0]).toMatchObject({ sort: "time", order: "desc" });
    });
    // 时间键再点：desc → asc
    fireEvent.click(screen.getByRole("button", { name: /按时间/ }));
    await waitFor(() => {
      expect(vi.mocked(searchNewsFeed).mock.calls[1][0]).toMatchObject({ sort: "time", order: "asc" });
    });
    // 再点回 desc
    fireEvent.click(screen.getByRole("button", { name: /按时间/ }));
    await waitFor(() => {
      expect(vi.mocked(searchNewsFeed).mock.calls[2][0]).toMatchObject({ sort: "time", order: "desc" });
    });
    // 切相关度：单向不翻转（order 维持 desc 语义，无方向图标）
    fireEvent.click(screen.getByRole("button", { name: "按相关度" }));
    await waitFor(() => {
      expect(vi.mocked(searchNewsFeed).mock.calls[3][0]).toMatchObject({ sort: "relevance" });
    });
  });

  /**
   * T21：sort/order 进 URL——带参入口直接生效（刷新/后退保持的等价断言）
   */
  it("restores sort and order from URL params on entry", async () => {
    vi.mocked(searchNewsFeed).mockResolvedValue({ records: [], total: 0, hasMore: false });
    renderFeed("/?q=奖学金&sort=relevance&order=asc");

    await waitFor(() => {
      expect(vi.mocked(searchNewsFeed).mock.calls[0][0]).toMatchObject({
        q: "奖学金",
        sort: "relevance",
        order: "asc"
      });
    });
    expect(screen.getByRole("button", { name: "按相关度" }).getAttribute("aria-pressed")).toBe("true");
  });
});

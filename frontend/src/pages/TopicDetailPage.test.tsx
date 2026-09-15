import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";

import { TopicDetailPage } from "./TopicDetailPage";
import { TOPIC_MISSING_MESSAGE, fetchTopicDetail } from "@/services/newsService";
import type { TopicDetailData } from "@/services/newsService";
import { MOCK_NEWS_ITEMS, NEWS_TOPICS } from "@/services/newsMockData";

/**
 * 公开主题详情页数据全部来自
 * /public/news/topic/{slug} 专用端点（fetchTopicDetail）——统计头计数/近期焦点/
 * 最新动态同源，不再拉全局流第一页客户端过滤（「44 vs 9」与 csm 空态同根因）。
 * - 近期焦点=主题内热榜卡：按 heat desc 排序+排名徽章+🔥热度+「本主题 · 按热度」注；
 * - 点击焦点行滚动定位到下方对应卡片并描边闪烁（jsdom 断言口径=scrollIntoView mock
 *   + .feed-flash 类挂载/1600ms 后移除——滚动定位类交互经验）；
 * - 空态语义：itemCount=0=「暂无」；计数>0 却拉取为空/请求失败=失败态；
 * - 未知 slug（后端 A000001 同文案「主题不存在」）回主题地图；「加载更多」=hasMore 护栏。
 * fixture 口径：ai 主题两条——mock-007（今天，heat 31）+ mock-014（昨天，heat 63），
 * 流式顺序≠热度顺序，恰好证明焦点排序按 heat desc。
 */

vi.mock("@/services/newsService", () => ({
  fetchTopicDetail: vi.fn(),
  // 页面消费的模块常量须随 mock 工厂提供（vitest 工厂替换整模块导出）
  TOPIC_MISSING_MESSAGE: "主题不存在"
}));

// fixture 内 ai 最新条目=mock-007（2026-09-10 09:50 HKT）→「数据更新至」统计位锚点
const AI_LAST_PUBLISH = new Date("2026-09-10T09:50:00+08:00");

/** 按端点契约构造载荷：itemCount 与 records 同源；overrides 模拟异常/分页形态 */
function topicDetail(slug: string, overrides: Partial<TopicDetailData> = {}): TopicDetailData {
  const topic = NEWS_TOPICS.find((candidate) => candidate.slug === slug);
  if (!topic) {
    throw new Error(`fixture 缺少主题 ${slug}`);
  }
  const records = MOCK_NEWS_ITEMS.filter((item) => item.topics.includes(slug));
  return {
    topic: { ...topic, itemCount: records.length },
    lastPublishTime: records.length > 0 ? AI_LAST_PUBLISH : null,
    page: { records, total: records.length, hasMore: false },
    ...overrides
  };
}

function instrumentNetwork(): { requestedUrls: string[] } {
  const requestedUrls: string[] = [];
  vi.spyOn(XMLHttpRequest.prototype, "open").mockImplementation((...args: Parameters<XMLHttpRequest["open"]>) => {
    requestedUrls.push(String(args[1]));
  });
  return { requestedUrls };
}

function renderPage(slug: string) {
  return render(
    <MemoryRouter initialEntries={[`/topics/${slug}`]}>
      <Routes>
        <Route path="/topics/:slug" element={<TopicDetailPage />} />
        <Route path="/topics" element={<div>TOPICS_LANDING</div>} />
      </Routes>
    </MemoryRouter>
  );
}

describe("TopicDetailPage", () => {
  let scrollIntoViewMock: ReturnType<typeof vi.fn>;
  // jsdom 实际未实现 scrollIntoView（运行时为 undefined）；按 lib.dom 类型保存原值便于还原
  let originalScrollIntoView: Element["scrollIntoView"];

  beforeEach(() => {
    vi.mocked(fetchTopicDetail).mockReset();
    vi.mocked(fetchTopicDetail).mockResolvedValue(topicDetail("ai"));
    // jsdom 不实现 scrollIntoView（滚动定位交互：mock 后断言调用）
    scrollIntoViewMock = vi.fn();
    originalScrollIntoView = Element.prototype.scrollIntoView;
    Element.prototype.scrollIntoView = scrollIntoViewMock as unknown as Element["scrollIntoView"];
  });

  afterEach(() => {
    cleanup();
    Element.prototype.scrollIntoView = originalScrollIntoView;
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it("renders header stats and recent focus sorted by heat desc with date-grouped latest updates", async () => {
    const { requestedUrls } = instrumentNetwork();
    const { container } = renderPage("ai");

    // 界定头：名称+描述+（条数 · 更新时间）统计+返回链（数据异步就绪后再查）；
    // 计数与列表同源：itemCount=fixture ai 主题条目数
    await waitFor(() => {
      expect(screen.getByText("共 2 条")).toBeTruthy();
    });
    expect(screen.getByRole("link", { name: "‹ 全部主题" }).getAttribute("href")).toBe("/topics");
    // 名称在顶栏标题与界定头两处并存
    expect(screen.getAllByText("人工智能").length).toBeGreaterThan(1);
    expect(screen.getByText("AI 算法、应用与治理方向的科研与活动")).toBeTruthy();
    expect(screen.getByText("数据更新至 9月10日 09:50")).toBeTruthy();

    // 近期焦点：右上灰字注 + 按 heat desc 排序（63 的国自然排在 31 的讲座前，流式顺序为 31→63）
    expect(screen.getByText("近期焦点")).toBeTruthy();
    expect(screen.getByText("本主题 · 按热度")).toBeTruthy();
    const focusRows = screen.getAllByRole("button", { name: /🔥 \d+/ });
    expect(focusRows).toHaveLength(2);
    expect(focusRows[0].textContent).toContain("国家自然科学基金");
    expect(focusRows[0].textContent).toContain("🔥 63");
    expect(focusRows[1].textContent).toContain("人工智能与城市韧性");
    expect(focusRows[1].textContent).toContain("🔥 31");
    // 排名徽章 1/2（前 3 红底由样式承载，断言数字存在）
    expect(focusRows[0].textContent).toContain("1");

    // 最新动态：日期分组全量（今天 1 条 + 昨天 1 条）
    expect(screen.getByText("最新动态")).toBeTruthy();
    await waitFor(() => {
      expect(container.querySelectorAll("article")).toHaveLength(2);
    });
    expect(screen.getByText("今天 · 9月10日 周四")).toBeTruthy();
    expect(screen.getByText("昨天 · 9月9日 周三")).toBeTruthy();

    // 匿名 Network 断言：零 /auth 请求、零 /rag/settings 引擎探测
    expect(requestedUrls.filter((url) => url.includes("/auth"))).toEqual([]);
    expect(requestedUrls.filter((url) => url.includes("/rag/settings"))).toEqual([]);
  });

  it("scrolls to and flashes the matched card when a focus row is clicked (scrollIntoView mock)", async () => {
    renderPage("ai");
    const row = await screen.findByRole("button", { name: /国家自然科学基金/ });
    expect(scrollIntoViewMock).not.toHaveBeenCalled();

    // 数据就绪后再切 fake timers（渲染等待走真实定时器）；点击用 fireEvent——
    // 断言对象是定时器驱动的描边闪烁而非指针语义，绕开 userEvent 假时钟等待机
    vi.useFakeTimers();
    fireEvent.click(row);

    expect(scrollIntoViewMock).toHaveBeenCalledTimes(1);
    expect(scrollIntoViewMock).toHaveBeenCalledWith({ behavior: "smooth", block: "center" });
    const flashed = document.querySelector(".feed-flash");
    expect(flashed).not.toBeNull();
    // 描边挂在焦点行对应的卡片上（国自然卡），而非其它卡
    expect(flashed?.textContent).toContain("国家自然科学基金");
    expect(flashed?.textContent).not.toContain("城市韧性");

    // 1600ms 后描边移除（原型 scrollToCard setTimeout 语义）
    act(() => {
      vi.advanceTimersByTime(1700);
    });
    expect(document.querySelector(".feed-flash")).toBeNull();
  });

  it("renders the empty state for a valid topic with no items (hss, itemCount=0)", async () => {
    vi.mocked(fetchTopicDetail).mockResolvedValue(topicDetail("hss"));
    const { container } = renderPage("hss");

    await waitFor(() => {
      expect(screen.getByText(/该主题下暂无入选资讯/)).toBeTruthy();
    });
    // 计数与列表同源：空主题 itemCount=0，不再展示注册表prototype计数
    expect(screen.getByText("共 0 条")).toBeTruthy();
    // 空态下近期焦点与卡片列表整体隐藏，「最新动态」小节头保留（原型口径）
    expect(screen.queryByText("近期焦点")).toBeNull();
    expect(screen.getAllByText("最新动态")).toHaveLength(1);
    expect(container.querySelectorAll("article")).toHaveLength(0);
    expect(screen.getByRole("link", { name: "‹ 全部主题" })).toBeTruthy();
  });

  it("shows the failure card when count > 0 but the item page resolves empty", async () => {
    const hss = topicDetail("hss");
    vi.mocked(fetchTopicDetail).mockResolvedValue({
      ...hss,
      topic: { ...hss.topic, itemCount: 14 },
      page: { records: [], total: 0, hasMore: false }
    });
    renderPage("hss");

    // 计数>0 却拉取为空=异常态：失败文案，而非「暂无入选资讯」误导
    await waitFor(() => {
      expect(screen.getByText("资讯加载失败，请稍后刷新重试")).toBeTruthy();
    });
    expect(screen.queryByText(/该主题下暂无入选资讯/)).toBeNull();
  });

  it("redirects an unknown topic slug back to /topics (backend 主题不存在 contract)", async () => {
    vi.mocked(fetchTopicDetail).mockRejectedValue(new Error(TOPIC_MISSING_MESSAGE));
    renderPage("no-such-topic");

    await waitFor(() => {
      expect(screen.getByText("TOPICS_LANDING")).toBeTruthy();
    });
  });

  it("shows the failure empty state when the topic detail request rejects", async () => {
    vi.mocked(fetchTopicDetail).mockRejectedValue(new Error("network down"));
    renderPage("ai");

    await waitFor(() => {
      expect(screen.getByText("资讯加载失败，请稍后刷新重试")).toBeTruthy();
    });
  });

  it("appends the next page via 加载更多 and hides the button when hasMore turns false", async () => {
    const pageOne = topicDetail("ai");
    vi.mocked(fetchTopicDetail).mockResolvedValueOnce({
      ...pageOne,
      page: { records: pageOne.page.records, total: 4, hasMore: true }
    });
    const pageTwoRecords = MOCK_NEWS_ITEMS.filter((item) => item.topics.includes("campus"));
    vi.mocked(fetchTopicDetail).mockResolvedValueOnce({
      ...pageOne,
      page: { records: pageTwoRecords, total: 4, hasMore: false }
    });
    const { container } = renderPage("ai");

    const loadMore = await screen.findByRole("button", { name: "加载更多" });
    expect(container.querySelectorAll("article")).toHaveLength(2);

    fireEvent.click(loadMore);
    await waitFor(() => {
      expect(container.querySelectorAll("article")).toHaveLength(2 + pageTwoRecords.length);
    });
    // hasMore=false 后护栏按钮消失；追加分页请求带递增页码
    expect(screen.queryByRole("button", { name: "加载更多" })).toBeNull();
    expect(fetchTopicDetail).toHaveBeenLastCalledWith("ai", 2);
  });
});

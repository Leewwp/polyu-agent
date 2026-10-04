import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";

import { TopicDetailPage } from "./TopicDetailPage";
import { TopicsPage } from "./TopicsPage";
import { FeedLangProvider } from "@/components/feed/feedLang";
import { fetchTopicDetail, fetchTopics } from "@/services/newsService";
import type { TopicDetailData } from "@/services/newsService";
import { MOCK_NEWS_ITEMS, NEWS_TOPICS, NEWS_TOPIC_GROUPS } from "@/services/newsMockData";
import { NEWS_FOLLOWED_TOPICS_KEY, useNewsLocalStore } from "@/stores/newsLocalStore";

/**
 * 公开主题地图页（原型 #viewTopics）。
 * - 20 主题三维分组目录卡 + 计数 + 详情链接；compact 页脚（无中段声明行）；
 * - 匿名渲染零 /auth 请求（公开页红线）；加载失败空态；
 * - 顶栏语言 pill 切 EN 后目录整体切英文（feedLang 数据级双语）；
 * - #215 两触点一致性：目录卡关注后，主题详情头同一按钮即时回显已关注（同一本地 store）。
 */

vi.mock("@/services/newsService", () => ({
  fetchTopics: vi.fn(),
  // TopicDetailPage（两触点一致性用例渲染）消费面随 mock 工厂提供
  fetchTopicDetail: vi.fn(),
  TOPIC_MISSING_MESSAGE: "主题不存在"
}));

function instrumentNetwork(): { requestedUrls: string[] } {
  const requestedUrls: string[] = [];
  vi.spyOn(XMLHttpRequest.prototype, "open").mockImplementation((...args: Parameters<XMLHttpRequest["open"]>) => {
    requestedUrls.push(String(args[1]));
  });
  return { requestedUrls };
}

function installLocalStorageStub(): Map<string, string> {
  const mem = new Map<string, string>();
  Object.defineProperty(window, "localStorage", {
    value: {
      getItem: (key: string) => mem.get(key) ?? null,
      setItem: (key: string, value: string) => void mem.set(key, value),
      removeItem: (key: string) => void mem.delete(key)
    },
    configurable: true
  });
  return mem;
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/topics"]}>
      <FeedLangProvider>
        <TopicsPage />
      </FeedLangProvider>
    </MemoryRouter>
  );
}

/** ai 主题详情载荷（fixture 同 TopicDetailPage.test 口径：2 条 mock 记录） */
function aiTopicDetail(): TopicDetailData {
  const topic = NEWS_TOPICS.find((candidate) => candidate.slug === "ai");
  if (!topic) {
    throw new Error("fixture 缺少主题 ai");
  }
  const records = MOCK_NEWS_ITEMS.filter((item) => item.topics.includes("ai"));
  return {
    topic: { ...topic, itemCount: records.length },
    lastPublishTime: records.length > 0 ? new Date("2026-09-10T09:50:00+08:00") : null,
    page: { records, total: records.length, hasMore: false }
  };
}

function renderTopicDetail(slug: string) {
  return render(
    <MemoryRouter initialEntries={[`/topics/${slug}`]}>
      <FeedLangProvider>
        <Routes>
          <Route path="/topics/:slug" element={<TopicDetailPage />} />
          <Route path="/topics" element={<div>TOPICS_LANDING</div>} />
        </Routes>
      </FeedLangProvider>
    </MemoryRouter>
  );
}

describe("TopicsPage", () => {
  let mem: Map<string, string>;

  beforeEach(() => {
    vi.mocked(fetchTopics).mockReset();
    vi.mocked(fetchTopics).mockResolvedValue({ groups: NEWS_TOPIC_GROUPS, topics: NEWS_TOPICS });
    vi.mocked(fetchTopicDetail).mockReset();
    mem = installLocalStorageStub();
    useNewsLocalStore.getState().hydrate();
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    Object.defineProperty(window, "localStorage", { value: undefined, configurable: true });
  });

  it("renders 22 topic cards across 3 groups with counts and detail links, anonymously without /auth requests", async () => {
    const { requestedUrls } = instrumentNetwork();
    const { container } = renderPage();

    // 页头（原型 topics-head；顶栏标题与页头 h2 同名并存）
    expect(screen.getAllByText("主题地图").length).toBeGreaterThan(1);

    await waitFor(() => {
      expect(container.querySelectorAll("a[href^='/topics/']")).toHaveLength(22);
    });

    // 计数随目录数据动态（waitFor 后=已加载，勿在加载前断言数字，有竞态）
    expect(screen.getByText(/22 个主题由 AI 标签自动聚合/)).toBeTruthy();

    // 三维分组头（6/7/9）
    expect(screen.getByText("学院与部门")).toBeTruthy();
    expect(screen.getByText("研究领域与话题")).toBeTruthy();
    expect(screen.getByText("学生事务")).toBeTruthy();
    // 目录卡计数与链接抽检
    expect(screen.getByText("查看 74 条 →")).toBeTruthy();
    expect(screen.getByRole("link", { name: /人工智能/ }).getAttribute("href")).toBe("/topics/ai");
    expect(screen.getByRole("link", { name: /校园生活/ }).getAttribute("href")).toBe("/topics/campus");

    // compact 页脚：三法务链+邮箱，无中段非官方声明行（原型 topics 视图口径）
    expect(screen.getByRole("link", { name: "隐私声明" }).getAttribute("href")).toBe("/privacy");
    expect(screen.getByRole("link", { name: "服务条款" }).getAttribute("href")).toBe("/terms");
    expect(screen.getByRole("link", { name: "非官方声明" }).getAttribute("href")).toBe("/disclaimer");
    expect(screen.getByText(/ppp@polyuguide\.com · © 2026 PolyUGuide/)).toBeTruthy();
    expect(screen.queryByText(/非官方社区项目/)).toBeNull();

    // 匿名 Network 断言：零 /auth 请求、零 /rag/settings 引擎探测
    expect(requestedUrls.filter((url) => url.includes("/auth"))).toEqual([]);
    expect(requestedUrls.filter((url) => url.includes("/rag/settings"))).toEqual([]);
  });

  it("switches the whole directory to english via the topbar lang pill", async () => {
    renderPage();
    await waitFor(() => {
      expect(screen.getByText("学院与部门")).toBeTruthy();
    });

    const user = userEvent.setup();
    // MobileTopbar 补 LangPill 后桌面/移动两组 pill 并存，取第一组（桌面）
    await user.click(screen.getAllByRole("button", { name: "EN" })[0]);

    await waitFor(() => {
      expect(screen.getByText("Faculties & Departments")).toBeTruthy();
    });
    expect(screen.getByText("Research Areas & Themes")).toBeTruthy();
    expect(screen.getByText("Student Affairs")).toBeTruthy();
    expect(screen.getByText("74 items →")).toBeTruthy();
    expect(screen.queryByText("学院与部门")).toBeNull();
  });

  it("shows the failure empty state when the topics registry cannot be loaded", async () => {
    vi.mocked(fetchTopics).mockRejectedValue(new Error("network down"));
    renderPage();

    await waitFor(() => {
      expect(screen.getByText("主题目录加载失败，请稍后刷新重试")).toBeTruthy();
    });
    expect(screen.queryByText("学院与部门")).toBeNull();
  });

  it("#215 两触点一致：目录卡关注 → 主题详情头即时回显已关注 → 详情侧取关回落", async () => {
    vi.mocked(fetchTopicDetail).mockResolvedValue(aiTopicDetail());
    const user = userEvent.setup();

    // 触点一（主题目录）：在「人工智能」卡上关注
    renderPage();
    const aiCard = await waitFor(() => {
      const card = screen.getByText("🤖 人工智能").closest("a")?.parentElement;
      expect(card).toBeTruthy();
      return card as HTMLElement;
    });
    await user.click(within(aiCard).getByRole("button", { name: "☆ 关注" }));
    expect(within(aiCard).getByRole("button", { name: "★ 已关注" })).toBeTruthy();
    expect(JSON.parse(mem.get(NEWS_FOLLOWED_TOPICS_KEY) ?? "[]")).toEqual(["ai"]);

    // 触点二（主题详情，同一浏览会话导航）：头部关注钮读同一 store，直接呈已关注
    cleanup();
    renderTopicDetail("ai");
    await waitFor(() => {
      expect(screen.getByText("共 2 条")).toBeTruthy();
    });
    const detailFollow = screen.getByRole("button", { name: "★ 已关注" });
    expect(detailFollow.getAttribute("aria-pressed")).toBe("true");

    // 详情侧取关：状态与持久层同步回落（再回目录即恢复「关注」态）
    await user.click(detailFollow);
    expect(screen.getByRole("button", { name: "☆ 关注" }).getAttribute("aria-pressed")).toBe("false");
    expect(useNewsLocalStore.getState().followedTopics).toEqual([]);
    expect(JSON.parse(mem.get(NEWS_FOLLOWED_TOPICS_KEY) ?? "[]")).toEqual([]);
  });
});

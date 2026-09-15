import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";

import { TopicsPage } from "./TopicsPage";
import { fetchTopics } from "@/services/newsService";
import { NEWS_TOPICS, NEWS_TOPIC_GROUPS } from "@/services/newsMockData";

/**
 * 公开主题地图页（原型 #viewTopics）。
 * - 20 主题三维分组目录卡 + 计数 + 详情链接；compact 页脚（无中段声明行）；
 * - 匿名渲染零 /auth 请求（公开页红线）；加载失败空态；
 * - 顶栏语言 pill 切 EN 后目录整体切英文（feedLang 数据级双语）。
 */

vi.mock("@/services/newsService", () => ({
  fetchTopics: vi.fn()
}));

function instrumentNetwork(): { requestedUrls: string[] } {
  const requestedUrls: string[] = [];
  vi.spyOn(XMLHttpRequest.prototype, "open").mockImplementation((...args: Parameters<XMLHttpRequest["open"]>) => {
    requestedUrls.push(String(args[1]));
  });
  return { requestedUrls };
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/topics"]}>
      <TopicsPage />
    </MemoryRouter>
  );
}

describe("TopicsPage", () => {
  beforeEach(() => {
    vi.mocked(fetchTopics).mockReset();
    vi.mocked(fetchTopics).mockResolvedValue({ groups: NEWS_TOPIC_GROUPS, topics: NEWS_TOPICS });
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("renders 20 topic cards across 3 groups with counts and detail links, anonymously without /auth requests", async () => {
    const { requestedUrls } = instrumentNetwork();
    const { container } = renderPage();

    // 页头（原型 topics-head；顶栏标题与页头 h2 同名并存）
    expect(screen.getAllByText("主题地图").length).toBeGreaterThan(1);
    expect(screen.getByText(/20 个主题由 AI 标签自动聚合/)).toBeTruthy();

    await waitFor(() => {
      expect(container.querySelectorAll("a[href^='/topics/']")).toHaveLength(20);
    });

    // 三维分组头（6/6/8）
    expect(screen.getByText("学院与部门")).toBeTruthy();
    expect(screen.getByText("研究领域与话题")).toBeTruthy();
    expect(screen.getByText("学生事务")).toBeTruthy();
    // 目录卡计数与链接抽检
    expect(screen.getByText("查看 74 条 →")).toBeTruthy();
    expect(screen.getByRole("link", { name: /人工智能/ }).getAttribute("href")).toBe("/topics/ai");
    expect(screen.getByRole("link", { name: /校园生活/ }).getAttribute("href")).toBe("/topics/campus");

    // compact 页脚：三法务链+邮箱，无中段非官方声明行（原型 topics 视图口径）
    expect(screen.getByRole("link", { name: "隐私政策" }).getAttribute("href")).toBe("/privacy");
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
});

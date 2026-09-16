import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, RouterProvider } from "react-router-dom";

import { router } from "@/router";
import { FeedPage } from "./FeedPage";

/**
 * 首页机检面：
 * - 匿名渲染：15 卡/热点 5 行/9 chips/页脚三法务链+联系邮箱；
 * - Network 断言：XHR 层记录全部外发请求，断言匿名访问 `/` 不触发任何 /auth 请求、
 *   也不触发引擎探测 /rag/settings（engineStore.ts:13 经验红线）；
 * - 根路由直出 FeedPage（裸路由、匿名不被拉去 /login）。
 */

function instrumentNetwork(): { requestedUrls: string[] } {
  const requestedUrls: string[] = [];
  vi.spyOn(XMLHttpRequest.prototype, "open").mockImplementation((...args: Parameters<XMLHttpRequest["open"]>) => {
    requestedUrls.push(String(args[1]));
  });
  return { requestedUrls };
}

function renderFeed() {
  return render(
    <MemoryRouter initialEntries={["/"]}>
      <FeedPage />
    </MemoryRouter>
  );
}

describe("FeedPage", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    // 语言 pill 把偏好写入 localStorage（polyu.feed.lang），同文件内跨用例残留——
    // 「切 EN」用例跑完后，后继用例若不断言语言无关形态，「精选」等中文断言必挂（测试漂移判例）。
    // 可选链：部分 jsdom 环境（如本机）不暴露 localStorage
    window.localStorage?.removeItem("polyu.feed.lang");
  });

  it("renders anonymously: 15 cards, 5 hot rows, 9 chips, footer with legal links and contact email", async () => {
    const { requestedUrls } = instrumentNetwork();
    const { container } = renderFeed();

    await waitFor(() => {
      expect(container.querySelectorAll("article")).toHaveLength(15);
    });

    // 热点卡 5 行（前 3 名红色徽章由样式承载，此处断言行数与榜首热度）
    expect(screen.getByText("今日热点")).toBeTruthy();
    expect(screen.getAllByText(/🔥 \d+/)).toHaveLength(5);
    expect(screen.getByText("🔥 138")).toBeTruthy();

    // 9 个分类 chips（全部 + 8 类）
    const chipNames = ["全部", "招生", "科研", "校园", "活动", "就业", "交流", "奖学金", "公告"];
    for (const name of chipNames) {
      expect(screen.getByRole("button", { name })).toBeTruthy();
    }

    // 页脚：三法务链（指向线上既有路由）+ 非官方声明 + 联系邮箱
    expect(screen.getByRole("link", { name: "隐私政策" }).getAttribute("href")).toBe("/privacy");
    expect(screen.getByRole("link", { name: "服务条款" }).getAttribute("href")).toBe("/terms");
    expect(screen.getByRole("link", { name: "非官方声明" }).getAttribute("href")).toBe("/disclaimer");
    expect(screen.getByText(/ppp@polyuguide\.com · © 2026 PolyUGuide/)).toBeTruthy();
    expect(screen.getByText(/非官方社区项目/)).toBeTruthy();

    // Network 断言：匿名访问不触发任何 /auth 请求，也不触发 /rag/settings 引擎探测
    expect(requestedUrls.filter((url) => url.includes("/auth"))).toEqual([]);
    expect(requestedUrls.filter((url) => url.includes("/rag/settings"))).toEqual([]);
  });

  it("filters cards by category chip (research -> 4 cards)", async () => {
    const { container } = renderFeed();
    await waitFor(() => {
      expect(container.querySelectorAll("article")).toHaveLength(15);
    });

    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "科研" }));

    await waitFor(() => {
      expect(container.querySelectorAll("article")).toHaveLength(4);
    });
    expect(container.querySelectorAll("article")[0].textContent).toContain("钙钛矿");
  });

  it("switches card language via the global topbar pill only", async () => {
    renderFeed();
    await waitFor(() => {
      expect(screen.getByText("理大团队破解钙钛矿太阳能电池稳定性难题，成果刊于《自然·能源》")).toBeTruthy();
    });

    // 卡片级「中 / EN」小钮已移除：桌面+移动顶栏两个全局 pill 并存
    expect(screen.queryByRole("button", { name: "中 / EN" })).toBeNull();
    const enPills = screen.getAllByRole("button", { name: "EN" });
    expect(enPills.length).toBeGreaterThanOrEqual(2);

    const user = userEvent.setup();
    await user.click(enPills[0]);

    await waitFor(() => {
      expect(screen.getByText("PolyU team cracks perovskite solar-cell stability problem, published in Nature Energy")).toBeTruthy();
    });
  });

  it("serves `/` as the public feed via the real router (anonymous, no login redirect)", async () => {
    const { requestedUrls } = instrumentNetwork();
    const { container } = render(<RouterProvider router={router} />);

    await waitFor(() => {
      expect(container.querySelectorAll("article")).toHaveLength(15);
    });
    // 语言无关断言（T24 判例：语言 pill 偏好持久化，同文件「切 EN」用例先跑则此处为英文界面）
    expect(screen.getAllByText(/精选|Featured/).length).toBeGreaterThan(0);

    // 真实路由树下的匿名 Network 断言：零 /auth 请求
    expect(requestedUrls.filter((url) => url.includes("/auth"))).toEqual([]);
  });

  it("hides AI digest strip and hot panel in all-news view (?view=all)", async () => {
    const { container } = render(
      <MemoryRouter initialEntries={["/?view=all"]}>
        <FeedPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(container.querySelectorAll("article")).toHaveLength(15);
    });

    // 全部资讯态不渲染热点卡与 AI 精选条（双语断言——语言 pill 持久化偏好可能使界面为英文）
    expect(screen.queryByText(/今日热点|Trending today/)).toBeNull();
    expect(screen.queryByText(/AI 每日精选|AI daily digest/)).toBeNull();
    // 顶栏标题切「全部资讯」（侧栏导航同名项并存，取全部匹配）
    expect(screen.getAllByText(/全部资讯|All news/).length).toBeGreaterThan(1);
  });
});

/** 补充面（当日空态灰条 / 单页无更多） */

describe("FeedPage additions", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    window.localStorage?.removeItem("polyu.feed.lang");
  });

  it("shows the 'today updating' grey banner when newest item predates today (HKT)", async () => {
    const { container } = renderFeed();
    await waitFor(() => {
      expect(container.querySelectorAll("article")).toHaveLength(15);
    });

    // mock 数据最新为 2026-09-10，早于真实 HKT 今天 → 灰条出现且不阻断列表
    expect(screen.getByText("今日数据更新中——先看看此前的资讯")).toBeTruthy();
    expect(container.querySelectorAll("article").length).toBeGreaterThan(0);
  });

  it("hides the load-more button while mock data fits in one page", async () => {
    const { container } = renderFeed();
    await waitFor(() => {
      expect(container.querySelectorAll("article")).toHaveLength(15);
    });
    // 15 条 < 页大小 20 → 单页无更多
    expect(screen.queryByRole("button", { name: "加载更多" })).toBeNull();
  });
});

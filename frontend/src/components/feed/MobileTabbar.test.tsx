import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { toast } from "sonner";

import { MobileTabbar } from "./MobileTabbar";
import { FeedLangContext, type FeedLang } from "./feedLang";
import { guestLogin } from "@/services/authService";
import { useAuthStore } from "@/stores/authStore";

/**
 * 移动端 4 tab + FAB + 「更多」面板（#230）：
 * - tab 高亮按 pathname 归属判定（63a Q4）：精选/全部仅首页对应版式；
 *   热点榜/主题（含 :slug）/日报/关键日期/关于归「更多」；/news/:id 不点灯；
 *   query 不影响归属；路由切换后即时正确；
 * - 「更多」面板 = Radix Dialog bottom sheet：9 项两组、触发器 aria-expanded/
 *   aria-controls、初始焦点、Escape 关、焦点回触发器、modal 背景；
 * - 「对话」tab 与 FAB 游客直通（useEnterChat）——未登录铸游客号进 /chat；
 * - 语言随 #227 根 Provider（测试直接喂 FeedLangContext 值，同装配口径）。
 */

// toast 本体可调用（历史断言面）+ success/error 等方法（authStore.guestLogin 会调 toast.success）
vi.mock("sonner", () => ({
  toast: Object.assign(vi.fn(), { success: vi.fn(), error: vi.fn(), message: vi.fn() })
}));

vi.mock("@/services/authService", () => ({
  getCurrentUser: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
  guestLogin: vi.fn(),
  fetchGuestQuota: vi.fn()
}));

function renderTabbar(initialEntry = "/", lang: FeedLang = "zh") {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <FeedLangContext.Provider value={{ lang, setLang: () => {} }}>
        {/* tabbar 置于 Routes 外：导航后组件不卸载，可连续验证高亮随路由即时变化 */}
        <MobileTabbar />
        <Routes>
          <Route path="/chat" element={<div>CHAT_PAGE_MARK</div>} />
        </Routes>
      </FeedLangContext.Provider>
    </MemoryRouter>
  );
}

/** tab 高亮判定走 class（tabClass active 含 text-[var(--polyu-red)]，FeedSidebar.test 同口径） */
function isLit(el: Element): boolean {
  return (el.getAttribute("class") ?? "").includes("polyu-red");
}

function zhTabs() {
  return {
    featured: screen.getByRole("link", { name: /精选/ }),
    all: screen.getByRole("link", { name: /全部/ }),
    more: screen.getByRole("button", { name: /更多/ })
  };
}

describe("MobileTabbar", () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null, isAuthenticated: false, isLoading: false });
    vi.mocked(toast).mockClear();
    vi.mocked(guestLogin).mockReset();
  });

  afterEach(() => {
    cleanup();
  });

  it("renders four tabs with featured/all routing to the feed views", () => {
    renderTabbar("/");
    expect(screen.getByRole("link", { name: /精选/ }).getAttribute("href")).toBe("/");
    expect(screen.getByRole("link", { name: /全部/ }).getAttribute("href")).toBe("/?view=all");
    expect(screen.getByRole("button", { name: /对话/ })).toBeTruthy();
    expect(screen.getByRole("button", { name: /更多/ })).toBeTruthy();
    expect(screen.getByRole("button", { name: /问 Agent/ })).toBeTruthy();
  });

  it("casts a guest account and enters chat from both the chat tab and the FAB", async () => {
    vi.mocked(guestLogin).mockResolvedValue({
      userId: "g-9",
      role: "guest",
      token: "token-x",
      avatar: ""
    });
    renderTabbar();
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: /对话/ }));
    await waitFor(() => {
      expect(screen.getByText("CHAT_PAGE_MARK")).toBeTruthy();
    });

    // 第一次铸号成功后已是登录态：FAB 走已登录直达分支，不重复铸号
    await user.click(screen.getByRole("button", { name: /问 Agent/ }));
    expect(vi.mocked(guestLogin)).toHaveBeenCalledTimes(1);
    expect(screen.getByText("CHAT_PAGE_MARK")).toBeTruthy();
  });

  it("lights featured/all only on the matching home view (query-tolerant)", () => {
    // 首页精选态（无 view 或 view≠all；无关 query 不改归属）
    let view = renderTabbar("/");
    expect(isLit(zhTabs().featured)).toBe(true);
    expect(isLit(zhTabs().all)).toBe(false);
    expect(isLit(zhTabs().more)).toBe(false);
    expect(zhTabs().featured.getAttribute("aria-current")).toBe("page");
    view.unmount();

    view = renderTabbar("/?category=admissions&q=ai");
    expect(isLit(zhTabs().featured)).toBe(true);
    expect(isLit(zhTabs().all)).toBe(false);
    view.unmount();

    // 首页全部版式（版式参数 view 随行，其他 query 忽略）
    view = renderTabbar("/?view=all");
    expect(isLit(zhTabs().all)).toBe(true);
    expect(isLit(zhTabs().featured)).toBe(false);
    expect(zhTabs().all.getAttribute("aria-current")).toBe("page");
    expect(zhTabs().featured.getAttribute("aria-current")).toBeNull();
    view.unmount();

    view = renderTabbar("/?view=all&category=x");
    expect(isLit(zhTabs().all)).toBe(true);
    view.unmount();
  });

  it("switches the lit tab immediately on in-page view navigation", async () => {
    renderTabbar("/");
    const user = userEvent.setup();

    await user.click(screen.getByRole("link", { name: /全部/ }));

    const tabs = zhTabs();
    expect(isLit(tabs.all)).toBe(true);
    expect(isLit(tabs.featured)).toBe(false);

    // 返回精选：点灯即时跟随路由（不依赖来源历史）
    await user.click(screen.getByRole("link", { name: /精选/ }));
    expect(isLit(zhTabs().featured)).toBe(true);
    expect(isLit(zhTabs().all)).toBe(false);
  });

  it("lights the More tab (not Featured) on column pages incl. topic detail and queries", () => {
    const columnRoutes = [
      "/hot",
      "/hot?range=7",
      "/topics",
      "/topics/artificial-intelligence",
      "/daily",
      "/daily?date=2026-10-01",
      "/key-dates",
      "/key-dates?src=card",
      "/about"
    ];
    for (const route of columnRoutes) {
      const view = renderTabbar(route);
      const tabs = zhTabs();
      expect(isLit(tabs.more), `${route} 应点亮「更多」`).toBe(true);
      // 栏目页不再错误点亮「精选」（63a Q4 修复点）
      expect(isLit(tabs.featured), `${route} 不应点亮「精选」`).toBe(false);
      expect(isLit(tabs.all), `${route} 不应点亮「全部」`).toBe(false);
      view.unmount();
    }
  });

  it("lights no tab on the news detail page (back-to-feed entry preserved)", () => {
    for (const route of ["/news/123", "/news/123?src=hot"]) {
      const view = renderTabbar(route);
      const tabs = zhTabs();
      expect(isLit(tabs.featured), `${route} 不点灯`).toBe(false);
      expect(isLit(tabs.all), `${route} 不点灯`).toBe(false);
      expect(isLit(tabs.more), `${route} 不点灯`).toBe(false);
      view.unmount();
    }
  });

  it("opens the more sheet with 9 items in two groups", async () => {
    renderTabbar();
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: /更多/ }));

    // 两组标题
    expect(screen.getByText("栏目")).toBeTruthy();
    expect(screen.getByText("站点")).toBeTruthy();
    // 组一「栏目」：新增四栏目
    expect(screen.getByRole("link", { name: "🔥 热点榜" }).getAttribute("href")).toBe("/hot");
    expect(screen.getByRole("link", { name: "🧭 主题" }).getAttribute("href")).toBe("/topics");
    expect(screen.getByRole("link", { name: "📰 日报" }).getAttribute("href")).toBe("/daily");
    expect(screen.getByRole("link", { name: "📅 关键日期" }).getAttribute("href")).toBe("/key-dates");
    // 组二「站点」：关于+反馈+法务三链（现状 5 项；面板内无语言切换）
    expect(screen.getByRole("link", { name: "💡 关于" }).getAttribute("href")).toBe("/about");
    expect(screen.getByRole("button", { name: "💬 反馈" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "🔒 隐私声明" }).getAttribute("href")).toBe("/privacy");
    expect(screen.getByRole("link", { name: "📄 服务条款" }).getAttribute("href")).toBe("/terms");
    expect(screen.getByRole("link", { name: "ℹ️ 非官方声明" }).getAttribute("href")).toBe("/disclaimer");
  });

  it("exposes trigger aria-expanded/aria-controls, initial focus, tab trap and Escape close with focus return", async () => {
    renderTabbar();
    const user = userEvent.setup();
    const moreButton = screen.getByRole("button", { name: /更多/ });
    expect(moreButton.getAttribute("aria-expanded")).toBe("false");

    await user.click(moreButton);

    // 触发器展开态与面板关联（Radix Dialog primitive 合同）
    expect(moreButton.getAttribute("aria-expanded")).toBe("true");
    const controlsId = moreButton.getAttribute("aria-controls");
    expect(controlsId).toBeTruthy();
    expect(document.getElementById(controlsId as string)).toBeTruthy();
    // modal：背景不可误操作（react-remove-scroll 关 body 指针事件）
    expect(document.body.style.pointerEvents).toBe("none");

    // 初始焦点落面板容器（导航 sheet 惯例：Radix 默认会滤链接聚焦居中的
    // 「反馈」钮，语义弱），Tab 自首项起在面板内遍历
    expect(document.activeElement?.id).toBe(controlsId);
    await user.tab();
    expect(document.activeElement?.textContent).toContain("热点榜");
    await user.tab();
    expect(document.activeElement?.textContent).toContain("主题");

    // Escape 关闭：面板卸载、焦点回触发器、背景恢复可操作
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("link", { name: "🔥 热点榜" })).toBeNull();
    expect(moreButton.getAttribute("aria-expanded")).toBe("false");
    expect(document.activeElement).toBe(moreButton);
    expect(document.body.style.pointerEvents).not.toBe("none");
  });

  it("closes the sheet on section navigation and keeps More lit by attribution", async () => {
    renderTabbar("/");
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: /更多/ }));
    await user.click(screen.getByRole("link", { name: "🧭 主题" }));

    // 点击栏目后自动关闭；进入栏目后「更多」按归属保持点亮
    expect(screen.queryByText("栏目")).toBeNull();
    expect(isLit(zhTabs().more)).toBe(true);
  });

  it("moves focus into the feedback dialog when opened from the sheet", async () => {
    renderTabbar();
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: /更多/ }));
    await user.click(screen.getByRole("button", { name: "💬 反馈" }));

    // 面板关闭、反馈弹窗打开且焦点正确转移（非停留 body/触发器）
    expect(screen.queryByText("栏目")).toBeNull();
    await waitFor(() => {
      expect(screen.getByText("站点反馈")).toBeTruthy();
    });
    const feedbackInput = screen.getByLabelText("反馈内容");
    await waitFor(() => {
      expect(document.activeElement).toBe(feedbackInput);
    });
  });

  it("follows the root feed language for tabs and sheet labels (post-#227)", async () => {
    renderTabbar("/", "en");
    const user = userEvent.setup();

    expect(screen.getByRole("link", { name: /Featured/ })).toBeTruthy();
    expect(screen.getByRole("link", { name: /All/ })).toBeTruthy();
    expect(screen.getByRole("button", { name: /More/ })).toBeTruthy();
    expect(screen.getByRole("button", { name: /Ask Agent/ })).toBeTruthy();

    await user.click(screen.getByRole("button", { name: /More/ }));
    expect(screen.getByText("SECTIONS")).toBeTruthy();
    expect(screen.getByText("SITE")).toBeTruthy();
    expect(screen.getByRole("link", { name: "🔥 Trending" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "🧭 Topics" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "📰 Daily" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "📅 Key dates" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "💡 About" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "💬 Feedback" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "🔒 Privacy Notice" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "📄 Terms" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "ℹ️ Disclaimer" })).toBeTruthy();
  });
});

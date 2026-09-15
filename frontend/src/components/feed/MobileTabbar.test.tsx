import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { toast } from "sonner";

import { MobileTabbar } from "./MobileTabbar";
import { FeedLangContext } from "./feedLang";
import { guestLogin } from "@/services/authService";
import { useAuthStore } from "@/stores/authStore";

/**
 * 移动端 4 tab + FAB + 「更多」抽屉法务三链。
 * 「对话」tab 与 FAB 游客直通（useEnterChat）——未登录铸游客号进 /chat。
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

function renderTabbar(initialEntry = "/") {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <FeedLangContext.Provider value={{ lang: "zh", setLang: () => {} }}>
        {/* tabbar 置于 Routes 外：导航后组件不卸载，可连续验证对话 tab 与 FAB 两个入口 */}
        <MobileTabbar />
        <Routes>
          <Route path="/chat" element={<div>CHAT_PAGE_MARK</div>} />
        </Routes>
      </FeedLangContext.Provider>
    </MemoryRouter>
  );
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

  it("opens the more sheet with the three legal links (no language switch inside)", async () => {
    renderTabbar();
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: /更多/ }));
    expect(screen.getByRole("link", { name: "🔒 隐私政策" }).getAttribute("href")).toBe("/privacy");
    expect(screen.getByRole("link", { name: "📄 服务条款" }).getAttribute("href")).toBe("/terms");
    expect(screen.getByRole("link", { name: "ℹ️ 非官方声明" }).getAttribute("href")).toBe("/disclaimer");
  });
});

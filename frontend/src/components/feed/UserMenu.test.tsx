import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";

import { UserMenu } from "./UserMenu";
import { FeedLangContext } from "./feedLang";
import { useAuthStore } from "@/stores/authStore";

/**
 * 顶栏身份区三态——
 * 未登录→登录钮；游客→登录钮（身份由侧栏游客卡承载）；user/admin→头像+邮箱 chip+
 * 下拉（身份信息/管理后台[仅 admin]/退出登录）。
 */

vi.mock("@/services/authService", () => ({
  getCurrentUser: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
  guestLogin: vi.fn()
}));

function renderMenu(variant: "desktop" | "mobile" = "desktop") {
  return render(
    <MemoryRouter>
      <FeedLangContext.Provider value={{ lang: "zh", setLang: () => {} }}>
        <UserMenu variant={variant} />
      </FeedLangContext.Provider>
    </MemoryRouter>
  );
}

describe("UserMenu identity area", () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null, isAuthenticated: false, isLoading: false });
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("anonymous visitors see the sign-in link, not an identity chip", () => {
    renderMenu();
    const signIn = screen.getByRole("link", { name: "登录" });
    expect(signIn.getAttribute("href")).toBe("/login");
    expect(screen.queryByRole("button", { name: /账号菜单/ })).toBeNull();
  });

  it("guest sessions fall back to the sign-in link (identity carried by sidebar guest card)", () => {
    useAuthStore.setState({
      user: { userId: "g-1", username: "guest_abc", role: "guest" },
      isAuthenticated: true
    });
    renderMenu();
    expect(screen.getByRole("link", { name: "登录" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: /账号菜单/ })).toBeNull();
  });

  it("signed-in regular user: chip with email, dropdown has no admin entry, logout clears state", async () => {
    const logout = vi.fn(async () => {});
    useAuthStore.setState({
      user: { userId: "u-1", username: "test-alice@example.com", role: "user" },
      isAuthenticated: true,
      logout
    });
    renderMenu();

    const trigger = screen.getByRole("button", { name: /账号菜单/ });
    // chip 内可见邮箱（username 即邮箱）
    expect(screen.getByText("test-alice@example.com")).toBeTruthy();

    await userEvent.click(trigger);
    // 身份块 + 退出登录；普通用户无管理后台入口
    expect(screen.getByRole("menu")).toBeTruthy();
    expect(screen.getByText("已登录")).toBeTruthy();
    expect(screen.queryByText("管理后台")).toBeNull();

    await userEvent.click(screen.getByRole("menuitem", { name: "退出登录" }));
    expect(logout).toHaveBeenCalledTimes(1);
  });

  it("admin gets the admin console link in the dropdown (mobile variant shares the menu)", async () => {
    useAuthStore.setState({
      user: { userId: "u-3", username: "test-admin@example.com", role: "admin" },
      isAuthenticated: true,
      logout: vi.fn(async () => {})
    });
    renderMenu("mobile");

    await userEvent.click(screen.getByRole("button", { name: /账号菜单/ }));
    const adminLink = screen.getByRole("menuitem", { name: "管理后台" });
    expect(adminLink.getAttribute("href")).toBe("/admin");
  });
});

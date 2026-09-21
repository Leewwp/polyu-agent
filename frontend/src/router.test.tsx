import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";

import { RedirectIfAuth, RequireAccount } from "./router";
import { useAuthStore } from "@/stores/authStore";

/**
 * RedirectIfAuth 三态（2026-09-14 修复）：游客铸号后
 * isAuthenticated=true，守卫若不豁免游客，登录/注册/找回三页对游客永远不可达
 * （被弹回 /chat，无法转正）。正式用户维持原重定向语义。
 */

vi.mock("@/services/authService", () => ({
  getCurrentUser: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
  guestLogin: vi.fn()
}));

function renderGuard() {
  return render(
    <MemoryRouter initialEntries={["/login"]}>
      <Routes>
        <Route
          path="/login"
          element={
            <RedirectIfAuth>
              <div>auth-page</div>
            </RedirectIfAuth>
          }
        />
        <Route path="/chat" element={<div>chat-page</div>} />
      </Routes>
    </MemoryRouter>
  );
}

describe("RedirectIfAuth 游客豁免", () => {
  beforeEach(() => {
    useAuthStore.setState({
      user: null,
      isAuthenticated: false,
      isGuest: false,
      isLoading: false
    });
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("游客会话（isAuthenticated=true + isGuest）留在登录页，不弹回 /chat", () => {
    useAuthStore.setState({
      user: { userId: "g1", username: "guest-x", role: "guest" },
      isAuthenticated: true,
      isGuest: true
    });
    renderGuard();
    expect(screen.getByText("auth-page")).toBeTruthy();
    expect(screen.queryByText("chat-page")).toBeNull();
  });

  it("正式用户会话维持重定向语义 → /chat", () => {
    useAuthStore.setState({
      user: { userId: "u1", username: "alice", role: "user" },
      isAuthenticated: true,
      isGuest: false
    });
    renderGuard();
    expect(screen.getByText("chat-page")).toBeTruthy();
    expect(screen.queryByText("auth-page")).toBeNull();
  });

  it("未登录渲染登录页", () => {
    renderGuard();
    expect(screen.getByText("auth-page")).toBeTruthy();
  });
});

describe("RequireAccount 个人中心守卫（#104）", () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null, isAuthenticated: false, isGuest: false, isLoading: false });
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  function renderAccountGuard() {
    return render(
      <MemoryRouter initialEntries={["/account"]}>
        <Routes>
          <Route
            path="/account"
            element={
              <RequireAccount>
                <div>account-page</div>
              </RequireAccount>
            }
          />
          <Route path="/login" element={<div>login-page</div>} />
        </Routes>
      </MemoryRouter>
    );
  }

  it("游客会话（isAuthenticated=true）被拦去 /login，不进个人中心", () => {
    useAuthStore.setState({
      user: { userId: "g1", username: "guest-x", role: "guest" },
      isAuthenticated: true,
      isGuest: true
    });
    renderAccountGuard();
    expect(screen.getByText("login-page")).toBeTruthy();
    expect(screen.queryByText("account-page")).toBeNull();
  });

  it("未登录被拦去 /login；正式用户放行", () => {
    renderAccountGuard();
    expect(screen.getByText("login-page")).toBeTruthy();

    cleanup();
    useAuthStore.setState({
      user: { userId: "u1", username: "alice", role: "user" },
      isAuthenticated: true,
      isGuest: false
    });
    renderAccountGuard();
    expect(screen.getByText("account-page")).toBeTruthy();
  });
});

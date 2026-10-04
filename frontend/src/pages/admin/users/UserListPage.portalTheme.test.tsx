import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";

import { AdminLayout } from "@/pages/admin/AdminLayout";
import { UserListPage } from "./UserListPage";
import { useAuthStore } from "@/stores/authStore";
import { getUsersPage } from "@/services/userService";
import type { PageResult, UserItem } from "@/services/userService";

/**
 * #228 Portal 弹层双轨（DOM 侧集成，真实形态=AdminLayout 根+子页弹窗）：
 * 后台用户管理弹窗经 Radix Portal 直挂 document.body——保存/创建默认钮不在
 * .admin-layout 根内（吃不到布局作用域的紫色覆盖），必须靠 body[data-ui-theme="admin"]
 * 路由级标记（AdminLayout 挂载期打、离开清理）把紫色封到 body 直挂子树。
 * 标记→紫色的 CSS 语义由 src/styles/globals.primaryTheme.test.ts 源断言覆盖。
 */

vi.mock("@/services/userService", () => ({
  getUsersPage: vi.fn(),
  createUser: vi.fn(),
  updateUser: vi.fn(),
  deleteUser: vi.fn(),
  changePassword: vi.fn()
}));
vi.mock("sonner", () => ({ toast: { error: vi.fn(), success: vi.fn() } }));
vi.mock("@/components/RelativeTime", () => ({ RelativeTime: () => null }));

function page(): PageResult<UserItem> {
  return {
    records: [
      {
        id: "u1",
        username: "admin",
        role: "admin",
        createTime: "2026-09-01 10:00:00",
        updateTime: "2026-09-01 10:00:00"
      }
    ],
    total: 1,
    size: 10,
    current: 1,
    pages: 1
  };
}

function renderApp() {
  return render(
    <MemoryRouter initialEntries={["/admin/users"]}>
      <Routes>
        <Route path="/admin" element={<AdminLayout />}>
          <Route path="users" element={<UserListPage />} />
        </Route>
      </Routes>
    </MemoryRouter>
  );
}

describe("#228 后台用户管理弹窗（Portal 场景）主题双轨", () => {
  beforeEach(() => {
    useAuthStore.setState({
      user: { userId: "a1", username: "admin", role: "admin" },
      isAuthenticated: true,
      isGuest: false,
      isLoading: false
    });
    vi.mocked(getUsersPage).mockReset();
    vi.mocked(getUsersPage).mockResolvedValue(page());
    document.body.removeAttribute("data-ui-theme");
  });

  afterEach(() => {
    cleanup();
    document.body.removeAttribute("data-ui-theme");
    vi.restoreAllMocks();
  });

  it("布局根在 .admin-layout 内、body 带后台主题标记", async () => {
    renderApp();
    await waitFor(() => {
      expect(screen.getByRole("button", { name: /新增用户/ })).toBeTruthy();
    });
    expect(document.querySelector(".admin-layout")).toBeTruthy();
    expect(document.body.getAttribute("data-ui-theme")).toBe("admin");
  });

  it("弹窗创建默认钮 Portal 到 body、不在 .admin-layout 根内——由 body 标记接管主题", async () => {
    renderApp();
    const openButton = await waitFor(() => {
      const button = screen.getByRole("button", { name: /新增用户/ });
      expect(button).toBeTruthy();
      return button as HTMLButtonElement;
    });

    fireEvent.click(openButton);
    const save = await waitFor(() => {
      const button = screen.getByRole("button", { name: /创建/ });
      expect(button).toBeTruthy();
      return button as HTMLElement;
    });

    // Portal 双轨前提：弹层内容直挂 body，布局作用域覆盖不可达
    expect(document.body.contains(save)).toBe(true);
    expect(save.closest(".admin-layout")).toBeNull();
    // 后台路由标记在位（该作用域把 --primary/--ring 封回紫色，见 globals.css）
    expect(document.body.getAttribute("data-ui-theme")).toBe("admin");
    // 默认变体（bg-primary/ring-ring/shadow-glow）——主题随主色变量联动的触点
    expect(save.dataset.variant).toBe("default");
  });
});

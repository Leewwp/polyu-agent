import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { UserListPage } from "./UserListPage";
import { getUsersPage } from "@/services/userService";
import type { PageResult, UserItem } from "@/services/userService";

/**
 * L43（#94）：请求计数回归——
 * 1. 挂载只发一次（useStaleRequest 闭包身份稳定前，effect 依赖含不稳定闭包
 *    形成每渲染一发的无限请求循环，实测 300ms 内 1885 次）；
 * 2. 第 1 页点「刷新」单请求收敛（原先手动 loadUsers+setPageNo 各发一次）；
 * 3. 第 2 页点「刷新」经 setPageNo(1)→effect 承接，同样单请求。
 */

vi.mock("@/services/userService", () => ({
  getUsersPage: vi.fn()
}));
vi.mock("sonner", () => ({ toast: { error: vi.fn(), success: vi.fn() } }));
vi.mock("@/components/RelativeTime", () => ({ RelativeTime: () => null }));

function page(count: number, current: number, pages: number): PageResult<UserItem> {
  return {
    records: Array.from({ length: count }, (_, index) => ({
      id: `u${current}-${index}`,
      username: `user${index}`,
      role: "user",
      createTime: "2026-09-01 10:00:00",
      updateTime: "2026-09-01 10:00:00"
    })),
    total: count,
    size: 10,
    current,
    pages
  };
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/admin/users"]}>
      <UserListPage />
    </MemoryRouter>
  );
}

async function settle() {
  // 让挂载请求与其后的渲染安定下来（若存在循环，调用数会持续增长）
  await waitFor(() => {
    expect(vi.mocked(getUsersPage).mock.calls.length).toBeGreaterThanOrEqual(1);
  });
  await new Promise((resolve) => setTimeout(resolve, 150));
}

describe("UserListPage 请求计数（L43）", () => {
  beforeEach(() => {
    vi.mocked(getUsersPage).mockReset();
    vi.mocked(getUsersPage).mockResolvedValue(page(10, 1, 2));
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("mounts with exactly one list request (no per-render refetch loop)", async () => {
    renderPage();
    await settle();
    expect(vi.mocked(getUsersPage).mock.calls.length).toBe(1);
    expect(vi.mocked(getUsersPage).mock.calls[0][0]).toBe(1);
  });

  it("refresh on page 1 converges to a single request", async () => {
    renderPage();
    await settle();
    fireEvent.click(screen.getByRole("button", { name: /刷新/ }));
    await settle();
    expect(vi.mocked(getUsersPage).mock.calls.length).toBe(2);
  });

  it("refresh from page 2 routes through setPageNo(1) effect with a single request", async () => {
    vi.mocked(getUsersPage)
      .mockResolvedValueOnce(page(10, 1, 2))
      .mockResolvedValueOnce(page(10, 2, 2))
      .mockResolvedValue(page(10, 1, 2));
    renderPage();
    await settle();
    fireEvent.click(screen.getByRole("button", { name: "下一页" }));
    await waitFor(() => {
      expect(vi.mocked(getUsersPage).mock.calls.length).toBe(2);
    });
    await new Promise((resolve) => setTimeout(resolve, 100));
    fireEvent.click(screen.getByRole("button", { name: /刷新/ }));
    await waitFor(() => {
      expect(vi.mocked(getUsersPage).mock.calls.length).toBe(3);
    });
    await new Promise((resolve) => setTimeout(resolve, 150));
    // 第 2 页刷新：setPageNo(1) 经 effect 单发一次，不再叠加手动 loadUsers
    expect(vi.mocked(getUsersPage).mock.calls.length).toBe(3);
    expect(vi.mocked(getUsersPage).mock.calls[2][0]).toBe(1);
  });
});

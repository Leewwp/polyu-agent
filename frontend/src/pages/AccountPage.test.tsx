import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";

import { FeedLangContext } from "@/components/feed/feedLang";
import { AccountPage } from "./AccountPage";
import { useAuthStore } from "@/stores/authStore";

/**
 * 账号设置页机检面（#104）：资料展示（未设置邮箱口径）、改邮箱两步流转、
 * 双 tab 分享按 flag 探测隐藏、注销 admin 预判拒绝。
 */
const listMyAgentSharesMock = vi.hoisted(() => vi.fn());
const listMySharesMock = vi.hoisted(() => vi.fn());
const revokeAgentShareMock = vi.hoisted(() => vi.fn());
const requestEmailChangeMock = vi.hoisted(() => vi.fn());
const confirmEmailChangeMock = vi.hoisted(() => vi.fn());
const changePasswordMock = vi.hoisted(() => vi.fn());
const deleteAccountMock = vi.hoisted(() => vi.fn());

vi.mock("@/services/agentShareService", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/services/agentShareService")>();
  return {
    ...actual,
    listMyAgentShares: listMyAgentSharesMock,
    revokeAgentShare: revokeAgentShareMock
  };
});

vi.mock("@/services/shareService", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/services/shareService")>();
  return {
    ...actual,
    listMyShares: listMySharesMock
  };
});

vi.mock("@/services/userService", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/services/userService")>();
  return {
    ...actual,
    requestEmailChange: requestEmailChangeMock,
    confirmEmailChange: confirmEmailChangeMock,
    changePassword: changePasswordMock
  };
});

vi.mock("@/services/authService", () => ({
  getCurrentUser: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
  guestLogin: vi.fn(),
  deleteAccount: deleteAccountMock
}));

function renderPage(lang: "zh" | "en" = "zh") {
  return render(
    <MemoryRouter>
      <FeedLangContext.Provider value={{ lang, setLang: () => {} }}>
        <AccountPage />
      </FeedLangContext.Provider>
    </MemoryRouter>
  );
}

describe("AccountPage", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    listMyAgentSharesMock.mockReset();
    listMySharesMock.mockReset();
    requestEmailChangeMock.mockReset();
    confirmEmailChangeMock.mockReset();
    changePasswordMock.mockReset();
    deleteAccountMock.mockReset();
  });

  it("shows profile with verified email badge", async () => {
    useAuthStore.setState({
      user: {
        userId: "u-1",
        username: "alice",
        role: "user",
        email: "alice@example.com",
        emailVerified: 1,
        createTime: "2026-09-01T00:00:00Z"
      },
      isAuthenticated: true,
      isGuest: false
    });
    listMyAgentSharesMock.mockResolvedValue([]);
    listMySharesMock.mockResolvedValue([]);
    renderPage();

    // #231 标题单源：账号页标签=「账号设置 · PolyUGuide」（DOM 断言证据——
    // 真浏览器路径有 HttpOnly cookie 登录墙，见 PR 偏离声明）
    expect(document.title).toBe("账号设置 · PolyUGuide");
    expect(screen.getByText("alice")).toBeTruthy();
    expect(screen.getByTestId("profile-email").textContent).toContain("alice@example.com");
    expect(screen.getByTestId("profile-email").textContent).toContain("已验证");
  });

  it("falls back to 未设置 for legacy users without email", async () => {
    useAuthStore.setState({
      user: { userId: "u-2", username: "legacy-admin", role: "admin", email: null },
      isAuthenticated: true,
      isGuest: false
    });
    listMyAgentSharesMock.mockResolvedValue([]);
    listMySharesMock.mockResolvedValue([]);
    renderPage();

    expect(screen.getByTestId("profile-email").textContent).toBe("未设置");
  });

  it("#229：头部有「回主站」明确入口指向 /（不依赖浏览器返回键）", async () => {
    useAuthStore.setState({
      user: { userId: "u-1", username: "alice", role: "user", email: null },
      isAuthenticated: true,
      isGuest: false
    });
    listMyAgentSharesMock.mockResolvedValue([]);
    listMySharesMock.mockResolvedValue([]);
    renderPage();

    const backToSite = screen.getByTestId("back-to-site");
    expect(backToSite.getAttribute("href")).toBe("/");
    expect(backToSite.textContent).toContain("回主站");
  });

  it("admin sees the no-self-delete notice instead of the delete button", async () => {
    useAuthStore.setState({
      user: { userId: "u-3", username: "admin", role: "admin", email: null },
      isAuthenticated: true,
      isGuest: false
    });
    listMyAgentSharesMock.mockResolvedValue([]);
    listMySharesMock.mockResolvedValue([]);
    renderPage();

    expect(screen.getByText("管理员账号不支持自助注销。")).toBeTruthy();
    expect(screen.queryByTestId("delete-account")).toBeNull();
  });

  it("hides a share tab whose flag is off (probe 404)", async () => {
    useAuthStore.setState({
      user: { userId: "u-1", username: "alice", role: "user", email: null },
      isAuthenticated: true,
      isGuest: false
    });
    listMyAgentSharesMock.mockRejectedValue(new Error("404"));
    listMySharesMock.mockResolvedValue([]);
    renderPage();

    await waitFor(() => {
      expect(screen.getByRole("tab", { name: "答案分享" })).toBeTruthy();
    });
    expect(screen.queryByRole("tab", { name: "会话分享" })).toBeNull();
  });

  it("#139：我的分享说明不再写死 90 天（以每条记录到期时间为准）", async () => {
    useAuthStore.setState({
      user: { userId: "u-1", username: "alice", role: "user", email: null },
      isAuthenticated: true,
      isGuest: false
    });
    listMyAgentSharesMock.mockResolvedValue([
      { token: "t-1", titlePreview: "宿舍申请咨询", status: "ACTIVE", expireTime: "2026-12-25T00:00:00" }
    ]);
    listMySharesMock.mockResolvedValue([]);
    renderPage();

    await waitFor(() => {
      expect(screen.getByRole("tab", { name: "会话分享" })).toBeTruthy();
    });
    expect(screen.queryByText(/90 天/)).toBeNull();
    expect(screen.getByText(/到期时间以每条记录显示为准/)).toBeTruthy();
  });

  it("walks the two-step email change flow and refreshes the store", async () => {
    const fetchCurrentUser = vi.fn(async () => {});
    useAuthStore.setState({
      user: { userId: "u-1", username: "alice", role: "user", email: "old@example.com" },
      isAuthenticated: true,
      isGuest: false,
      fetchCurrentUser
    });
    listMyAgentSharesMock.mockResolvedValue([]);
    listMySharesMock.mockResolvedValue([]);
    requestEmailChangeMock.mockResolvedValue(undefined);
    confirmEmailChangeMock.mockResolvedValue(undefined);
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByTestId("new-email"), "new@example.com");
    await user.type(screen.getByTestId("email-current-password"), "right-pass");
    await user.click(screen.getByRole("button", { name: "发送验证码" }));
    expect(requestEmailChangeMock).toHaveBeenCalledWith("new@example.com", "right-pass");

    await user.type(screen.getByTestId("email-code"), "123456");
    await user.click(screen.getByRole("button", { name: "确认更改" }));
    await waitFor(() => {
      expect(confirmEmailChangeMock).toHaveBeenCalledWith("new@example.com", "123456");
    });
    expect(fetchCurrentUser).toHaveBeenCalled();
  });

  it("surfaces backend duplicate-email errors inline in the change flow", async () => {
    useAuthStore.setState({
      user: { userId: "u-1", username: "alice", role: "user", email: "old@example.com" },
      isAuthenticated: true,
      isGuest: false
    });
    listMyAgentSharesMock.mockResolvedValue([]);
    listMySharesMock.mockResolvedValue([]);
    requestEmailChangeMock.mockRejectedValue(new Error("该邮箱已被其他账号绑定"));
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByTestId("new-email"), "taken@example.com");
    await user.type(screen.getByTestId("email-current-password"), "right-pass");
    await user.click(screen.getByRole("button", { name: "发送验证码" }));

    await waitFor(() => {
      expect(screen.getByText("该邮箱已被其他账号绑定")).toBeTruthy();
    });
  });

  it("changes password through the existing endpoint", async () => {
    useAuthStore.setState({
      user: { userId: "u-1", username: "alice", role: "user", email: null },
      isAuthenticated: true,
      isGuest: false
    });
    listMyAgentSharesMock.mockResolvedValue([]);
    listMySharesMock.mockResolvedValue([]);
    changePasswordMock.mockResolvedValue(undefined);
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByTestId("pw-current"), "old-pass-123");
    await user.type(screen.getByTestId("pw-new"), "new-pass-456");
    await user.click(screen.getByRole("button", { name: "修改密码" }));

    await waitFor(() => {
      expect(changePasswordMock).toHaveBeenCalledWith({
        currentPassword: "old-pass-123",
        newPassword: "new-pass-456"
      });
    });
  });

  describe("accessible names (#235)", () => {
    it("names every input by its visible purpose in each card (zh, no visible labels → aria-label)", async () => {
      useAuthStore.setState({
        user: { userId: "u-1", username: "alice", role: "user", email: null },
        isAuthenticated: true,
        isGuest: false
      });
      listMyAgentSharesMock.mockResolvedValue([]);
      listMySharesMock.mockResolvedValue([]);
      renderPage();

      // 换邮箱第一阶段：新邮箱 + 当前密码（分卡 within——密码卡同名 aria-label 不串场）
      const emailCard = within(screen.getByRole("region", { name: "更改邮箱" }));
      expect(emailCard.getByLabelText("新邮箱")).toBe(screen.getByTestId("new-email"));
      expect(emailCard.getByLabelText("当前密码")).toBe(
        screen.getByTestId("email-current-password")
      );

      // 修改密码卡：当前密码 + 新密码
      const pwCard = within(screen.getByRole("region", { name: "修改密码" }));
      expect(pwCard.getByLabelText("当前密码")).toBe(screen.getByTestId("pw-current"));
      expect(pwCard.getByLabelText("新密码")).toBe(screen.getByTestId("pw-new"));
    });

    it("names the code input in the change-email second step (zh)", async () => {
      useAuthStore.setState({
        user: { userId: "u-1", username: "alice", role: "user", email: "old@example.com" },
        isAuthenticated: true,
        isGuest: false
      });
      listMyAgentSharesMock.mockResolvedValue([]);
      listMySharesMock.mockResolvedValue([]);
      requestEmailChangeMock.mockResolvedValue(undefined);
      const user = userEvent.setup();
      renderPage();

      // 分卡取控件——「当前密码」在换邮箱卡与修改密码卡各有一枚（aria-label 同名）
      const emailCard = within(screen.getByRole("region", { name: "更改邮箱" }));
      await user.type(emailCard.getByLabelText("新邮箱"), "new@example.com");
      await user.type(emailCard.getByLabelText("当前密码"), "right-pass");
      await user.click(screen.getByRole("button", { name: "发送验证码" }));

      await waitFor(() => {
        expect(screen.getByLabelText("验证码")).toBe(screen.getByTestId("email-code"));
      });
    });

    it("names the delete-account confirmation dialog inputs and its close button (zh)", async () => {
      useAuthStore.setState({
        user: { userId: "u-1", username: "alice", role: "user", email: null },
        isAuthenticated: true,
        isGuest: false
      });
      listMyAgentSharesMock.mockResolvedValue([]);
      listMySharesMock.mockResolvedValue([]);
      const user = userEvent.setup();
      renderPage();

      await user.click(screen.getByTestId("delete-account"));
      expect(screen.getByLabelText("密码确认")).toBe(screen.getByTestId("delete-password"));
      // #235：公共关闭钮（仅 X 图标）可报名
      expect(screen.getByRole("button", { name: "关闭" })).toBeTruthy();
    });

    it("switches the accessible names with the global language (en)", async () => {
      useAuthStore.setState({
        user: { userId: "u-1", username: "alice", role: "user", email: null },
        isAuthenticated: true,
        isGuest: false
      });
      listMyAgentSharesMock.mockResolvedValue([]);
      listMySharesMock.mockResolvedValue([]);
      renderPage("en");

      const emailCard = within(screen.getByRole("region", { name: "Change email" }));
      expect(emailCard.getByLabelText("New email")).toBe(screen.getByTestId("new-email"));
      expect(emailCard.getByLabelText("Current password")).toBe(
        screen.getByTestId("email-current-password")
      );

      const pwCard = within(screen.getByRole("region", { name: "Change password" }));
      expect(pwCard.getByLabelText("New password")).toBe(screen.getByTestId("pw-new"));

      await userEvent.click(screen.getByTestId("delete-account"));
      expect(screen.getByRole("button", { name: "Close" })).toBeTruthy();
    });
  });
});

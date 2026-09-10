import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";

import { ForgotPasswordPage } from "./ForgotPasswordPage";

/**
 * U11-④ 找回密码机检面：请求（受理口径统一，静默进入下一步）→ 重置码+新密码 → 完成引导登录。
 */
const requestResetMock = vi.hoisted(() => vi.fn());
const resetMock = vi.hoisted(() => vi.fn());

vi.mock("@/services/authService", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/services/authService")>();
  return {
    ...actual,
    requestPasswordReset: requestResetMock,
    resetPassword: resetMock
  };
});

describe("ForgotPasswordPage", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    requestResetMock.mockReset();
    resetMock.mockReset();
  });

  it("requests a reset code then resets the password end to end", async () => {
    requestResetMock.mockResolvedValue(undefined);
    resetMock.mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <ForgotPasswordPage />
      </MemoryRouter>
    );

    await user.type(screen.getByPlaceholderText("you@example.com"), "user@example.com");
    await user.click(screen.getByRole("button", { name: "发送重置码" }));
    await waitFor(() => {
      expect(requestResetMock).toHaveBeenCalledWith("user@example.com");
    });

    await waitFor(() => {
      expect(screen.getByPlaceholderText("6 位数字")).toBeTruthy();
    });
    await user.type(screen.getByPlaceholderText("6 位数字"), "654321");
    await user.type(screen.getByPlaceholderText("8–64 位字符"), "newpassword8");
    await user.click(screen.getByRole("button", { name: "重置密码" }));

    await waitFor(() => {
      expect(resetMock).toHaveBeenCalledWith("user@example.com", "654321", "newpassword8");
    });
    await waitFor(() => {
      expect(screen.getByRole("button", { name: /前往登录/ })).toBeTruthy();
    });
  });

  it("blocks invalid email client-side before any request", async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <ForgotPasswordPage />
      </MemoryRouter>
    );

    await user.type(screen.getByPlaceholderText("you@example.com"), "not-an-email");
    expect(screen.getByRole("button", { name: "发送重置码" }).hasAttribute("disabled")).toBe(true);
    expect(requestResetMock).not.toHaveBeenCalled();
  });

  it("surfaces backend reset errors inline and stays on the reset step", async () => {
    requestResetMock.mockResolvedValue(undefined);
    resetMock.mockRejectedValue(new Error("重置码不正确或已过期"));
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <ForgotPasswordPage />
      </MemoryRouter>
    );

    await user.type(screen.getByPlaceholderText("you@example.com"), "user@example.com");
    await user.click(screen.getByRole("button", { name: "发送重置码" }));
    await waitFor(() => {
      expect(screen.getByPlaceholderText("6 位数字")).toBeTruthy();
    });
    await user.type(screen.getByPlaceholderText("6 位数字"), "000000");
    await user.type(screen.getByPlaceholderText("8–64 位字符"), "newpassword8");
    await user.click(screen.getByRole("button", { name: "重置密码" }));

    await waitFor(() => {
      expect(screen.getByText("重置码不正确或已过期")).toBeTruthy();
    });
  });
});

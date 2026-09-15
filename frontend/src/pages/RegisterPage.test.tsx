import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";

import { RegisterPage } from "./RegisterPage";

/**
 * 注册页机检面：勾选确认门（未勾选不放行注册）、注册→验证→完成三步流转、
 * 后端 flag 关闭态文案内联展示（错误响应不泄漏注册状态口径由后端承担，前端只透传文案）。
 */
const registerMock = vi.hoisted(() => vi.fn());
const verifyMock = vi.hoisted(() => vi.fn());
const resendMock = vi.hoisted(() => vi.fn());

vi.mock("@/services/authService", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/services/authService")>();
  return {
    ...actual,
    register: registerMock,
    verifyEmail: verifyMock,
    resendVerificationCode: resendMock
  };
});

function renderPage() {
  return render(
    <MemoryRouter>
      <RegisterPage />
    </MemoryRouter>
  );
}

async function fillRegisterForm(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByPlaceholderText("you@example.com"), "newuser@example.com");
  await user.type(screen.getByPlaceholderText("8–64 位字符"), "password123");
}

describe("RegisterPage", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    registerMock.mockReset();
    verifyMock.mockReset();
    resendMock.mockReset();
  });

  it("blocks submit until the agreement checkbox is checked (agreement gate)", async () => {
    const user = userEvent.setup();
    renderPage();
    await fillRegisterForm(user);

    const submit = screen.getByRole("button", { name: "注册" });
    expect(submit.hasAttribute("disabled")).toBe(true);
    expect(registerMock).not.toHaveBeenCalled();

    await user.click(screen.getByRole("checkbox"));
    expect(submit.hasAttribute("disabled")).toBe(false);

    await user.click(submit);
    await waitFor(() => {
      expect(registerMock).toHaveBeenCalledWith("newuser@example.com", "password123");
    });
  });

  it("shows the privacy/terms agreement copy with legal links", () => {
    renderPage();
    expect(screen.getByRole("link", { name: "隐私声明" }).getAttribute("href")).toBe("/privacy");
    expect(screen.getByRole("link", { name: "服务条款" }).getAttribute("href")).toBe("/terms");
  });

  it("moves to verify step after registration and to done after code verification", async () => {
    registerMock.mockResolvedValue(undefined);
    verifyMock.mockResolvedValue(undefined);
    const user = userEvent.setup();
    renderPage();
    await fillRegisterForm(user);
    await user.click(screen.getByRole("checkbox"));
    await user.click(screen.getByRole("button", { name: "注册" }));

    await waitFor(() => {
      expect(screen.getByPlaceholderText("6 位数字")).toBeTruthy();
    });
    // 注册成功即进入 60s 重发冷却（对齐后端冷却）
    expect(screen.getByRole("button", { name: /^60s$/ }).hasAttribute("disabled")).toBe(true);

    await user.type(screen.getByPlaceholderText("6 位数字"), "123456");
    await user.click(screen.getByRole("button", { name: "完成验证" }));
    await waitFor(() => {
      expect(verifyMock).toHaveBeenCalledWith("newuser@example.com", "123456");
    });
    await waitFor(() => {
      expect(screen.getByRole("button", { name: /前往登录/ })).toBeTruthy();
    });
  });

  it("surfaces the registration-closed message inline when the flag is off", async () => {
    registerMock.mockRejectedValue(new Error("注册通道当前未开放"));
    const user = userEvent.setup();
    renderPage();
    await fillRegisterForm(user);
    await user.click(screen.getByRole("checkbox"));
    await user.click(screen.getByRole("button", { name: "注册" }));

    await waitFor(() => {
      expect(screen.getByText("注册通道当前未开放")).toBeTruthy();
    });
    // 失败后停留在表单步，可修改重试
    expect(screen.getByPlaceholderText("you@example.com")).toBeTruthy();
  });

  it("rejects short passwords client-side before any request", async () => {
    const user = userEvent.setup();
    renderPage();
    await user.type(screen.getByPlaceholderText("you@example.com"), "newuser@example.com");
    await user.type(screen.getByPlaceholderText("8–64 位字符"), "short");
    await user.click(screen.getByRole("checkbox"));

    expect(screen.getByRole("button", { name: "注册" }).hasAttribute("disabled")).toBe(true);
    expect(registerMock).not.toHaveBeenCalled();
  });
});

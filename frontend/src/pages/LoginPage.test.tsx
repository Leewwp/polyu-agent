import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { FeedLangContext } from "@/components/feed/feedLang";
import { LoginPage } from "./LoginPage";

/**
 * 登录页挂注册与找回密码入口（注册通道 flag 由后端承担，入口常驻）。
 * #235：输入可访问名经可见 label（htmlFor/id）关联提供，随语言切换。
 */
function renderPage(lang: "zh" | "en" = "zh") {
  return render(
    <MemoryRouter>
      <FeedLangContext.Provider value={{ lang, setLang: () => {} }}>
        <LoginPage />
      </FeedLangContext.Provider>
    </MemoryRouter>
  );
}

describe("LoginPage entry links", () => {
  afterEach(() => {
    cleanup();
  });

  it("links to the register page", () => {
    renderPage();
    expect(screen.getByRole("link", { name: "注册" }).getAttribute("href")).toBe("/register");
  });

  it("links to the forgot-password page", () => {
    renderPage();
    expect(screen.getByRole("link", { name: "忘记密码？" }).getAttribute("href")).toBe(
      "/forgot-password"
    );
  });

  it("labels the account field as username-or-email (dual-channel login)", () => {
    renderPage();
    expect(screen.getByText("用户名或邮箱")).toBeTruthy();
    expect(screen.getByPlaceholderText("请输入用户名或邮箱")).toBeTruthy();
  });
});

describe("LoginPage accessible names (#235)", () => {
  afterEach(() => {
    cleanup();
  });

  it("derives input names from visible labels via htmlFor/id association (zh)", () => {
    renderPage();
    expect(screen.getByLabelText("用户名或邮箱")).toBe(
      screen.getByPlaceholderText("请输入用户名或邮箱")
    );
    expect(screen.getByLabelText("密码")).toBe(screen.getByPlaceholderText("请输入密码"));
    // 关联而非 aria-label 复写：输入不再携带 aria-label，名称单源于可见 label
    expect(screen.getByPlaceholderText("请输入用户名或邮箱").hasAttribute("aria-label")).toBe(
      false
    );
  });

  it("switches the accessible names with the global language (en)", () => {
    renderPage("en");
    expect(screen.getByLabelText("Username or email")).toBe(
      screen.getByPlaceholderText("Enter username or email")
    );
    expect(screen.getByLabelText("Password")).toBe(screen.getByPlaceholderText("Enter password"));
  });
});

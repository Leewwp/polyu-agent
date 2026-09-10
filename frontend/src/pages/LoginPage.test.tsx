import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { LoginPage } from "./LoginPage";

/**
 * U11-④：登录页挂注册与找回密码入口（注册通道 flag 由后端承担，入口常驻）。
 */
describe("LoginPage entry links", () => {
  afterEach(() => {
    cleanup();
  });

  it("links to the register page", () => {
    render(
      <MemoryRouter>
        <LoginPage />
      </MemoryRouter>
    );
    expect(screen.getByRole("link", { name: "注册" }).getAttribute("href")).toBe("/register");
  });

  it("links to the forgot-password page", () => {
    render(
      <MemoryRouter>
        <LoginPage />
      </MemoryRouter>
    );
    expect(screen.getByRole("link", { name: "忘记密码？" }).getAttribute("href")).toBe(
      "/forgot-password"
    );
  });
});

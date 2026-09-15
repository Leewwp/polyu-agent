import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { LoginPromptModal } from "./LoginPromptModal";

/** 游客超限弹窗——文案照原型；此处仅展示组件本体。 */

function renderModal(props: Partial<{ open: boolean; onClose: () => void; lang: "zh" | "en" }> = {}) {
  const onClose = props.onClose ?? vi.fn();
  const view = render(
    <MemoryRouter>
      <LoginPromptModal open={props.open ?? true} onClose={onClose} lang={props.lang} />
    </MemoryRouter>
  );
  return { onClose, ...view };
}

describe("LoginPromptModal", () => {
  afterEach(() => {
    cleanup();
  });

  it("renders nothing when closed", () => {
    const { container } = renderModal({ open: false });
    expect(container.firstElementChild).toBeNull();
  });

  it("shows the prototype copy with register/sign-in links in zh", () => {
    renderModal();

    expect(screen.getByText("已达到游客使用上限")).toBeTruthy();
    expect(screen.getByText("3 次")).toBeTruthy();
    expect(screen.getByText(/免登录 Agent 对话已用完/)).toBeTruthy();
    expect(screen.getByText(/浏览资讯始终免费/)).toBeTruthy();
    expect(screen.getByText("游客数据仅保留 30 天，注册后可同步对话历史与更多功能")).toBeTruthy();

    expect(screen.getByRole("link", { name: "注册" }).getAttribute("href")).toBe("/register");
    expect(screen.getByRole("link", { name: "登录" }).getAttribute("href")).toBe("/login");
  });

  it("shows the english copy in en mode", () => {
    renderModal({ lang: "en" });
    expect(screen.getByText("Guest limit reached")).toBeTruthy();
    expect(screen.getByText(/browsing news stays free/)).toBeTruthy();
  });

  it("closes via the close button and the backdrop", () => {
    const { onClose, container } = renderModal();

    fireEvent.click(screen.getByRole("button", { name: "关闭" }));
    expect(onClose).toHaveBeenCalledTimes(1);

    fireEvent.click(container.firstElementChild as HTMLElement);
    expect(onClose).toHaveBeenCalledTimes(2);
  });
});

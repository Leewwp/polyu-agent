import { afterEach, describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { MessageItem } from "@/components/chat/MessageItem";
import type { Message } from "@/types";

function assistantMessage(notice: Message["notice"], awaitingSignal = false): Message {
  return {
    id: "assistant-1",
    role: "assistant",
    content: "",
    status: "streaming",
    notice,
    awaitingSignal
  };
}

describe("MessageItem 结构化提示（U11-⑤：超限非裸 toast）", () => {
  let unmount: (() => void) | null = null;

  afterEach(() => {
    unmount?.();
    unmount = null;
  });

  // MessageItem 含 react-router Link，包一层 MemoryRouter 渲染
  function mount(message: Message) {
    const result = render(
      <MemoryRouter>
        <MessageItem message={message} />
      </MemoryRouter>
    );
    unmount = result.unmount;
    return result;
  }

  it("配额用尽渲染提示块与注册引导链接", () => {
    mount(assistantMessage({ kind: "quota", text: "今日匿名试用次数已用完，注册登录后可继续提问" }));

    expect(screen.queryByText(/今日匿名试用次数已用完/)).not.toBeNull();
    const cta = screen.queryByRole("link", { name: /注册登录，解锁完整使用/ });
    expect(cta).not.toBeNull();
    expect(cta?.getAttribute("href")).toBe("/login");
  });

  it("排队超时渲染繁忙提示且不带注册链接", () => {
    mount(assistantMessage({ kind: "busy", text: "系统繁忙，请稍后再试，当前使用人数较多，请稍等片刻再试" }));

    expect(screen.queryByText(/系统繁忙/)).not.toBeNull();
    expect(screen.queryByRole("link", { name: /注册登录/ })).toBeNull();
  });

  it("普通错误渲染错误提示块而非“生成已中断”裸文案", () => {
    mount({ ...assistantMessage({ kind: "error", text: "生成失败，请稍后重试" }), status: "error" });

    expect(screen.queryByText(/生成失败/)).not.toBeNull();
    expect(screen.queryByText("生成已中断。")).toBeNull();
  });

  it("等待首信号期间显示排队等待文案", () => {
    mount(assistantMessage(undefined, true));

    expect(screen.queryByText(/正在排队等待系统空闲/)).not.toBeNull();
  });

  it("收到信号后等待区不再显示排队文案", () => {
    mount(assistantMessage(undefined, false));

    expect(screen.queryByText(/正在排队等待系统空闲/)).toBeNull();
  });
});

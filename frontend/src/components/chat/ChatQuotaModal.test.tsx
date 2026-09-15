import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { act, cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";

import { ChatQuotaModal } from "./ChatQuotaModal";
import { FeedLangContext } from "@/components/feed/feedLang";
import { useChatStore } from "@/stores/chatStore";
import type { ChatNoticeKind, Message } from "@/types";

/**
 * quota 类错误升级为 LoginPromptModal 全局弹窗——
 * quota notice 弹、busy/concurrent 不弹、关闭后同消息不重弹、新消息可再触发。
 */

function message(id: string, kind: ChatNoticeKind): Message {
  return {
    id,
    role: "assistant",
    content: "",
    status: "error",
    notice: { kind, text: "今日游客试用次数已用完" }
  };
}

function renderModal(lang: "zh" | "en" = "zh") {
  return render(
    <MemoryRouter>
      <FeedLangContext.Provider value={{ lang, setLang: () => {} }}>
        <ChatQuotaModal />
      </FeedLangContext.Provider>
    </MemoryRouter>
  );
}

describe("ChatQuotaModal", () => {
  beforeEach(() => {
    useChatStore.setState({ messages: [] });
  });

  afterEach(() => {
    cleanup();
    useChatStore.setState({ messages: [] });
  });

  it("renders nothing when no message carries a quota notice", () => {
    useChatStore.setState({
      messages: [message("m-1", "busy"), message("m-2", "concurrent")]
    });
    renderModal();
    expect(screen.queryByText("已达到游客使用上限")).toBeNull();
  });

  it("opens the guest limit modal for the latest quota notice (zh)", () => {
    useChatStore.setState({
      messages: [message("m-1", "quota")]
    });
    renderModal();
    expect(screen.getByText("已达到游客使用上限")).toBeTruthy();
    expect(screen.getByRole("link", { name: "注册" }).getAttribute("href")).toBe("/register");
    expect(screen.getByRole("link", { name: "登录" }).getAttribute("href")).toBe("/login");
    expect(screen.getByText(/游客数据仅保留 30 天/)).toBeTruthy();
  });

  it("stays closed after dismissal and reopens for a new quota message", async () => {
    useChatStore.setState({ messages: [message("m-1", "quota")] });
    const user = userEvent.setup();
    renderModal();

    expect(screen.getByText("已达到游客使用上限")).toBeTruthy();
    await user.click(screen.getByRole("button", { name: "关闭" }));
    expect(screen.queryByText("已达到游客使用上限")).toBeNull();

    // 同一条消息的 notice 仍在 messages 里（仅弹窗让位），不重弹
    act(() => {
      useChatStore.setState({ messages: [message("m-1", "quota")] });
    });
    expect(screen.queryByText("已达到游客使用上限")).toBeNull();

    // 再次撞限产生新消息 → 可再次触发
    act(() => {
      useChatStore.setState({ messages: [message("m-1", "quota"), message("m-2", "quota")] });
    });
    expect(screen.getByText("已达到游客使用上限")).toBeTruthy();
  });

  it("renders English copy when lang is en", () => {
    useChatStore.setState({ messages: [message("m-1", "quota")] });
    renderModal("en");
    expect(screen.getByText("Guest limit reached")).toBeTruthy();
  });
});

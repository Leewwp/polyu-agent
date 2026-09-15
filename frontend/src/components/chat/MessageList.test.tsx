import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

vi.mock("react-virtuoso", () => ({ Virtuoso: () => null }));

import { MessageList } from "@/components/chat/MessageList";

/**
 * 会话消息加载失败不再空白回落——空态优先渲染行内错误 + 「重试」入口
 * （错误分支在 Virtuoso 之前 return，jsdom 下无需真实虚拟列表）。
 */

describe("MessageList 空态错误分支", () => {
  it("加载失败：显示行内错误文案与重试按钮，点击回调重试", () => {
    const onRetry = vi.fn();
    render(
      <MessageList
        messages={[]}
        isLoading={false}
        isStreaming={false}
        loadError="网络异常，请稍后重试"
        onRetry={onRetry}
      />
    );
    expect(screen.getByText("网络异常，请稍后重试")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "重试" }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("无错误时空态仍走欢迎屏，不出现重试入口", () => {
    render(<MessageList messages={[]} isLoading={false} isStreaming={false} />);
    expect(screen.queryByRole("button", { name: "重试" })).toBeNull();
  });

  it("加载中保持原空白占位（不闪错误态）", () => {
    render(
      <MessageList
        messages={[]}
        isLoading={true}
        isStreaming={false}
        loadError="网络异常，请稍后重试"
      />
    );
    expect(screen.queryByRole("button", { name: "重试" })).toBeNull();
  });
});

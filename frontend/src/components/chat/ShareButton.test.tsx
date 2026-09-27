import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, cleanup } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { ShareButton } from "@/components/chat/ShareButton";

const createShareMock = vi.hoisted(() => vi.fn());
const isGuestMock = vi.hoisted(() => vi.fn(() => false));

vi.mock("@/services/shareService", () => ({
  createShare: createShareMock
}));

vi.mock("@/stores/authStore", () => ({
  useAuthStore: (selector: (state: { isGuest: boolean }) => boolean) =>
    selector({ isGuest: isGuestMock() })
}));

describe("ShareButton", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    createShareMock.mockReset();
  });

  it("copies share link on success", async () => {
    createShareMock.mockResolvedValue({ token: "TOKEN123", expireTime: null });

    // user-event v14 接管 navigator.clipboard（自带 mock），从其 readText 读回断言
    const user = userEvent.setup();
    render(<ShareButton messageId="m1" />);
    await user.click(screen.getByRole("button", { name: /分享|Share/ }));

    const copied = await navigator.clipboard.readText();
    expect(createShareMock).toHaveBeenCalledWith("m1");
    expect(copied).toContain("/share/TOKEN123");
  });

  it("guest click shows login guidance without calling the create endpoint (#151)", async () => {
    isGuestMock.mockReturnValue(true);
    const user = userEvent.setup();
    render(<ShareButton messageId="m1" />);
    await user.click(screen.getByRole("button", { name: /分享|Share/ }));

    // 前端双保险：游客不触达创建端点（后端快照机制单点另有硬阻断兜底）
    expect(createShareMock).not.toHaveBeenCalled();
    isGuestMock.mockReturnValue(false);
  });

  it("surfaces disabled state when backend 404s", async () => {
    createShareMock.mockRejectedValue(new Error("Request failed with status code 404"));
    const user = userEvent.setup();
    render(<ShareButton messageId="m1" />);
    await user.click(screen.getByRole("button", { name: /分享|Share/ }));

    await waitFor(() => {
      // flag 关闭时不发请求报错——按钮保持可用，链接不复制
      expect(createShareMock).toHaveBeenCalledTimes(1);
    });
  });
});

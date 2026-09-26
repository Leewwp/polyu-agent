import { afterEach, describe, expect, it, vi } from "vitest";

const toastSuccess = vi.hoisted(() => vi.fn());
const toastError = vi.hoisted(() => vi.fn());
vi.mock("sonner", () => ({
  toast: { success: toastSuccess, error: toastError }
}));

import { shareUrl } from "./shareUrl";

/**
 * #139「分享给…」五点行为合同：navigator.share 优先 / AbortError 静默 /
 * unsupported 降级剪贴板 / 非 Abort 失败同降级 / 剪贴板也失败明确提示。
 */

const TEXTS = { copied: "COPIED-TOAST", failed: "FAILED-TOAST" };

function stubShare(impl?: () => Promise<void>) {
  Object.defineProperty(navigator, "share", {
    value: impl ?? vi.fn().mockResolvedValue(undefined),
    configurable: true
  });
}

function stubClipboard(ok: boolean) {
  Object.defineProperty(navigator, "clipboard", {
    value: ok ? { writeText: vi.fn().mockResolvedValue(undefined) } : { writeText: vi.fn().mockRejectedValue(new Error("denied")) },
    configurable: true
  });
}

afterEach(() => {
  vi.restoreAllMocks();
  toastSuccess.mockClear();
  toastError.mockClear();
  // 摘掉 share 桩（jsdom 原生无 navigator.share）
  Object.defineProperty(navigator, "share", { value: undefined, configurable: true });
});

describe("shareUrl 五点合同", () => {
  it("① navigator.share 可用：调系统面板，无剪贴板无 toast", async () => {
    const share = vi.fn().mockResolvedValue(undefined);
    stubShare(share);
    stubClipboard(true);
    const outcome = await shareUrl({ title: "t", url: "https://x.example/s/1", texts: TEXTS });
    expect(outcome).toBe("shared");
    expect(share).toHaveBeenCalledWith({ title: "t", url: "https://x.example/s/1" });
    expect(navigator.clipboard.writeText).not.toHaveBeenCalled();
    expect(toastSuccess).not.toHaveBeenCalled();
  });

  it("② 用户取消（AbortError）：静默，无任何 toast", async () => {
    stubShare(() => Promise.reject(new DOMException("abort", "AbortError")));
    stubClipboard(true);
    const outcome = await shareUrl({ title: "t", url: "https://x.example/s/2", texts: TEXTS });
    expect(outcome).toBe("cancelled");
    expect(toastSuccess).not.toHaveBeenCalled();
    expect(toastError).not.toHaveBeenCalled();
  });

  it("③ navigator.share 不支持：剪贴板 fallback+toast", async () => {
    Object.defineProperty(navigator, "share", { value: undefined, configurable: true });
    stubClipboard(true);
    const outcome = await shareUrl({ title: "t", url: "https://x.example/s/3", texts: TEXTS });
    expect(outcome).toBe("copied");
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith("https://x.example/s/3");
    expect(toastSuccess).toHaveBeenCalledWith("COPIED-TOAST");
  });

  it("④ 非 Abort 分享失败：同降级（剪贴板+toast），不冒泡原错误", async () => {
    stubShare(() => Promise.reject(new Error("share failed")));
    stubClipboard(true);
    const outcome = await shareUrl({ title: "t", url: "https://x.example/s/4", texts: TEXTS });
    expect(outcome).toBe("copied");
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith("https://x.example/s/4");
    expect(toastSuccess).toHaveBeenCalledWith("COPIED-TOAST");
  });

  it("⑤ 剪贴板也失败：明确失败提示", async () => {
    stubShare(() => Promise.reject(new Error("share failed")));
    stubClipboard(false);
    const outcome = await shareUrl({ title: "t", url: "https://x.example/s/5", texts: TEXTS });
    expect(outcome).toBe("failed");
    expect(toastError).toHaveBeenCalledWith("FAILED-TOAST");
  });
});

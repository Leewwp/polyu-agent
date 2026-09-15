import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";

const submitSiteFeedback = vi.fn();

vi.mock("@/services/siteService", () => ({
  submitSiteFeedback: (...args: unknown[]) => submitSiteFeedback(...args),
  fetchSiteAbout: vi.fn()
}));

const toastSuccess = vi.fn();
const toastError = vi.fn();
vi.mock("sonner", () => ({
  toast: {
    success: (msg: string) => toastSuccess(msg),
    error: (msg: string) => toastError(msg)
  }
}));

import { FeedbackDialog } from "./FeedbackDialog";

describe("FeedbackDialog", () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it("空内容提交被拦且不发请求", async () => {
    render(<FeedbackDialog open onClose={() => null} />);

    fireEvent.click(screen.getByText("提交"));

    expect(submitSiteFeedback).not.toHaveBeenCalled();
    await waitFor(() => {
      expect(toastError).toHaveBeenCalledWith("请填写反馈内容");
    });
  });

  it("合法内容提交成功后关窗并 toast 成功", async () => {
    submitSiteFeedback.mockResolvedValue(undefined);
    const onClose = vi.fn();
    render(<FeedbackDialog open onClose={onClose} />);

    fireEvent.change(screen.getByLabelText("反馈内容"), {
      target: { value: "这是一条足够长的反馈内容。" }
    });
    fireEvent.change(screen.getByLabelText("联系方式"), { target: { value: "a@b.com" } });
    fireEvent.click(screen.getByText("提交"));

    await waitFor(() => {
      expect(submitSiteFeedback).toHaveBeenCalledWith("这是一条足够长的反馈内容。", "a@b.com");
    });
    await waitFor(() => {
      expect(toastSuccess).toHaveBeenCalled();
      expect(onClose).toHaveBeenCalled();
    });
  });

  it("提交失败保留弹窗可重试", async () => {
    submitSiteFeedback.mockRejectedValue(new Error("今日提交已达上限，请明天再来"));
    const onClose = vi.fn();
    render(<FeedbackDialog open onClose={onClose} />);

    fireEvent.change(screen.getByLabelText("反馈内容"), {
      target: { value: "再来一条足够长的反馈内容。" }
    });
    fireEvent.click(screen.getByText("提交"));

    await waitFor(() => {
      expect(submitSiteFeedback).toHaveBeenCalled();
    });
    expect(onClose).not.toHaveBeenCalled();
  });
});

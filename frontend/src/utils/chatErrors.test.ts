import { describe, expect, it } from "vitest";

import { classifyChatError, noticeTextFor } from "@/utils/chatErrors";

describe("classifyChatError", () => {
  it("把游客配额用尽归为 quota", () => {
    expect(classifyChatError("今日匿名试用次数已用完，注册登录后可继续提问")).toBe("quota");
  });

  it("把排队超时/系统繁忙归为 busy", () => {
    expect(classifyChatError("系统繁忙，请稍后再试")).toBe("busy");
  });

  it("把同会话在途互斥归为 concurrent", () => {
    expect(classifyChatError("当前会话处理中，请稍后再发起新的对话")).toBe("concurrent");
  });

  it("其余与空文案归为 error", () => {
    expect(classifyChatError("SSE 请求失败（500）")).toBe("error");
    expect(classifyChatError("")).toBe("error");
    expect(classifyChatError(null)).toBe("error");
  });
});

describe("noticeTextFor", () => {
  it("quota 保留后端原文", () => {
    expect(noticeTextFor("quota", "今日匿名试用次数已用完，注册登录后可继续提问"))
      .toBe("今日匿名试用次数已用完，注册登录后可继续提问");
  });

  it("busy 在原文后补充行动句", () => {
    expect(noticeTextFor("busy", "系统繁忙，请稍后再试")).toContain("请稍等片刻再试");
  });

  it("空文案回退到兜底句", () => {
    expect(noticeTextFor("error", "")).toBe("生成失败，请稍后重试");
    expect(noticeTextFor("quota", undefined)).toBe("今日游客试用次数已用完");
  });
});

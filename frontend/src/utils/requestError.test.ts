import { describe, expect, it } from "vitest";
import { AxiosError } from "axios";

import { errorTextFor, isTransportError, markToastShown, wasToastShown } from "@/utils/requestError";

/**
 * 网络层错误判定 / 中文文案映射 / toast 去重标记的最小单元面。
 * 「网络层错误」口径 = axios 错误且未收到任何 HTTP 响应（连接被掐 / 超时 / 拒连），
 * 与后端返回的业务错误（code!==0）严格区分——后者不重试、由调用层提示。
 */

function networkError(code = "ERR_NETWORK"): AxiosError {
  return new AxiosError("Network Error", code);
}

describe("requestError", () => {
  it("无响应的 axios 错误判为网络层错误", () => {
    expect(isTransportError(networkError())).toBe(true);
    expect(isTransportError(networkError("ECONNABORTED"))).toBe(true);
  });

  it("带 HTTP 响应的错误与普通 Error 不算网络层错误", () => {
    const withResponse = new AxiosError("fail", undefined, undefined, undefined, {
      status: 500,
      data: {},
      headers: {},
      statusText: "",
      config: {} as never
    });
    expect(isTransportError(withResponse)).toBe(false);
    expect(isTransportError(new Error("业务错误"))).toBe(false);
    expect(isTransportError(null)).toBe(false);
  });

  it("网络层错误统一映射中文文案，超时另有文案", () => {
    expect(errorTextFor(networkError(), "兜底")).toBe("网络异常，请稍后重试");
    expect(errorTextFor(networkError("ECONNABORTED"), "兜底")).toBe("请求超时，请稍后重试");
  });

  it("非网络层错误透出 message，空 message 用兜底", () => {
    expect(errorTextFor(new Error("会话不存在"), "兜底")).toBe("会话不存在");
    expect(errorTextFor(new Error(""), "兜底")).toBe("兜底");
    expect(errorTextFor(undefined, "兜底")).toBe("兜底");
  });

  it("toast 标记可回读（WeakSet 语义，仅对象）", () => {
    const err = new Error("x");
    expect(wasToastShown(err)).toBe(false);
    markToastShown(err);
    expect(wasToastShown(err)).toBe(true);
    expect(wasToastShown("not an object")).toBe(false);
  });
});

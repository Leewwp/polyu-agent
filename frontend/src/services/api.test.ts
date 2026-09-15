import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  AxiosError,
  type AxiosAdapter,
  type AxiosResponse,
  type InternalAxiosRequestConfig
} from "axios";

const { toastError } = vi.hoisted(() => ({ toastError: vi.fn() }));
vi.mock("sonner", () => ({
  toast: { error: toastError, success: vi.fn() }
}));

import { api } from "@/services/api";
import { wasToastShown } from "@/utils/requestError";

/**
 * axios 实例硬化——
 * 1) GET timeout 60s→15s（SSE 走 fetch 不经此处，不受影响）；
 * 2) 幂等 GET 网络层错误自动重试 1 次（业务错误/非幂等请求一律不重试）；
 * 3) 网络层错误由拦截器统一弹中文文案，axios 英文原文（Network Error）不再示人；
 * 4) 拦截器弹过的错误打标记，调用层凭 wasToastShown 免重复弹。
 */

function okResponse(config: InternalAxiosRequestConfig, data: unknown): AxiosResponse {
  return { data, status: 200, statusText: "OK", headers: {}, config };
}

function transportError(config: InternalAxiosRequestConfig, code = "ERR_NETWORK"): AxiosError {
  return new AxiosError(code === "ECONNABORTED" ? "timeout of 15000ms exceeded" : "Network Error", code, config);
}

describe("services/api 拦截器", () => {
  let calls: InternalAxiosRequestConfig[];
  let respond: (config: InternalAxiosRequestConfig) => Promise<AxiosResponse>;

  beforeEach(() => {
    toastError.mockClear();
    calls = [];
    api.defaults.adapter = ((config: InternalAxiosRequestConfig) => {
      calls.push(config);
      return respond(config);
    }) as AxiosAdapter;
  });

  it("GET 超时收紧为 15s，非 GET 维持实例默认 60s", async () => {
    respond = (config) => Promise.resolve(okResponse(config, { code: "0", data: [] }));
    await api.get("/conversations");
    expect(calls[0].timeout).toBe(15000);
    await api.post("/conversations", { title: "x" });
    expect(calls[1].timeout).toBe(60000);
  });

  it("GET 网络层错误自动重试 1 次，重试成功则零 toast", async () => {
    let attempt = 0;
    respond = (config) => {
      attempt += 1;
      return attempt === 1
        ? Promise.reject(transportError(config))
        : Promise.resolve(okResponse(config, { code: "0", data: ["recovered"] }));
    };
    const data = await api.get("/conversations");
    expect(attempt).toBe(2);
    expect(data).toEqual(["recovered"]);
    expect(toastError).not.toHaveBeenCalled();
  });

  it("GET 重试仍失败：恰好一次重试、只弹一条中文文案、错误对象带已提示标记", async () => {
    respond = (config) => Promise.reject(transportError(config));
    const err = await api.get("/conversations").catch((error) => error);
    expect(err).toBeInstanceOf(AxiosError);
    expect(calls.length).toBe(2);
    expect(toastError).toHaveBeenCalledTimes(1);
    expect(toastError).toHaveBeenCalledWith("网络异常，请稍后重试");
    expect(wasToastShown(err)).toBe(true);
  });

  it("GET 超时（ECONNABORTED）同属网络层：重试一次后失败弹超时中文文案", async () => {
    respond = (config) => Promise.reject(transportError(config, "ECONNABORTED"));
    await expect(api.get("/conversations")).rejects.toBeInstanceOf(AxiosError);
    expect(calls.length).toBe(2);
    expect(toastError).toHaveBeenCalledTimes(1);
    expect(toastError).toHaveBeenCalledWith("请求超时，请稍后重试");
  });

  it("非幂等 POST 网络层错误不重试", async () => {
    respond = (config) => Promise.reject(transportError(config));
    await expect(api.post("/conversations", {})).rejects.toBeInstanceOf(AxiosError);
    expect(calls.length).toBe(1);
    expect(toastError).toHaveBeenCalledTimes(1);
  });

  it("业务错误（code!==0）不弹 toast、不重试，reject 交调用层提示", async () => {
    respond = (config) =>
      Promise.resolve(okResponse(config, { code: "A000001", message: "会话不存在", data: null }));
    await expect(api.get("/conversations/1/messages")).rejects.toThrow("会话不存在");
    expect(calls.length).toBe(1);
    expect(toastError).not.toHaveBeenCalled();
  });

  it("HTTP 500 带 message：弹后端 message 一条", async () => {
    respond = (config) =>
      Promise.reject(
        new AxiosError("Request failed with status code 500", undefined, config, undefined, {
          data: { message: "服务器开小差" },
          status: 500,
          statusText: "Internal Server Error",
          headers: {},
          config
        })
      );
    await expect(api.get("/dashboard")).rejects.toBeInstanceOf(AxiosError);
    expect(toastError).toHaveBeenCalledTimes(1);
    expect(toastError).toHaveBeenCalledWith("服务器开小差");
  });
});

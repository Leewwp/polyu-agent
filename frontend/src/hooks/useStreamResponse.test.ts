import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { createStreamResponse } from "./useStreamResponse";

/**
 * M16 SSE 重发闸：中途断流（已收到流字节）不重试——重发同一问句会让服务端
 * 整轮重跑（重复回答+双计费）；只有零字节失败（未建流）才保留重试。
 * Response 用鸭子类型手搓（jsdom 无 fetch 全家桶），只实现 readSseStream
 * 与 assertEventStreamOrThrow 触达的面。
 */

const encoder = new TextEncoder();

interface FakeStreamPlan {
  chunks: string[];
  /** chunk 耗尽后正常收尾（done）还是抛错（断连） */
  complete?: boolean;
  error?: Error;
}

function sseResponse(plan: FakeStreamPlan) {
  let index = 0;
  return {
    ok: true,
    status: 200,
    headers: {
      get: (name: string) => (name.toLowerCase() === "content-type" ? "text/event-stream" : null)
    },
    body: {
      getReader: () => ({
        read: async () => {
          if (index < plan.chunks.length) {
            const value = encoder.encode(plan.chunks[index]);
            index += 1;
            return { value, done: false };
          }
          if (plan.complete) {
            return { value: undefined, done: true };
          }
          throw plan.error ?? new Error("connection reset");
        }
      })
    }
  } as unknown as Response;
}

describe("useStreamResponse M16 断流重试闸", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("已收到流字节后断连：不重发，错误上抛", async () => {
    fetchMock.mockReturnValue(Promise.resolve(sseResponse({ chunks: ["data: hello\n\n"] })));
    const onMessage = vi.fn();
    const { start } = createStreamResponse(
      { url: "https://example.invalid/stream", retryCount: 1, retryDelayMs: 1 },
      { onMessage }
    );
    await expect(start()).rejects.toThrow("connection reset");
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(onMessage).toHaveBeenCalledTimes(1);
  });

  it("零字节失败（未建流）：保留一次重试", async () => {
    fetchMock
      .mockRejectedValueOnce(new TypeError("Failed to fetch"))
      .mockReturnValueOnce(Promise.resolve(sseResponse({ chunks: ["data: hi\n\n"], complete: true })));
    const onMessage = vi.fn();
    const { start } = createStreamResponse(
      { url: "https://example.invalid/stream", retryCount: 1, retryDelayMs: 1 },
      { onMessage }
    );
    await expect(start()).resolves.toBeUndefined();
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(onMessage).toHaveBeenCalledTimes(1);
  });

  it("完整收流（done 帧）后正常返回，无重试", async () => {
    fetchMock.mockReturnValue(
      Promise.resolve(sseResponse({ chunks: ["event: done\ndata: \n\n"], complete: true }))
    );
    const onDone = vi.fn();
    const { start } = createStreamResponse(
      { url: "https://example.invalid/stream", retryCount: 1, retryDelayMs: 1 },
      { onDone }
    );
    await expect(start()).resolves.toBeUndefined();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(onDone).toHaveBeenCalledTimes(1);
  });
});

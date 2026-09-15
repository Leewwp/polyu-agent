import type { CompletionPayload, MessageDeltaPayload, StreamMetaPayload } from "@/types";

export interface StreamHandlers {
  onMeta?: (payload: StreamMetaPayload) => void;
  onMessage?: (payload: MessageDeltaPayload) => void;
  onThinking?: (payload: MessageDeltaPayload) => void;
  onFinish?: (payload: CompletionPayload) => void;
  onDone?: () => void;
  onCancel?: (payload: CompletionPayload) => void;
  onReject?: (payload: MessageDeltaPayload) => void;
  onTitle?: (payload: { title: string }) => void;
  onError?: (error: Error) => void;
  onEvent?: (event: string, payload: unknown) => void;
}

export interface StreamOptions {
  url: string;
  headers?: Record<string, string>;
  signal?: AbortSignal;
  retryCount?: number;
  retryDelayMs?: number;
}

function parseData(raw: string): unknown {
  if (!raw) return "";
  try {
    return JSON.parse(raw);
  } catch {
    return raw;
  }
}

/**
 * 终态错误：业务拒绝（配额用尽/未登录等）不该走网络类重试，
 * 标记 terminal 后 streamWithRetry 直接抛出
 */
class TerminalStreamError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "TerminalStreamError";
  }
}

function isTerminalStreamError(error: unknown): boolean {
  return error instanceof TerminalStreamError;
}

/**
 * 建流前的业务错误体识别：SSE 端点在控制器层抛 ClientException 时返回
 * HTTP 200 + JSON Result（无 event-stream 内容），按 SSE 解析会变成静默死空气。
 * 这里在进入流读取前把 JSON 错误转成终态错误。
 */
async function assertEventStreamOrThrow(response: Response): Promise<void> {
  const contentType = response.headers.get("content-type") || "";
  if (contentType.includes("text/event-stream")) {
    return;
  }
  let message = "";
  try {
    const body = (await response.text()) || "";
    const parsed = parseData(body) as { message?: string } | null;
    message = (parsed && typeof parsed === "object" && typeof parsed.message === "string"
      ? parsed.message
      : "") || "";
  } catch {
    message = "";
  }
  if (response.status === 429) {
    throw new TerminalStreamError(message || "请求过于频繁，请稍后再试");
  }
  throw new TerminalStreamError(message || `SSE 请求失败（${response.status}）`);
}

async function readSseStream(response: Response, handlers: StreamHandlers, signal?: AbortSignal) {
  if (!response.body) {
    throw new Error("流式响应为空");
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";
  let eventName = "message";
  let dataLines: string[] = [];

  const dispatchEvent = () => {
    if (dataLines.length === 0) {
      eventName = "message";
      return;
    }
    const raw = dataLines.join("\n");
    const payload = parseData(raw);
    handlers.onEvent?.(eventName, payload);

    switch (eventName) {
      case "meta":
        handlers.onMeta?.(payload as StreamMetaPayload);
        break;
      case "message":
        {
          const messagePayload = payload as MessageDeltaPayload;
          if (messagePayload?.type === "think") {
            handlers.onThinking?.(messagePayload);
          }
          handlers.onMessage?.(messagePayload);
        }
        break;
      case "finish":
        handlers.onFinish?.(payload as CompletionPayload);
        break;
      case "done":
        handlers.onDone?.();
        break;
      case "cancel":
        handlers.onCancel?.(payload as CompletionPayload);
        break;
      case "reject":
        handlers.onReject?.(payload as MessageDeltaPayload);
        break;
      case "title":
        handlers.onTitle?.(payload as { title: string });
        break;
      case "error":
        handlers.onError?.(new Error(String((payload as { error?: string })?.error || payload)));
        break;
      default:
        break;
    }

    eventName = "message";
    dataLines = [];
  };

  for (;;) {
    if (signal?.aborted) {
      reader.cancel();
      break;
    }
    const { value, done } = await reader.read();
    if (done) {
      dispatchEvent();
      break;
    }
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split(/\r?\n/);
    buffer = lines.pop() ?? "";
    for (const line of lines) {
      if (!line) {
        dispatchEvent();
        continue;
      }
      if (line.startsWith(":")) {
        continue;
      }
      if (line.startsWith("event:")) {
        eventName = line.slice(6).trim();
        continue;
      }
      if (line.startsWith("data:")) {
        dataLines.push(line.slice(5).trim());
      }
    }
  }
}

async function streamWithRetry(
  options: StreamOptions,
  handlers: StreamHandlers
): Promise<void> {
  const { url, headers, signal } = options;
  const retryCount = options.retryCount ?? 2;
  const retryDelayMs = options.retryDelayMs ?? 600;

  let attempt = 0;
  while (attempt <= retryCount) {
    try {
      const response = await fetch(url, {
        method: "GET",
        headers: {
          Accept: "text/event-stream",
          ...headers
        },
        signal
      });

      if (!response.ok) {
        if (response.status === 429) {
          throw new TerminalStreamError("请求过于频繁，请稍后再试");
        }
        throw new Error(`SSE 请求失败（${response.status}）`);
      }

      // 业务拒绝（配额用尽等）会以 HTTP 200 + JSON 返回，先识别再进流读取
      await assertEventStreamOrThrow(response);
      await readSseStream(response, handlers, signal);
      return;
    } catch (error) {
      const err = error as Error;
      if (signal?.aborted) {
        throw err;
      }
      // 终态错误（业务拒绝）不重试：重试只会重复撞同一面墙
      if (isTerminalStreamError(err)) {
        throw err;
      }
      if (attempt >= retryCount) {
        throw err;
      }
      await new Promise((resolve) => setTimeout(resolve, retryDelayMs * Math.pow(2, attempt)));
      attempt += 1;
    }
  }
}

export function createStreamResponse(options: StreamOptions, handlers: StreamHandlers) {
  const controller = new AbortController();
  const mergedOptions = {
    ...options,
    signal: options.signal ?? controller.signal
  };

  return {
    start: () => streamWithRetry(mergedOptions, handlers),
    cancel: () => controller.abort()
  };
}

import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { FeedLangContext, FeedLangProvider, useFeedLang } from "./feedLang";

/**
 * 全局语言上下文——localStorage 偏好记忆与 Provider 外守卫。
 * 注：本项目 jsdom 30 环境不暴露 window.localStorage（项目 storage.ts 同以 try/catch 兜底），
 * 此处用内存桩注入后验证记忆逻辑本身。
 */

const STORAGE_KEY = "polyu.feed.lang";

function installLocalStorageStub(): Map<string, string> {
  const mem = new Map<string, string>();
  Object.defineProperty(window, "localStorage", {
    value: {
      getItem: (key: string) => mem.get(key) ?? null,
      setItem: (key: string, value: string) => void mem.set(key, value),
      removeItem: (key: string) => void mem.delete(key)
    },
    configurable: true
  });
  return mem;
}

function Probe() {
  const { lang, setLang } = useFeedLang();
  return (
    <button type="button" onClick={() => setLang(lang === "zh" ? "en" : "zh")}>
      {lang}
    </button>
  );
}

describe("feedLang", () => {
  let mem: Map<string, string>;

  beforeEach(() => {
    mem = installLocalStorageStub();
  });

  afterEach(() => {
    cleanup();
    Object.defineProperty(window, "localStorage", { value: undefined, configurable: true });
    // html lang 同步用例会改 documentElement.lang，还原默认防同文件后续用例漂移
    document.documentElement.lang = "zh";
  });

  it("defaults to zh for first-time visitors and persists switches", async () => {
    render(
      <FeedLangProvider>
        <Probe />
      </FeedLangProvider>
    );
    expect(screen.getByRole("button", { name: "zh" })).toBeTruthy();

    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "zh" }));

    expect(screen.getByRole("button", { name: "en" })).toBeTruthy();
    expect(mem.get(STORAGE_KEY)).toBe("en");
  });

  it("reuses the stored preference on mount and ignores invalid values", () => {
    mem.set(STORAGE_KEY, "en");
    const first = render(
      <FeedLangProvider>
        <Probe />
      </FeedLangProvider>
    );
    expect(screen.getByRole("button", { name: "en" })).toBeTruthy();
    first.unmount();

    mem.set(STORAGE_KEY, "fr");
    render(
      <FeedLangProvider>
        <Probe />
      </FeedLangProvider>
    );
    expect(screen.getByRole("button", { name: "zh" })).toBeTruthy();
  });

  it("stays functional without localStorage (session-only memory)", () => {
    Object.defineProperty(window, "localStorage", { value: undefined, configurable: true });
    expect(() =>
      render(
        <FeedLangProvider>
          <Probe />
        </FeedLangProvider>
      )
    ).not.toThrow();
    expect(screen.getByRole("button", { name: "zh" })).toBeTruthy();
  });

  it("throws when useFeedLang is used outside the provider", () => {
    expect(() => render(<Probe />)).toThrow(/FeedLangProvider/);
  });

  it("exposes the raw context for tests and providers", () => {
    expect(FeedLangContext).toBeTruthy();
  });

  it("syncs <html lang> to zh on first visit (mount-time, not click-time)", () => {
    render(
      <FeedLangProvider>
        <Probe />
      </FeedLangProvider>
    );
    // 首次直达：挂载即按持久化（无存储=默认 zh）落定，非仅点击切换时
    expect(document.documentElement.lang).toBe("zh");
  });

  it("syncs <html lang> to the persisted preference on mount (refresh / direct entry)", () => {
    // 持久化 EN 后直接打开轻量路由（无任何点击）：刷新/首达即 en
    mem.set(STORAGE_KEY, "en");
    render(
      <FeedLangProvider>
        <Probe />
      </FeedLangProvider>
    );
    expect(document.documentElement.lang).toBe("en");
  });

  it("updates <html lang> when switching languages at runtime", async () => {
    render(
      <FeedLangProvider>
        <Probe />
      </FeedLangProvider>
    );
    expect(document.documentElement.lang).toBe("zh");

    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "zh" }));

    expect(document.documentElement.lang).toBe("en");

    await user.click(screen.getByRole("button", { name: "en" }));
    expect(document.documentElement.lang).toBe("zh");
  });
});

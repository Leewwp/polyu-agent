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
});

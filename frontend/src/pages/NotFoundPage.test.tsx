import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { act } from "react";
import { MemoryRouter, Route, Routes } from "react-router-dom";

import { NotFoundPage } from "./NotFoundPage";
import { FeedLangProvider, useFeedLang } from "@/components/feed/feedLang";

/**
 * 404（#231/N6）：页面标题「页面不存在 · PolyUGuide」随语言；
 * noindex meta 挂载期在 head、离开即移除（SPA 软 404 对搜索引擎是 200
 * 带错误文案——不设 noindex 会被当正常页收录）。
 * jsdom 30 不暴露 window.localStorage（FeedShell.test 同款内存桩经验）。
 */

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

function ToEnglishButton() {
  const { setLang } = useFeedLang();
  return (
    <button type="button" onClick={() => setLang("en")}>
      to-en
    </button>
  );
}

function robotsMetas(): HTMLMetaElement[] {
  return Array.from(document.head.querySelectorAll<HTMLMetaElement>('meta[name="robots"]'));
}

describe("NotFoundPage title & noindex (#231)", () => {
  beforeEach(() => {
    installLocalStorageStub();
  });

  afterEach(() => {
    cleanup();
    Object.defineProperty(window, "localStorage", { value: undefined, configurable: true });
  });

  it("sets the stable page title and a noindex meta while mounted", () => {
    const view = render(
      <MemoryRouter>
        <FeedLangProvider>
          <NotFoundPage />
        </FeedLangProvider>
      </MemoryRouter>
    );
    expect(document.title).toBe("页面不存在 · PolyUGuide");
    expect(robotsMetas().map((meta) => meta.content)).toEqual(["noindex, nofollow"]);
    expect(screen.getByText("页面不存在")).toBeTruthy();

    view.unmount();
    // 离开 404 后：noindex 移除
    expect(robotsMetas()).toEqual([]);
  });

  it("follows the global language for the tab title", async () => {
    render(
      <MemoryRouter>
        <FeedLangProvider>
          <NotFoundPage />
          <ToEnglishButton />
        </FeedLangProvider>
      </MemoryRouter>
    );
    expect(document.title).toBe("页面不存在 · PolyUGuide");

    await act(async () => {
      screen.getByRole("button", { name: "to-en" }).click();
    });
    expect(document.title).toBe("Page not found · PolyUGuide");
    expect(screen.getByText("Page not found")).toBeTruthy();
  });
});

describe("NotFoundPage（#229 回到首页）", () => {
  afterEach(() => {
    cleanup();
  });

  it("主操作为回到首页且指到 /（不再返回聊天）", () => {
    render(
      <MemoryRouter initialEntries={["/no-such-page"]}>
        <FeedLangProvider>
          <Routes>
            <Route path="*" element={<NotFoundPage />} />
            <Route path="/" element={<div>HOME_PAGE_MARK</div>} />
            <Route path="/chat" element={<div>CHAT_PAGE_MARK</div>} />
          </Routes>
        </FeedLangProvider>
      </MemoryRouter>
    );

    const home = screen.getByRole("link", { name: "回到首页" });
    expect(home.getAttribute("href")).toBe("/");
    expect(screen.queryByText("返回聊天")).toBeNull();
  });

  it("轻量布局保留：提示卡片与页脚三法务链接在场", () => {
    render(
      <MemoryRouter initialEntries={["/no-such-page"]}>
        <FeedLangProvider>
          <Routes>
            <Route path="*" element={<NotFoundPage />} />
          </Routes>
        </FeedLangProvider>
      </MemoryRouter>
    );

    expect(screen.getByText("页面不存在")).toBeTruthy();
    expect(screen.getByRole("link", { name: "隐私声明" }).getAttribute("href")).toBe("/privacy");
    expect(screen.getByRole("link", { name: "服务条款" }).getAttribute("href")).toBe("/terms");
    expect(screen.getByRole("link", { name: "非官方声明" }).getAttribute("href")).toBe("/disclaimer");
  });
});

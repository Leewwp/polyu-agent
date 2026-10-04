import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { act } from "react";
import { MemoryRouter } from "react-router-dom";

import { FeedShell } from "./FeedShell";
import { FeedLangProvider, useFeedLang } from "./feedLang";
import { useAgentChatStore } from "@/stores/agentChatStore";
import { useChatStore } from "@/stores/chatStore";
import { useEngineStore } from "@/stores/engineStore";

/**
 * #231 标题单源 + 页面级 h1 落壳（63a C 实现位条款）：
 * - document.title=「页面名 · PolyUGuide」，随语言即时跟随；
 * - fluid 档标题优先值与顶栏同一次序：分享快照标题/当前会话标题 > 页面名；
 * - pageHeading：壳渲染 h1（桌面 min-[861px]:sr-only——clip 法去重复视觉页名，
 *   不以 display:none 摘出无障碍树；移动显示）；默认不渲染，避免与正文头双标题。
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

function renderContentShell(props: { title: { zh: string; en: string }; pageHeading?: boolean }) {
  return render(
    <MemoryRouter>
      <FeedLangProvider>
        <FeedShell title={props.title} pageHeading={props.pageHeading ?? false}>
          <p>shell-content</p>
        </FeedShell>
      </FeedLangProvider>
    </MemoryRouter>
  );
}

describe("FeedShell document.title single source (#231)", () => {
  beforeEach(() => {
    installLocalStorageStub();
    useEngineStore.setState({ engineType: null, loading: false, error: null });
    useChatStore.setState({ sessions: [], currentSessionId: null, sessionsLoaded: true });
    useAgentChatStore.setState({ sessions: [], currentSessionId: null, sessionsLoaded: true });
  });

  afterEach(() => {
    cleanup();
    Object.defineProperty(window, "localStorage", { value: undefined, configurable: true });
  });

  it("drives 「页面名 · PolyUGuide」 from the title prop and follows language switches", async () => {
    render(
      <MemoryRouter>
        <FeedLangProvider>
          <FeedShell title={{ zh: "精选", en: "Featured" }}>
            <p>shell-content</p>
          </FeedShell>
          <ToEnglishButton />
        </FeedLangProvider>
      </MemoryRouter>
    );
    expect(document.title).toBe("精选 · PolyUGuide");

    await act(async () => {
      screen.getByRole("button", { name: "to-en" }).click();
    });
    expect(document.title).toBe("Featured · PolyUGuide");
  });

  it("prefers the share snapshot title in shareView (null falls back to the page name)", () => {
    const { rerender } = render(
      <MemoryRouter>
        <FeedLangProvider>
          <FeedShell title={{ zh: "分享对话", en: "Shared conversation" }} fluid shareView={{ title: null }}>
            <p>share-body</p>
          </FeedShell>
        </FeedLangProvider>
      </MemoryRouter>
    );
    // 加载中/无效态：快照标题 null → 回落稳定页名（不展示空值）
    expect(document.title).toBe("分享对话 · PolyUGuide");

    rerender(
      <MemoryRouter>
        <FeedLangProvider>
          <FeedShell title={{ zh: "分享对话", en: "Shared conversation" }} fluid shareView={{ title: "博士申请材料清单" }}>
            <p>share-body</p>
          </FeedShell>
        </FeedLangProvider>
      </MemoryRouter>
    );
    expect(document.title).toBe("博士申请材料清单 · PolyUGuide");
  });

  it("prefers the current chat session title in fluid mode, falling back to the page name", () => {
    useEngineStore.setState({ engineType: "workflow" });
    const { rerender } = render(
      <MemoryRouter>
        <FeedLangProvider>
          <FeedShell title={{ zh: "智能问答", en: "Smart Q&A" }} fluid>
            <p>chat-body</p>
          </FeedShell>
        </FeedLangProvider>
      </MemoryRouter>
    );
    expect(document.title).toBe("智能问答 · PolyUGuide");

    useChatStore.setState({
      currentSessionId: "wf-9",
      sessions: [{ id: "wf-9", title: "图书馆开放时间咨询", lastTime: "2026-10-01" }]
    });
    rerender(
      <MemoryRouter>
        <FeedLangProvider>
          <FeedShell title={{ zh: "智能问答", en: "Smart Q&A" }} fluid>
            <p>chat-body</p>
          </FeedShell>
        </FeedLangProvider>
      </MemoryRouter>
    );
    expect(document.title).toBe("图书馆开放时间咨询 · PolyUGuide");
  });
});

describe("FeedShell page-level h1 (#231/N2)", () => {
  beforeEach(() => {
    installLocalStorageStub();
  });

  afterEach(() => {
    cleanup();
    Object.defineProperty(window, "localStorage", { value: undefined, configurable: true });
  });

  it("renders the page name as an h1 when pageHeading is on (desktop sr-only via clip, mobile visible)", () => {
    renderContentShell({ title: { zh: "热点榜", en: "Trending" }, pageHeading: true });
    const heading = screen.getByRole("heading", { level: 1, name: "热点榜" });
    // 桌面档 sr-only（clip 法——不摘出无障碍树，非 display:none）；
    // 移动档（≤860px）无 hidden/clip 类——正文显示页名
    expect(heading.className).toContain("min-[861px]:sr-only");
    expect(heading.className).not.toContain("hidden");
    // 壳顶栏页名仍由 DesktopTopbar 承载（div，非标题元素）——同屏无第二个视觉 h1
    expect(screen.getAllByRole("heading", { level: 1 })).toHaveLength(1);
  });

  it("renders no h1 by default (正文自带内容头的页面避免双标题)", () => {
    renderContentShell({ title: { zh: "资讯详情", en: "News" } });
    expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
  });

  it("follows the language for the shell-rendered h1 text", () => {
    document.title = "";
    const { container } = render(
      <MemoryRouter>
        <FeedLangProvider>
          <FeedShell title={{ zh: "关键日期", en: "Key dates" }} pageHeading>
            <p>shell-content</p>
          </FeedShell>
        </FeedLangProvider>
      </MemoryRouter>
    );
    expect(screen.getByRole("heading", { level: 1, name: "关键日期" })).toBeTruthy();
    expect(document.querySelector("html")!.lang).toBe("zh");
    void container;
  });
});

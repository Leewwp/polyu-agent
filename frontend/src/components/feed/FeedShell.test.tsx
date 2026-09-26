import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";

import { FeedShell } from "./FeedShell";
import { feedDateLabels } from "@/services/newsMapping";
import { useAgentChatStore } from "@/stores/agentChatStore";
import { useChatStore } from "@/stores/chatStore";
import { useEngineStore } from "@/stores/engineStore";

/**
 * MobileTopbar 补 LangPill——移动端顶栏与桌面顶栏同一全局语言值，
 * 任一 pill 切换两处同步（feed/hot/topics/detail 五页共用本壳）。
 * 2026-09-13：顶栏日期改实时 HKT 值（feedDateLabels）——断言改为
 * 同源计算（同一 Date 喂给组件与断言，jsdom 下 toLocaleDateString timeZone 可用）。
 * #136（2026-09-26）：移动顶栏去日期（五元素防挤占），日期断言收归桌面顶栏 long 档。
 * 注：jsdom 30 不暴露 window.localStorage（feedLang.test 同款内存桩经验）。
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

function renderShell() {
  return render(
    <MemoryRouter>
      <FeedShell title={{ zh: "精选", en: "Featured" }}>
        <p>shell-content</p>
      </FeedShell>
    </MemoryRouter>
  );
}

describe("FeedShell global language pills", () => {
  let mem: Map<string, string>;

  beforeEach(() => {
    mem = installLocalStorageStub();
  });

  afterEach(() => {
    cleanup();
    Object.defineProperty(window, "localStorage", { value: undefined, configurable: true });
  });

  it("renders a LangPill in both desktop and mobile topbars, kept in sync", async () => {
    renderShell();

    // 桌面 + 移动两个顶栏各一组 中/EN
    expect(screen.getAllByRole("button", { name: "中" })).toHaveLength(2);
    expect(screen.getAllByRole("button", { name: "EN" })).toHaveLength(2);
    // 顶栏日期只在桌面顶栏（long 档实时 HKT）；移动顶栏去日期（#136），短日期不得再现
    expect(screen.getByText(feedDateLabels(new Date(), "zh").long)).toBeTruthy();
    expect(screen.queryByText(feedDateLabels(new Date(), "zh").short)).toBeNull();

    const user = userEvent.setup();
    await user.click(screen.getAllByRole("button", { name: "EN" })[1]);

    // 两组 pill 同步切红、日期转英文、localStorage 记忆
    for (const pill of screen.getAllByRole("button", { name: "EN" })) {
      expect(pill.getAttribute("aria-pressed")).toBe("true");
    }
    expect(screen.getByText(feedDateLabels(new Date(), "en").long)).toBeTruthy();
    expect(mem.get(STORAGE_KEY)).toBe("en");
  });

  it("topbar date is real-time, not the retired 2026-09-10 mock constant", () => {
    renderShell();
    // 日期回归断言：写死常量「9月10日 · 周四」不得再现（任何日期的实时值都不含「9月10日」，
    // 除非真实跨到 9 月 10 日——用不含该子串的形状断言替代脆弱的日期排除法：
    // 直接断言渲染值与 feedDateLabels 当日计算一致已在上一用例覆盖，此处守常量删除回归）
    expect(screen.queryByText("9月10日 · 周四")).toBeNull();
    expect(screen.queryByText("9月10日 · 周四 · 2026")).toBeNull();
  });
});

describe("FeedShell fluid topbar session title", () => {
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

  /** 顶栏标题位=class 含 text-[17px] 的 div（会话标题与侧栏最近对话同文，按位置区分） */
  function topbarTitle(): string | null {
    const hits = screen.getAllByText(/./).filter(
      (el) => el.tagName === "DIV" && el.className.includes("text-[17px]")
    );
    return hits.length === 1 ? hits[0].textContent : null;
  }

  function renderFluidShell() {
    return render(
      <MemoryRouter>
        <FeedShell title={{ zh: "智能问答", en: "Smart Q&A" }} fluid>
          <p>chat-body</p>
        </FeedShell>
      </MemoryRouter>
    );
  }

  it("falls back to the page name while no current session exists", () => {
    renderFluidShell();
    expect(topbarTitle()).toBe("智能问答");
  });

  it("shows the current agent-engine session title once a session is active", () => {
    useEngineStore.setState({ engineType: "agent" });
    useAgentChatStore.setState({
      currentSessionId: "ag-1",
      sessions: [{ id: "ag-1", title: "博士申请材料清单", lastTime: "2026-09-13" }]
    });
    renderFluidShell();
    expect(topbarTitle()).toBe("博士申请材料清单");
  });

  it("reads the workflow store when the engine is workflow", () => {
    useEngineStore.setState({ engineType: "workflow" });
    useChatStore.setState({
      currentSessionId: "wf-1",
      sessions: [{ id: "wf-1", title: "图书馆开放时间咨询" }]
    });
    renderFluidShell();
    expect(topbarTitle()).toBe("图书馆开放时间咨询");
  });
});

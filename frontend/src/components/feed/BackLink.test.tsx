import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes, useNavigate } from "react-router-dom";

import { BackLink } from "./BackLink";
import { FeedLangProvider } from "./feedLang";

/**
 * feed 域共享返回出口 BackLink（#342）：
 * - history 语义：有站内历史（window.history.state.idx > 0，react-router
 *   BrowserHistory 逐跳写入的索引）→ navigate(-1) 精准回上一页——从日报进
 *   详情返回必须回日报，不落固定首页；直链/新标签（无站内历史）→ replace
 *   到自然父级 fallbackTo（replace 而非 push：不把中间态留进历史栈）；
 * - 文案=通用「返回 / Back」（目的地不可静态预知，跟随全局语言）；
 * - 无障碍名由文字承载，ArrowLeft 图标 aria-hidden。
 * jsdom 口径：MemoryRouter 与 window.history 解耦——state 由用例直接布景
 * （react-router BrowserHistory 才写 idx；直链=state null / idx 0）。
 */

/** jsdom 30 不暴露 window.localStorage（feedLang.test / NewsDetailPage.test 同款内存桩） */
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

/** 恒挂探针：回退后可再触发一次 navigate(-1) 验证 replace 未留栈 */
function GoBackProbe() {
  const navigate = useNavigate();
  return (
    <button type="button" onClick={() => navigate(-1)}>
      GO_BACK_PROBE
    </button>
  );
}

function renderBackLink(initialEntries: string[]) {
  return render(
    <MemoryRouter initialEntries={initialEntries}>
      <FeedLangProvider>
        <GoBackProbe />
        <Routes>
          {/* origin=真实上一页（≠fallback，用于证明返回的是历史而非回退路由） */}
          <Route path="/origin" element={<div>ORIGIN_PAGE</div>} />
          <Route path="/current" element={<BackLink fallbackTo="/fallback" />} />
          <Route path="/fallback" element={<div>FALLBACK_PAGE</div>} />
        </Routes>
      </FeedLangProvider>
    </MemoryRouter>
  );
}

describe("BackLink", () => {
  let mem: Map<string, string>;

  beforeEach(() => {
    mem = installLocalStorageStub();
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    Object.defineProperty(window, "localStorage", { value: undefined, configurable: true });
    // history.state 布景还原（用例间零残留）
    window.history.replaceState(null, "");
  });

  it("goes back one history entry when in-site history exists (从日报进详情回日报，不落 fallback)", async () => {
    // 站内已有一跳（react-router 语义 idx=2>0）；上一页=/origin≠fallback——
    // 返回必须精准落 /origin，落 /fallback 即视为功能设计问题（票面裁定）
    window.history.replaceState({ idx: 2, key: "x", usr: null }, "");
    renderBackLink(["/origin", "/current"]);

    await userEvent.setup().click(screen.getByRole("link", { name: "返回" }));
    expect(screen.getByText("ORIGIN_PAGE")).toBeTruthy();
    expect(screen.queryByText("FALLBACK_PAGE")).toBeNull();
  });

  it("replaces to the fallback route on direct entry without in-site history (直链/新标签)", async () => {
    // state=null=外链直开；replace 而非 push——再按一次后退仍停在 fallback
    // （若误用 push，栈里会残留 /current，后退即返回中间态）
    expect(window.history.state).toBeNull();
    renderBackLink(["/current"]);

    await userEvent.setup().click(screen.getByRole("link", { name: "返回" }));
    expect(screen.getByText("FALLBACK_PAGE")).toBeTruthy();

    await userEvent.setup().click(screen.getByRole("button", { name: "GO_BACK_PROBE" }));
    expect(screen.getByText("FALLBACK_PAGE")).toBeTruthy();
  });

  it("falls back when idx is 0 (首页直达详情后站内仅此一跳，无上一页可回)", async () => {
    // idx=0=站内首个条目（应用首达该路径）：与直链同形，落 fallback
    window.history.replaceState({ idx: 0, key: "x", usr: null }, "");
    renderBackLink(["/current"]);

    await userEvent.setup().click(screen.getByRole("link", { name: "返回" }));
    expect(screen.getByText("FALLBACK_PAGE")).toBeTruthy();
  });

  it("labels itself bilingually per the global feed lang (zh 默认 / en 随全局)", async () => {
    const { unmount } = renderBackLink(["/current"]);
    // zh 默认（localStorage 空 → readStoredLang 回落 zh）
    expect(screen.getByRole("link", { name: "返回" })).toBeTruthy();
    expect(screen.queryByRole("link", { name: "Back" })).toBeNull();
    unmount();
    cleanup();

    // en：localStorage 持久化偏好（FeedLangProvider 挂载即读取）
    mem.set("polyu.feed.lang", "en");
    renderBackLink(["/current"]);
    expect(screen.getByRole("link", { name: "Back" })).toBeTruthy();
    expect(screen.queryByRole("link", { name: "返回" })).toBeNull();
  });

  it("hides the arrow icon from the accessibility tree — name carried by the text (h-4 w-4 视觉锚)", () => {
    renderBackLink(["/current"]);

    const link = screen.getByRole("link", { name: "返回" });
    const svg = link.querySelector("svg");
    // accessible name 全由文字承载（getByRole name 命中即证）；图标退出无障碍树
    expect(svg?.getAttribute("aria-hidden")).toBe("true");
    expect(svg?.getAttribute("class")).toContain("h-4");
    expect(svg?.getAttribute("class")).toContain("w-4");
    expect(link.textContent).toBe("返回");
  });
});

import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { TopicFollowButton } from "./TopicFollowButton";
import { FeedLangContext } from "./feedLang";
import type { FeedLang } from "./feedLang";
import { NEWS_FOLLOWED_TOPICS_KEY, useNewsLocalStore } from "@/stores/newsLocalStore";

/**
 * #215 本地关注按钮（两触点共用）：
 * - 默认未关注「☆ 关注」→ 点击「★ 已关注」（aria-pressed 联动）→ 再点取关；
 * - 关注状态读写 useNewsLocalStore 实时值：同 slug 多实例同屏同步（非 getState 快照）；
 * - 点击写穿 localStorage（polyu.news.followedTopics）。
 */

/** jsdom 环境统一走内存桩（NewsDetailPage.test 同款经验），保证用例间零残留 */
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

function renderButton(lang: FeedLang = "zh") {
  return render(
    <FeedLangContext.Provider value={{ lang, setLang: () => {} }}>
      <TopicFollowButton slug="ai" nameZh="人工智能" nameEn="Artificial Intelligence" />
    </FeedLangContext.Provider>
  );
}

describe("TopicFollowButton", () => {
  let mem: Map<string, string>;

  beforeEach(() => {
    mem = installLocalStorageStub();
    useNewsLocalStore.getState().hydrate();
  });

  afterEach(() => {
    cleanup();
    Object.defineProperty(window, "localStorage", { value: undefined, configurable: true });
  });

  it("renders unfollowed by default and toggles follow/unfollow with aria-pressed", async () => {
    renderButton();
    const user = userEvent.setup();

    const button = screen.getByRole("button", { name: "☆ 关注" });
    expect(button.getAttribute("aria-pressed")).toBe("false");

    await user.click(button);
    const followed = screen.getByRole("button", { name: "★ 已关注" });
    expect(followed.getAttribute("aria-pressed")).toBe("true");
    // 写穿 localStorage：存 slug 稳定标识而非展示名
    expect(JSON.parse(mem.get(NEWS_FOLLOWED_TOPICS_KEY) ?? "[]")).toEqual(["ai"]);

    await user.click(followed);
    expect(screen.getByRole("button", { name: "☆ 关注" }).getAttribute("aria-pressed")).toBe("false");
    expect(JSON.parse(mem.get(NEWS_FOLLOWED_TOPICS_KEY) ?? "[]")).toEqual([]);
  });

  it("renders en labels (Follow/Following)", async () => {
    renderButton("en");
    const user = userEvent.setup();

    const button = screen.getByRole("button", { name: "☆ Follow" });
    await user.click(button);
    expect(screen.getByRole("button", { name: "★ Following" })).toBeTruthy();
  });

  it("keeps same-slug buttons in sync via live store reads (非 getState 快照判例)", async () => {
    render(
      <FeedLangContext.Provider value={{ lang: "zh", setLang: () => {} }}>
        <div>
          <TopicFollowButton slug="ai" nameZh="人工智能" nameEn="Artificial Intelligence" />
          <TopicFollowButton slug="ai" nameZh="人工智能" nameEn="Artificial Intelligence" />
        </div>
      </FeedLangContext.Provider>
    );
    const user = userEvent.setup();

    // 任一实例关注：同屏另一实例（模拟另一触点）即时回显已关注
    await user.click(screen.getAllByRole("button", { name: "☆ 关注" })[0]);
    expect(screen.getAllByRole("button", { name: "★ 已关注" })).toHaveLength(2);
  });
});

import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { cleanup, render } from "@testing-library/react";
import { act } from "react";

import { FeedLangProvider, useFeedLang } from "@/components/feed/feedLang";
import {
  resetPageTitleForTests,
  useDetailPageTitle,
  usePageTitle,
  type LocalizedTitle
} from "./usePageTitle";

/**
 * #231 标题单源机制（63a C + R2）：
 * - base（页面名）与 override（详情实际标题）同路径汇聚，后设覆盖、清理回落父级页面名；
 * - 语言切换即时跟随（双语页面名/双语详情标题重解析；string 详情标题不翻译）；
 * - 空值回落稳定页名、全空不写空标题；
 * - 页面切换（base 换值）即换标签——父壳与详情两个 effect 不相互覆写。
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

function BasePage({ title }: { title: LocalizedTitle }) {
  usePageTitle(title);
  return <p>base</p>;
}

function DetailValue({ title }: { title: LocalizedTitle | null }) {
  useDetailPageTitle(title);
  return <p>detail</p>;
}

/** 语言切换钮：借应用根 Provider 的真实 setLang 通道（与线上 LangPill 同源） */
function ToEnglishButton() {
  const { setLang } = useFeedLang();
  return (
    <button type="button" onClick={() => setLang("en")}>
      to-en
    </button>
  );
}

describe("usePageTitle mechanism", () => {
  beforeEach(() => {
    installLocalStorageStub();
    // 模块级单源跨用例隔离：每例从零页面名起步
    resetPageTitleForTests();
  });

  afterEach(() => {
    cleanup();
    Object.defineProperty(window, "localStorage", { value: undefined, configurable: true });
  });

  it("writes 「页面名 · PolyUGuide」 and follows page (route) changes", () => {
    const view = render(
      <FeedLangProvider>
        <BasePage title={{ zh: "精选", en: "Featured" }} />
      </FeedLangProvider>
    );
    expect(document.title).toBe("精选 · PolyUGuide");

    // 路由切换 = 卸载旧页面、挂新页面名
    view.unmount();
    render(
      <FeedLangProvider>
        <BasePage title={{ zh: "热点榜", en: "Trending" }} />
      </FeedLangProvider>
    );
    expect(document.title).toBe("热点榜 · PolyUGuide");
  });

  it("re-resolves the bilingual page name immediately on language switch", async () => {
    render(
      <FeedLangProvider>
        <BasePage title={{ zh: "登录", en: "Sign in" }} />
        <ToEnglishButton />
      </FeedLangProvider>
    );
    expect(document.title).toBe("登录 · PolyUGuide");

    await act(async () => {
      document.querySelector("button")!.click();
    });
    expect(document.title).toBe("Sign in · PolyUGuide");
  });

  it("re-resolves a bilingual detail title on language switch (string detail stays untranslated)", async () => {
    render(
      <FeedLangProvider>
        <BasePage title={{ zh: "资讯详情", en: "News" }} />
        <DetailValue title={{ zh: "港理工发布新政策", en: "PolyU announces new policy" }} />
        <ToEnglishButton />
      </FeedLangProvider>
    );
    expect(document.title).toBe("港理工发布新政策 · PolyUGuide");

    await act(async () => {
      document.querySelector("button")!.click();
    });
    expect(document.title).toBe("PolyU announces new policy · PolyUGuide");
  });

  it("unilingual detail values (snapshot/doc names) pass through as-is", () => {
    render(
      <FeedLangProvider>
        <BasePage title={{ zh: "分享对话", en: "Shared conversation" }} />
        <DetailValue title="博士申请材料清单" />
      </FeedLangProvider>
    );
    expect(document.title).toBe("博士申请材料清单 · PolyUGuide");
  });

  it("detail title overrides the page name and falls back to it on cleanup", () => {
    const detail = render(
      <FeedLangProvider>
        <BasePage title={{ zh: "资讯详情", en: "News" }} />
        <DetailValue title={{ zh: "港理工发布新政策", en: "PolyU announces new policy" }} />
      </FeedLangProvider>
    );
    expect(document.title).toBe("港理工发布新政策 · PolyUGuide");

    // 详情卸载（返回列表/离开页面）→ 回落父级页面名
    detail.rerender(
      <FeedLangProvider>
        <BasePage title={{ zh: "资讯详情", en: "News" }} />
        <DetailValue title={null} />
      </FeedLangProvider>
    );
    expect(document.title).toBe("资讯详情 · PolyUGuide");
  });

  it("empty/whitespace detail falls back to the stable page name; nothing writes an empty title", () => {
    document.title = "既有稳定标题 · PolyUGuide";
    render(
      <FeedLangProvider>
        <DetailValue title={"   "} />
      </FeedLangProvider>
    );
    // 无 base、override 全空：保持原值，不写空标题
    expect(document.title).toBe("既有稳定标题 · PolyUGuide");

    // base 就位后空 override 回落页面名
    cleanup();
    render(
      <FeedLangProvider>
        <BasePage title={{ zh: "文档预览", en: "Document preview" }} />
        <DetailValue title={""} />
      </FeedLangProvider>
    );
    expect(document.title).toBe("文档预览 · PolyUGuide");
  });

  it("换参数（详情 A → 详情 B）：后设覆盖，旧 effect 清理不写回旧标题", () => {
    const view = render(
      <FeedLangProvider>
        <BasePage title={{ zh: "资讯详情", en: "News" }} />
        <DetailValue title={{ zh: "主题 A 标题", en: "Topic A" }} />
      </FeedLangProvider>
    );
    expect(document.title).toBe("主题 A 标题 · PolyUGuide");

    view.rerender(
      <FeedLangProvider>
        <BasePage title={{ zh: "资讯详情", en: "News" }} />
        <DetailValue title={{ zh: "主题 B 标题", en: "Topic B" }} />
      </FeedLangProvider>
    );
    expect(document.title).toBe("主题 B 标题 · PolyUGuide");
  });

  it("route swap 详情→账号：清理先回落父级页面名，下一页 base 即刻接管", () => {
    const detail = render(
      <FeedLangProvider>
        <BasePage title={{ zh: "资讯详情", en: "News" }} />
        <DetailValue title="港理工发布新政策" />
      </FeedLangProvider>
    );
    expect(document.title).toBe("港理工发布新政策 · PolyUGuide");

    detail.unmount();
    // 清理回落父级页面名（真实路由切换中，下一页 effect 在同一提交内跟进覆写）
    expect(document.title).toBe("资讯详情 · PolyUGuide");

    render(
      <FeedLangProvider>
        <BasePage title={{ zh: "账号设置", en: "Account settings" }} />
      </FeedLangProvider>
    );
    expect(document.title).toBe("账号设置 · PolyUGuide");
  });
});

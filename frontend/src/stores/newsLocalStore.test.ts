import { beforeEach, describe, expect, it } from "vitest";

import {
  MAX_READ_ITEMS,
  NEWS_FOLLOWED_TOPICS_KEY,
  NEWS_READ_ITEMS_KEY,
  createNewsLocalStore,
  useNewsLocalStore
} from "@/stores/newsLocalStore";

/**
 * #215 浏览器本地「关注主题/已读」（newsLocalStore）：
 * - 持久层唯一=localStorage（polyu.news.* 两键）：关注/已读写穿同步；
 * - 脏数据容错：损坏 JSON/非数组/非字符串项降级空数组不崩；
 * - 清除站点数据（清空 localStorage）→ hydrate 即状态归零（无 sessionStorage/服务端镜像）；
 * - 无痕窗口互不污染：每个浏览剖面一份独立 storage 实例，两实例互不可见；
 * - 已读：幂等+最新在前+MAX_READ_ITEMS 滚动淘汰。
 */

/** 内存 Storage 桩：一个实例=一个独立浏览会话剖面（普通窗口/无痕窗口各自一份） */
function createMemoryStorage(): Storage {
  const mem = new Map<string, string>();
  return {
    get length() {
      return mem.size;
    },
    clear: () => mem.clear(),
    getItem: (key: string) => mem.get(key) ?? null,
    key: (index: number) => Array.from(mem.keys())[index] ?? null,
    removeItem: (key: string) => void mem.delete(key),
    setItem: (key: string, value: string) => void mem.set(key, String(value))
  } as Storage;
}

describe("newsLocalStore(#215 本地关注/已读)", () => {
  beforeEach(() => {
    // 显式清理：默认单例绑定 window.localStorage，清空+重新装配保证用例间零残留
    window.localStorage.clear();
    useNewsLocalStore.getState().hydrate();
  });

  it("关注→取关：内存态与 localStorage 写穿同步（存 slug 稳定标识）", () => {
    const area = createMemoryStorage();
    const store = createNewsLocalStore(() => area);

    store.getState().toggleTopicFollow("ai");
    expect(store.getState().followedTopics).toEqual(["ai"]);
    expect(JSON.parse(area.getItem(NEWS_FOLLOWED_TOPICS_KEY) ?? "[]")).toEqual(["ai"]);

    store.getState().toggleTopicFollow("eng");
    expect(store.getState().followedTopics).toEqual(["ai", "eng"]);

    store.getState().toggleTopicFollow("ai");
    expect(store.getState().followedTopics).toEqual(["eng"]);
    expect(JSON.parse(area.getItem(NEWS_FOLLOWED_TOPICS_KEY) ?? "[]")).toEqual(["eng"]);
  });

  it("默认单例绑定 window.localStorage（关注持久化到真实存储区）", () => {
    useNewsLocalStore.getState().toggleTopicFollow("ai");
    expect(useNewsLocalStore.getState().followedTopics).toEqual(["ai"]);
    expect(JSON.parse(window.localStorage.getItem(NEWS_FOLLOWED_TOPICS_KEY) ?? "[]")).toEqual(["ai"]);
  });

  it("脏数据容错：损坏 JSON/非数组/非字符串项一律降级为空数组，不抛错", () => {
    const area = createMemoryStorage();
    area.setItem(NEWS_FOLLOWED_TOPICS_KEY, "{broken json");
    area.setItem(NEWS_READ_ITEMS_KEY, JSON.stringify({ ids: ["mock-001"] }));

    const dirty = createNewsLocalStore(() => area);
    expect(dirty.getState().followedTopics).toEqual([]);
    expect(dirty.getState().readItems).toEqual([]);

    // 数组内混入非字符串项：仅保留字符串（其余丢弃），不整体失效
    area.setItem(NEWS_FOLLOWED_TOPICS_KEY, JSON.stringify(["ai", 42, null, true, "eng"]));
    const partial = createNewsLocalStore(() => area);
    expect(partial.getState().followedTopics).toEqual(["ai", "eng"]);
  });

  it("清除站点数据（清空 localStorage）→ hydrate 后关注/已读全部归零", () => {
    useNewsLocalStore.getState().toggleTopicFollow("ai");
    useNewsLocalStore.getState().markItemRead("mock-001");
    expect(useNewsLocalStore.getState().followedTopics).toEqual(["ai"]);
    expect(useNewsLocalStore.getState().readItems).toEqual(["mock-001"]);

    // 「清除站点数据」=localStorage 清空（HTTP 缓存是另一存储，不在此列）
    window.localStorage.clear();
    useNewsLocalStore.getState().hydrate();

    expect(useNewsLocalStore.getState().followedTopics).toEqual([]);
    expect(useNewsLocalStore.getState().readItems).toEqual([]);
  });

  it("已读：幂等、最新在前、容量上限滚动淘汰", () => {
    const store = createNewsLocalStore(() => createMemoryStorage());

    store.getState().markItemRead("n1");
    store.getState().markItemRead("n1"); // 幂等：重复标记不产生重复项
    expect(store.getState().readItems).toEqual(["n1"]);

    store.getState().markItemRead("n2");
    expect(store.getState().readItems).toEqual(["n2", "n1"]); // 最新在前

    // 灌超上限：最旧的 n1/n2 被滚动淘汰，驻留恰为 MAX_READ_ITEMS 条
    for (let i = 3; i <= MAX_READ_ITEMS + 2; i += 1) {
      store.getState().markItemRead(`n${i}`);
    }
    const readItems = store.getState().readItems;
    expect(readItems).toHaveLength(MAX_READ_ITEMS);
    expect(readItems[0]).toBe(`n${MAX_READ_ITEMS + 2}`);
    expect(readItems).toContain(`n${MAX_READ_ITEMS + 2 - MAX_READ_ITEMS + 1}`);
    expect(readItems).not.toContain("n1");
    expect(readItems).not.toContain("n2");
  });

  it("无痕窗口互不污染：两个独立 storage 实例（浏览剖面）互不可见", () => {
    const profileA = createMemoryStorage(); // 普通窗口剖面
    const profileB = createMemoryStorage(); // 无痕窗口剖面
    const storeA = createNewsLocalStore(() => profileA);
    const storeB = createNewsLocalStore(() => profileB);

    storeA.getState().toggleTopicFollow("ai");
    storeA.getState().markItemRead("mock-001");
    storeB.getState().toggleTopicFollow("eng");

    // A 只见自己写入的：ai 关注/mock-001 已读；不见 B 的 eng
    expect(storeA.getState().followedTopics).toEqual(["ai"]);
    expect(storeA.getState().readItems).toEqual(["mock-001"]);
    // B 只见自己写入的 eng；不见 A 的 ai/已读记录
    expect(storeB.getState().followedTopics).toEqual(["eng"]);
    expect(storeB.getState().readItems).toEqual([]);

    // 存储层同样隔离：各剖面键值互不串扰，B 重新装配仍拾取不到 A 的数据
    expect(JSON.parse(profileA.getItem(NEWS_FOLLOWED_TOPICS_KEY) ?? "[]")).toEqual(["ai"]);
    expect(profileB.getItem(NEWS_READ_ITEMS_KEY)).toBeNull();
    storeB.getState().hydrate();
    expect(storeB.getState().followedTopics).toEqual(["eng"]);
    expect(storeB.getState().readItems).toEqual([]);
  });

  it("storage 区不可用（隐私模式禁写等）时降级为会话内状态，不崩", () => {
    const noArea = createNewsLocalStore(() => null);
    expect(noArea.getState().followedTopics).toEqual([]);
    noArea.getState().toggleTopicFollow("ai");
    noArea.getState().markItemRead("mock-001");
    expect(noArea.getState().followedTopics).toEqual(["ai"]);
    expect(noArea.getState().readItems).toEqual(["mock-001"]);
  });

  it("已读写穿 localStorage（详情页进入即标的持久层落点）", () => {
    useNewsLocalStore.getState().markItemRead("mock-001");
    expect(JSON.parse(window.localStorage.getItem(NEWS_READ_ITEMS_KEY) ?? "[]")).toEqual(["mock-001"]);
  });
});

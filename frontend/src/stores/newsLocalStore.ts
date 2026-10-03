import { create } from "zustand";

/**
 * #215 浏览器本地「关注主题/已读」（纯前端，零后端零账号）：
 * - 唯一持久层=localStorage（键命名空间 polyu.news.*，与 feedLang 的 polyu.feed.lang 同族），
 *   不落 sessionStorage、无服务端镜像——「清除站点数据」即全部重置；
 * - 无痕窗口=独立浏览剖面（各自一份 localStorage），天然互不污染；
 * - 存稳定标识：关注=主题 slug（非展示名），已读=资讯条目 id；
 * - 脏数据容错：损坏 JSON/非数组/非字符串项一律降级为空数组，不抛错；
 * - 容量护栏：已读列表最近优先、上限 MAX_READ_ITEMS 滚动淘汰，防 localStorage 无界膨胀；
 * - 写失败（配额满/隐私模式禁写）静默降级为会话内状态，不崩。
 *
 * 组件判例：读 store 实时值（useNewsLocalStore(selector)），勿用 getState() 快照。
 */

/** localStorage 键：关注主题（slug 数组） */
export const NEWS_FOLLOWED_TOPICS_KEY = "polyu.news.followedTopics";
/** localStorage 键：已读资讯（条目 id 数组，最新在前） */
export const NEWS_READ_ITEMS_KEY = "polyu.news.readItems";

/** 已读驻留上限：超出滚动淘汰最旧（500 条 id 量级对 5MB 配额无压力） */
export const MAX_READ_ITEMS = 500;

/** 最小存储面：window.localStorage 与测试内存桩同形（无痕隔离测试注入独立实例用） */
export interface NewsStorageArea {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

/** 每次读写时惰性解析当前存储区：测试用 defineProperty 后装/换桩也能被单例拾取 */
export type NewsStorageAreaResolver = () => NewsStorageArea | null;

function defaultStorageAreaResolver(): NewsStorageArea | null {
  try {
    const area = typeof window === "undefined" ? undefined : window.localStorage;
    return area && typeof area.getItem === "function" ? area : null;
  } catch {
    // 某些隐私模式访问 window.localStorage 即抛——降级为纯会话内
    return null;
  }
}

/** 读字符串数组键：空/损坏 JSON/非数组降级 []；数组内仅保留字符串项（脏数据容错） */
function readSlugArray(area: NewsStorageArea, key: string): string[] {
  let raw: string | null;
  try {
    raw = area.getItem(key);
  } catch {
    return [];
  }
  if (!raw) {
    return [];
  }
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) {
      return [];
    }
    return parsed.filter((entry): entry is string => typeof entry === "string");
  } catch {
    return [];
  }
}

function writeSlugArray(area: NewsStorageArea, key: string, value: string[]): void {
  try {
    area.setItem(key, JSON.stringify(value));
  } catch {
    // 配额满/禁写：保持内存态，静默
  }
}

export interface NewsLocalState {
  /** 已关注主题 slug 列表 */
  followedTopics: string[];
  /** 已读资讯 id 列表（最新在前，上限 MAX_READ_ITEMS） */
  readItems: string[];
  /** 关注/取关（写穿 localStorage） */
  toggleTopicFollow: (slug: string) => void;
  /** 标记已读（幂等；最新在前，超限滚动淘汰） */
  markItemRead: (id: string) => void;
  /** 从 localStorage 重新装配：页面装载语义（测试模拟清站点数据后复位用） */
  hydrate: () => void;
}

/**
 * 工厂：可注入独立存储区（无痕隔离测试=每个会话剖面一个实例）。
 * 默认绑定 window.localStorage（惰性解析）。
 */
export function createNewsLocalStore(resolveArea: NewsStorageAreaResolver = defaultStorageAreaResolver) {
  const resolve = (): NewsStorageArea | null => {
    try {
      return resolveArea();
    } catch {
      return null;
    }
  };
  const readSnapshot = () => {
    const area = resolve();
    return {
      followedTopics: area ? readSlugArray(area, NEWS_FOLLOWED_TOPICS_KEY) : [],
      readItems: area ? readSlugArray(area, NEWS_READ_ITEMS_KEY) : []
    };
  };
  return create<NewsLocalState>((set, get) => ({
    ...readSnapshot(),
    toggleTopicFollow: (slug) => {
      const prev = get().followedTopics;
      const next = prev.includes(slug) ? prev.filter((entry) => entry !== slug) : [...prev, slug];
      const area = resolve();
      if (area) {
        writeSlugArray(area, NEWS_FOLLOWED_TOPICS_KEY, next);
      }
      set({ followedTopics: next });
    },
    markItemRead: (id) => {
      const prev = get().readItems;
      if (prev.includes(id)) {
        return;
      }
      const next = [id, ...prev].slice(0, MAX_READ_ITEMS);
      const area = resolve();
      if (area) {
        writeSlugArray(area, NEWS_READ_ITEMS_KEY, next);
      }
      set({ readItems: next });
    },
    hydrate: () => set(readSnapshot())
  }));
}

/** 默认单例：feed 域页面/组件消费（模块装载时从 localStorage 装配初值） */
export const useNewsLocalStore = createNewsLocalStore();

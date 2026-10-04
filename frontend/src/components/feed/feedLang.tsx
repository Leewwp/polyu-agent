import { createContext, useCallback, useContext, useEffect, useState } from "react";
import type { ReactNode } from "react";

/**
 * 全站中英语言总闸（#227 自 FeedShell 提根）：数据级双语——VO 双语字段直读，
 * 不引入 i18n 框架，延续 LegalShell 内联双语先例。Provider 挂在应用根
 * （App.tsx 包裹 RouterProvider），资讯壳不再自带第二层独立语言状态；
 * 壳外轻量页（404/法务/登录系/账号/两类分享）经 useOptionalFeedLang 消费同一全局值。
 * <html lang> 随 lang 同步（挂载即按持久化偏好设置——首达/刷新场景，非仅点击时）。
 */

export type FeedLang = "zh" | "en";

interface FeedLangContextValue {
  lang: FeedLang;
  setLang: (lang: FeedLang) => void;
}

export const FeedLangContext = createContext<FeedLangContextValue | null>(null);

/** 语言偏好记忆（localStorage 存 lang，回访沿用） */
const FEED_LANG_STORAGE_KEY = "polyu.feed.lang";

function readStoredLang(): FeedLang {
  try {
    return window.localStorage.getItem(FEED_LANG_STORAGE_KEY) === "en" ? "en" : "zh";
  } catch {
    return "zh";
  }
}

function storeLang(lang: FeedLang): void {
  try {
    window.localStorage.setItem(FEED_LANG_STORAGE_KEY, lang);
  } catch {
    // localStorage 不可用（隐私模式等）时静默降级为会话内记忆
  }
}

export function FeedLangProvider({ children }: { children: ReactNode }) {
  const [lang, setLangState] = useState<FeedLang>(readStoredLang);
  const setLang = useCallback((next: FeedLang) => {
    setLangState(next);
    storeLang(next);
  }, []);
  // <html lang> 同步（N5/#227）：effect 而非仅点击时写——挂载即按持久化偏好落定
  // （首达/刷新持久化 EN 后直接打开轻量路由也正确），切换后自然跟随。
  useEffect(() => {
    document.documentElement.lang = lang;
  }, [lang]);
  return <FeedLangContext.Provider value={{ lang, setLang }}>{children}</FeedLangContext.Provider>;
}

/**
 * 不可变内容语言探测（#227）：分享快照等「不随 UI 语言翻译」的正文，按实际
 * 内容语言标注 lang 属性（全局偏好≠段落内容语言——63a A 判例）。含 CJK
 * 汉字即视为中文，否则英文；空文本回落 UI 默认 zh。
 */
export function contentLangOf(text: string): FeedLang {
  return /[\u4e00-\u9fff]/.test(text) ? "zh" : "en";
}

export function useFeedLang(): FeedLangContextValue {
  const value = useContext(FeedLangContext);
  if (!value) {
    throw new Error("useFeedLang 必须在 FeedLangProvider（应用根）内使用");
  }
  return value;
}

/**
 * 可选版：壳外轻量页（404/法务/登录系/账号/答案分享页的页脚与操作钮，#227 消费面）
 * 与聊天域组件（ChatPage/AgentChatPage 的输入条/游客徽章/思考块等）消费同一全局语言；
 * 单测等场景可能脱离应用根 Provider 单独 render——无 Provider 时回退中文
 * （默认语言），不 throw（2026-09-12 修复；#227 提根后语义保留）。
 */
export function useOptionalFeedLang(): FeedLangContextValue {
  return (
    useContext(FeedLangContext) ?? {
      lang: "zh",
      setLang: () => undefined
    }
  );
}

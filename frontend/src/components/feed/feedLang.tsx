import { createContext, useCallback, useContext, useState } from "react";
import type { ReactNode } from "react";

/**
 * 资讯流全局中英语言（数据级双语——VO 双语字段直读，不引入 i18n 框架，
 * 延续 LegalShell 内联双语先例）。Provider 挂在 FeedShell 顶层，单卡「中 / EN」
 * 覆盖态在 NewsCard 内叠加于此全局值之上。
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
  return <FeedLangContext.Provider value={{ lang, setLang }}>{children}</FeedLangContext.Provider>;
}

export function useFeedLang(): FeedLangContextValue {
  const value = useContext(FeedLangContext);
  if (!value) {
    throw new Error("useFeedLang 必须在 FeedLangProvider（FeedShell）内使用");
  }
  return value;
}

/**
 * 可选版：聊天壳组件（ChatPage/AgentChatPage 域的输入条/游客徽章/思考块等）
 * 同样跟随全局语言 pill，但单测等场景可能脱离 FeedShell 单独 render——
 * 无 Provider 时回退中文（默认语言），不 throw（2026-09-12 修复）。
 */
export function useOptionalFeedLang(): FeedLangContextValue {
  return (
    useContext(FeedLangContext) ?? {
      lang: "zh",
      setLang: () => undefined
    }
  );
}

import { RouterProvider } from "react-router-dom";

import { ErrorBoundary } from "@/components/common/ErrorBoundary";
import { Toast } from "@/components/common/Toast";
import { FeedLangProvider } from "@/components/feed/feedLang";
import { router } from "@/router";

/**
 * 应用根：语言 Provider 挂根（#227 总闸）——全站（资讯壳+壳外轻量页）共享
 * 同一语言状态，资讯壳内不再有第二层独立语言状态；<html lang> 由 Provider
 * 随持久化偏好与切换同步。
 */
export default function App() {
  return (
    <ErrorBoundary>
      <FeedLangProvider>
        <RouterProvider router={router} />
        <Toast />
      </FeedLangProvider>
    </ErrorBoundary>
  );
}

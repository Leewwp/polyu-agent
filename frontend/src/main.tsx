import React from "react";
import ReactDOM from "react-dom/client";

import App from "@/App";
import { useAuthStore } from "@/stores/authStore";
import { useThemeStore } from "@/stores/themeStore";
import "@/styles/globals.css";

useThemeStore.getState().initialize();
// cookie 化：登录态无法本地判定（HttpOnly Cookie 对 JS 不可读），首帧渲染等
// /user/me 探针落定，避免已登录用户闪登录页；公开页探针失败不触发任何跳转
useAuthStore
  .getState()
  .checkAuth()
  .catch(() => null)
  .finally(() => {
    ReactDOM.createRoot(document.getElementById("root")!).render(
      <React.StrictMode>
        <App />
      </React.StrictMode>
    );
  });

let scrollIdleTimer: ReturnType<typeof setTimeout> | null = null;
const handleScrollActivity = () => {
  document.documentElement.classList.add("is-scrolling");
  if (scrollIdleTimer) clearTimeout(scrollIdleTimer);
  scrollIdleTimer = setTimeout(() => {
    document.documentElement.classList.remove("is-scrolling");
  }, 800);
};
window.addEventListener("scroll", handleScrollActivity, { capture: true, passive: true });
window.addEventListener("wheel", handleScrollActivity, { capture: true, passive: true });
window.addEventListener("touchmove", handleScrollActivity, { capture: true, passive: true });

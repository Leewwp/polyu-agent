/* eslint-disable */

import { create } from "zustand";
import { toast } from "sonner";

import type { CurrentUser } from "@/types";
import {
  getCurrentUser,
  guestLogin as guestLoginRequest,
  login as loginRequest,
  logout as logoutRequest
} from "@/services/authService";
import { resetChatStoresForAccountSwitch } from "@/stores/sessionReset";
import { storage } from "@/utils/storage";
import { isTransportError, toastErrorUnlessShown } from "@/utils/requestError";

/**
 * 会话载体 cookie 化：登录态由后端 HttpOnly Cookie 承载（Sa-Token is-read-cookie），
 * 前端不再持有/持久化 token——localStorage 只剩用户展示信息，登录态判定一律以
 * /user/me 探针为准（cookie 对 JS 不可读，无法本地判断）。token 字段与 setAuthToken
 * 接线随 localStorage 路径一并移除。
 */
interface AuthState {
  user: CurrentUser | null;
  isAuthenticated: boolean;
  /** 游客会话标记（2026-09-14 修复）：游客铸号后 isAuthenticated
   *  同样为 true（/chat 的 RequireAuth 须放行游客），但登录/注册/找回页的
   *  RedirectIfAuth 不得视游客为已登录——否则三页对游客永远不可达，无法转正。 */
  isGuest: boolean;
  isLoading: boolean;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  /** 游客直通铸号：失败时 throw（业务提示由 api 拦截器统一 toast），调用方引导登录 */
  guestLogin: () => Promise<void>;
  checkAuth: () => Promise<void>;
  fetchCurrentUser: () => Promise<void>;
}

export const useAuthStore = create<AuthState>((set, get) => ({
  user: null,
  isAuthenticated: false,
  isGuest: false,
  isLoading: false,
  login: async (username, password) => {
    set({ isLoading: true });
    try {
      await loginRequest(username, password);
      // token 只活在 HttpOnly Cookie 里；用户信息以 /user/me 为准
      set({ user: null, isAuthenticated: true, isGuest: false });
      get().fetchCurrentUser().catch(() => null);
      // M15：双引擎对称清场——agentChatStore 同步归零，换号后不残留上一账号的会话
      resetChatStoresForAccountSwitch({ isCreatingNew: true });
      toast.success("登录成功");
    } catch (error) {
      toastErrorUnlessShown(error, "登录失败");
      throw error;
    } finally {
      set({ isLoading: false });
    }
  },
  logout: async () => {
    try {
      // 后端登出同时清 Cookie（sa-token is-read-cookie：以增代删 maxAge=0）
      await logoutRequest();
    } catch {
      // Ignore network errors on logout
    }
    // M15：与 login 对称的双引擎清场（isCreatingNew=false：登出后无人续用会话态）
    resetChatStoresForAccountSwitch({ isCreatingNew: false });
    // 同时清掉 cookie 化之前遗留的 localStorage token（迁移清道夫）
    storage.clearAuth();
    set({ user: null, isAuthenticated: false, isGuest: false });
    toast.success("已退出登录");
  },
  guestLogin: async () => {
    set({ isLoading: true });
    try {
      // cookie 档：会话由后端 Set-Cookie 承载（后端已登录游客会复用，不重复铸号）；
      // M15：铸号成功同样双引擎清场——真实账号登出后转游客（或游客转真实账号），
      // 残留的上一身份会话不再被 /chat 续用（AgentChatPage 的 currentSessionId 早退）
      // 失败不在此 toast——api 拦截器已对业务错误给出提示，调用方负责引导登录
      await guestLoginRequest();
      set({ user: null, isAuthenticated: true, isGuest: true });
      get().fetchCurrentUser().catch(() => null);
      resetChatStoresForAccountSwitch({ isCreatingNew: true });
      toast.success("已进入游客模式");
    } finally {
      set({ isLoading: false });
    }
  },
  checkAuth: async () => {
    // 旧 localStorage token 一律清除（HttpOnly Cookie 后 JS 无法也无须读取）
    storage.clearToken();
    try {
      const data = await getCurrentUser();
      set({ user: data, isAuthenticated: true, isGuest: data?.role === "guest" });
    } catch (error) {
      // L37：网络层失败（断网/超时/拒连）探针没有结论，不算「未登录」——
      // 保留既有状态不清 user，避免把在线用户误踢去 /login；
      // 只有后端真实回话（业务错误）才判定未登录
      if (!isTransportError(error)) {
        storage.clearUser();
        set({ user: null, isAuthenticated: false });
      }
    }
  },
  fetchCurrentUser: async () => {
    if (!get().isAuthenticated) return;
    try {
      const data = await getCurrentUser();
      set({ user: data, isGuest: data?.role === "guest" });
    } catch {
      return;
    }
  }
}));

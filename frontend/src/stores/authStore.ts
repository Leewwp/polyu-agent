/* eslint-disable */

import { create } from "zustand";
import { toast } from "sonner";

import type { CurrentUser } from "@/types";
import { getCurrentUser, login as loginRequest, logout as logoutRequest } from "@/services/authService";
import { useChatStore } from "@/stores/chatStore";
import { storage } from "@/utils/storage";

/**
 * U3 会话载体 cookie 化：登录态由后端 HttpOnly Cookie 承载（Sa-Token is-read-cookie），
 * 前端不再持有/持久化 token——localStorage 只剩用户展示信息，登录态判定一律以
 * /user/me 探针为准（cookie 对 JS 不可读，无法本地判断）。token 字段与 setAuthToken
 * 接线随 localStorage 路径一并移除。
 */
interface AuthState {
  user: CurrentUser | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  checkAuth: () => Promise<void>;
  fetchCurrentUser: () => Promise<void>;
}

export const useAuthStore = create<AuthState>((set, get) => ({
  user: null,
  isAuthenticated: false,
  isLoading: false,
  login: async (username, password) => {
    set({ isLoading: true });
    try {
      await loginRequest(username, password);
      // token 只活在 HttpOnly Cookie 里；用户信息以 /user/me 为准
      set({ user: null, isAuthenticated: true });
      get().fetchCurrentUser().catch(() => null);
      useChatStore.getState().cancelGeneration();
      useChatStore.setState({
        sessions: [],
        currentSessionId: null,
        messages: [],
        isLoading: false,
        isStreaming: false,
        isCreatingNew: true,
        deepThinkingEnabled: false,
        thinkingStartAt: null,
        streamTaskId: null,
        streamAbort: null,
        streamingMessageId: null,
        cancelRequested: false
      });
      toast.success("登录成功");
    } catch (error) {
      toast.error((error as Error).message || "登录失败");
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
    useChatStore.getState().cancelGeneration();
    useChatStore.setState({
      sessions: [],
      currentSessionId: null,
      messages: [],
      isLoading: false,
      isStreaming: false,
      isCreatingNew: false,
      deepThinkingEnabled: false,
      thinkingStartAt: null,
      streamTaskId: null,
      streamAbort: null,
      streamingMessageId: null,
      cancelRequested: false
    });
    // 同时清掉 cookie 化之前遗留的 localStorage token（迁移清道夫）
    storage.clearAuth();
    set({ user: null, isAuthenticated: false });
    toast.success("已退出登录");
  },
  checkAuth: async () => {
    // 旧 localStorage token 一律清除（U3 迁移；HttpOnly Cookie 后 JS 无法也无须读取）
    storage.clearToken();
    try {
      const data = await getCurrentUser();
      set({ user: data, isAuthenticated: true });
    } catch {
      storage.clearUser();
      set({ user: null, isAuthenticated: false });
    }
  },
  fetchCurrentUser: async () => {
    if (!get().isAuthenticated) return;
    try {
      const data = await getCurrentUser();
      set({ user: data });
    } catch {
      return;
    }
  }
}));

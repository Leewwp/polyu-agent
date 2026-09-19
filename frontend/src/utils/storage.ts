import type { User } from "@/types";

const TOKEN_KEY = "ragent_token";
const USER_KEY = "ragent_user";
const THEME_KEY = "ragent_theme";

function safeGet(key: string) {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function safeSet(key: string, value: string) {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    return;
  }
}

function safeRemove(key: string) {
  try {
    window.localStorage.removeItem(key);
  } catch {
    return;
  }
}

export const storage = {
  // token 读写已随 cookie 化移除（评审 L35 死代码清理）：登录态由 HttpOnly Cookie 承载，
  // 遗留 localStorage token 只留 clearToken/clearAuth 做迁移清道夫
  clearToken() {
    safeRemove(TOKEN_KEY);
  },
  getUser(): User | null {
    const raw = safeGet(USER_KEY);
    if (!raw) return null;
    try {
      return JSON.parse(raw) as User;
    } catch {
      return null;
    }
  },
  setUser(user: User) {
    safeSet(USER_KEY, JSON.stringify(user));
  },
  clearUser() {
    safeRemove(USER_KEY);
  },
  clearAuth() {
    safeRemove(TOKEN_KEY);
    safeRemove(USER_KEY);
  },
  getTheme(): string | null {
    return safeGet(THEME_KEY);
  },
  setTheme(theme: string) {
    safeSet(THEME_KEY, theme);
  }
};

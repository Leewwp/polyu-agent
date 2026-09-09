import { api } from "@/services/api";
import type { CurrentUser, GuestQuotaInfo, User } from "@/types";

export interface LoginResponse extends User {}
export interface CurrentUserResponse extends CurrentUser {}

export async function login(username: string, password: string) {
  return api.post<LoginResponse, LoginResponse>("/auth/login", { username, password });
}

export async function logout() {
  return api.post<void>("/auth/logout");
}

export async function getCurrentUser() {
  return api.get<CurrentUserResponse, CurrentUserResponse>("/user/me");
}

/**
 * 游客配额状态（U11-⑤）：仅游客会话有意义；非游客/未登录会走业务错误，调用方静默忽略
 */
export async function fetchGuestQuota() {
  return api.get<GuestQuotaInfo, GuestQuotaInfo>("/auth/guest/quota");
}

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

/**
 * 自助注册与密码恢复（U2 端点 / U11-④ UI 接线）：五端点挂在
 * ragent.registration.enabled flag 后，关闭态后端统一回「注册通道当前未开放」，
 * 由页面内联展示该文案，不在前端二次判断 flag。
 */
export async function register(email: string, password: string) {
  return api.post<void, void>("/auth/register", { email, password });
}

export async function verifyEmail(email: string, code: string) {
  return api.post<void, void>("/auth/email/verify", { email, code });
}

export async function resendVerificationCode(email: string) {
  return api.post<void, void>("/auth/email/resend", { email });
}

export async function requestPasswordReset(email: string) {
  return api.post<void, void>("/auth/password/forgot", { email });
}

export async function resetPassword(email: string, code: string, newPassword: string) {
  return api.post<void, void>("/auth/password/reset", { email, code, newPassword });
}

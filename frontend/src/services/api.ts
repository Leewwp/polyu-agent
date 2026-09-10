import axios from "axios";
import { toast } from "sonner";

import { useAuthStore } from "@/stores/authStore";
import { storage } from "@/utils/storage";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || "";

export const api = axios.create({
  baseURL: API_BASE_URL,
  timeout: 60000
});

// U3 会话 cookie 化：token 由 HttpOnly Cookie 携带（同源自动附带），不再手动注入
// Authorization 头；会话过期改为清 authStore 状态——守卫组件据此重定向，
// 不再用 window.location 硬跳（会把直开的公开页 /share、/privacy 也劫持到登录页）。

function markSessionExpired() {
  storage.clearAuth();
  useAuthStore.setState({ user: null, isAuthenticated: false });
}

api.interceptors.response.use(
  (response) => {
    const payload = response.data;
    if (payload && typeof payload === "object" && "code" in payload) {
      if (payload.code !== "0") {
        const message = payload.message || "请求失败";
        const isAuthExpired = typeof message === "string" && message.includes("未登录");
        if (isAuthExpired) {
          markSessionExpired();
        }
        return Promise.reject(new Error(message));
      }
      return payload.data;
    }
    return payload;
  },
  (error) => {
    if (error?.response?.status === 401) {
      markSessionExpired();
    }
    const responseData = error?.response?.data;
    if (responseData && typeof responseData === "object" && "message" in responseData && responseData.message) {
      toast.error(responseData.message);
    } else if (error?.code === "ERR_NETWORK") {
      toast.error("网络错误，请检查网络连接");
    } else {
      toast.error(error?.message || "网络错误");
    }
    return Promise.reject(error);
  }
);

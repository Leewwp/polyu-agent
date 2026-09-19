import axios, { type InternalAxiosRequestConfig } from "axios";
import { toast } from "sonner";

import { useAuthStore } from "@/stores/authStore";
import { resetChatStoresForAccountSwitch } from "@/stores/sessionReset";
import { storage } from "@/utils/storage";
import { errorTextFor, isTransportError, markToastShown } from "@/utils/requestError";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || "";

// GET（列表/历史类请求）超时收紧 60s→15s，其余维持 60s。
// SSE 走 fetch（useStreamResponse）不经 axios，不受影响。
const GET_TIMEOUT_MS = 15000;

// 幂等 GET 网络层重试标记（挂在 config 上防无限循环）
interface RetriableConfig extends InternalAxiosRequestConfig {
  __transportRetried?: boolean;
}

export const api = axios.create({
  baseURL: API_BASE_URL,
  timeout: 60000
});

api.interceptors.request.use((config) => {
  if (config.method === "get") {
    config.timeout = GET_TIMEOUT_MS;
  }
  return config;
});

// 会话 cookie 化：token 由 HttpOnly Cookie 携带（同源自动附带），不再手动注入
// Authorization 头；会话过期改为清 authStore 状态——守卫组件据此重定向，
// 不再用 window.location 硬跳（会把直开的公开页 /share、/privacy 也劫持到登录页）。

// 会话过期的判定文案与 GlobalExceptionHandler.notLoginException 的返回值
// 同源（后端无独立结构化错误码——NotLoginException 复用 CLIENT_ERROR，只能按
// 文案识别；改彻底结构化需动上游原生处理器，已记 O10 票遗留）
const AUTH_EXPIRED_MESSAGE = "未登录或登录已过期";

function markSessionExpired() {
  storage.clearAuth();
  useAuthStore.setState({ user: null, isAuthenticated: false });
  // L36：过期即清场——断掉在途流并清空双聊天 store，过期后残留在内存里的
  // 会话内容与流态不再被下一个登录者看到（与 logout 同款语义）
  resetChatStoresForAccountSwitch({ isCreatingNew: false });
}

api.interceptors.response.use(
  (response) => {
    const payload = response.data;
    if (payload && typeof payload === "object" && "code" in payload) {
      if (payload.code !== "0") {
        const message = payload.message || "请求失败";
        // L36：过期识别收敛为与后端 NotLoginException 文案的精确匹配，
        // 不再用 includes("未登录") 宽松匹配（正文含该词的业务报错会被误判成过期）
        const isAuthExpired = message === AUTH_EXPIRED_MESSAGE;
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
    // 幂等 GET 的网络层错误（无响应：连接被掐/超时/拒连）自动重试 1 次——
    // 后端重启后陈旧 keep-alive 被掐的场景一次重试即新建连接恢复；业务错误、
    // SSE 流（不经 axios）、非幂等请求一律不重试。
    const config = error?.config as RetriableConfig | undefined;
    if (config?.method === "get" && isTransportError(error) && !config.__transportRetried) {
      config.__transportRetried = true;
      return api.request(config);
    }
    const responseData = error?.response?.data;
    if (responseData && typeof responseData === "object" && "message" in responseData && responseData.message) {
      toast.error(responseData.message);
    } else if (isTransportError(error)) {
      // 网络层错误统一中文文案兜底（axios 英文原文不再示人），并打标供调用层去重
      toast.error(errorTextFor(error, "网络异常，请稍后重试"));
    } else {
      toast.error("请求失败，请稍后重试");
    }
    markToastShown(error);
    return Promise.reject(error);
  }
);

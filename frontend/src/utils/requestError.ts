import axios from "axios";
import { toast } from "sonner";

/**
 * 请求错误的统一判定与提示语义。
 * 「网络层错误」= axios 错误且未收到任何 HTTP 响应（连接被掐 / 超时 / 拒连）——
 * 后端重启后陈旧 keep-alive 被掐即此形态；与业务错误（payload.code!==0，reject
 * 的普通 Error）严格区分：前者 GET 幂等可重试、文案由拦截器统一兜中文，后者不
 * 重试、由调用层提示。
 */

/** 是否传输级失败（无 HTTP 响应） */
export function isTransportError(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response === undefined;
}

/** 展示文案：网络层错误统一中文（axios 英文原文不再示人），其余透出 message 或兜底 */
export function errorTextFor(error: unknown, fallback: string): string {
  if (axios.isAxiosError(error) && error.response === undefined) {
    return error.code === "ECONNABORTED" ? "请求超时，请稍后重试" : "网络异常，请稍后重试";
  }
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return fallback;
}

// 拦截器已弹过 toast 的错误对象登记处：调用层凭此免重复弹（WeakSet 不拦引用回收）
const toastShownErrors = new WeakSet<object>();

export function markToastShown(error: unknown): void {
  if (error !== null && typeof error === "object") {
    toastShownErrors.add(error);
  }
}

export function wasToastShown(error: unknown): boolean {
  return error !== null && typeof error === "object" && toastShownErrors.has(error);
}

/** 调用层统一入口：拦截器已提示过的（网络层中文文案）不再重复弹，业务错误照常弹一条 */
export function toastErrorUnlessShown(error: unknown, fallback: string): void {
  if (wasToastShown(error)) {
    return;
  }
  toast.error(errorTextFor(error, fallback));
}

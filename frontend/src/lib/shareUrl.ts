import { toast } from "sonner";

/**
 * Web Share 统一行为合同（issue #139，doc44 D-7 五点）：
 * ① navigator.share supported → 调起系统分享面板；
 * ② 用户取消（AbortError）→ 静默不打扰；
 * ③ unsupported → 剪贴板 fallback + toast；
 * ④ 非 Abort 的分享失败 → 同 fallback（降级复制+toast）；
 * ⑤ 剪贴板也失败 → 明确提示（调用方给双语文案）。
 *
 * 参考系=NewsDetailPage 已验证的 Web Share 用法；本轮抽 Agent 侧轻量 helper，
 * NewsDetailPage 现状不为本 helper 让路（跨业务统一不在本轮范围）。
 */

export interface ShareUrlTexts {
  /** 剪贴板成功 toast */
  copied: string;
  /** ⑤ 剪贴板也失败的明确提示 */
  failed: string;
}

export type ShareUrlOutcome = "shared" | "cancelled" | "copied" | "failed";

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === "AbortError";
}

/** ⑤ 双保险：clipboard API 不在（非 https/旧引擎）时用遗留 execCommand 兜一次 */
async function writeToClipboard(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    try {
      const el = document.createElement("textarea");
      el.value = text;
      el.setAttribute("readonly", "");
      el.style.position = "fixed";
      el.style.opacity = "0";
      document.body.appendChild(el);
      el.select();
      const ok = document.execCommand("copy");
      document.body.removeChild(el);
      return ok;
    } catch {
      return false;
    }
  }
}

export async function shareUrl(value: { title: string; url: string; texts: ShareUrlTexts }): Promise<ShareUrlOutcome> {
  const { title, url, texts } = value;
  if (typeof navigator.share === "function") {
    try {
      await navigator.share({ title, url });
      return "shared";
    } catch (error) {
      if (isAbortError(error)) {
        return "cancelled";
      }
      // ④ 非 Abort 失败：降级复制（不 toast 原错误，按 fallback 合同给统一反馈）
    }
  }
  const ok = await writeToClipboard(url);
  if (ok) {
    toast.success(texts.copied);
    return "copied";
  }
  toast.error(texts.failed);
  return "failed";
}

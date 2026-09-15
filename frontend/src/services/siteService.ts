import axios from "axios";

import { api } from "@/services/api";

/**
 * 站点反馈与关于页服务（doc 25）。
 * 公开函数走独立匿名 axios 实例（照 newsService.ts 红线：15s 超时/信封解包/
 * 无 401 劫持——匿名访客永不被劫去登录）；admin 函数走既有 api 实例。
 */

const siteApi = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || "",
  timeout: 15000
});

siteApi.interceptors.response.use(
  (response) => {
    const payload = response.data;
    if (payload && typeof payload === "object" && "code" in payload) {
      if (payload.code !== "0") {
        return Promise.reject(new Error(payload.message || "请求失败"));
      }
      return payload.data;
    }
    return payload;
  },
  (error) => Promise.reject(error)
);

export interface SiteAboutContent {
  content: string;
  /** 英文档内容（可空；空时前台英文档回落中文） */
  contentEn?: string | null;
  qrImageUrl?: string | null;
  qrImageUrlAlt?: string | null;
  updateTime?: string | null;
}

export interface SiteFeedbackItem {
  id: number;
  content: string;
  contact?: string | null;
  clientIpMasked?: string | null;
  status: number;
  createTime?: string | null;
  updateTime?: string | null;
}

export interface SiteFeedbackPage {
  records: SiteFeedbackItem[];
  total: number;
  current: number;
  size: number;
  pages: number;
}

/** 匿名提交反馈（内容 10–2000 字，联系方式选填；同 IP 每日 ≤5 条） */
export async function submitSiteFeedback(content: string, contact?: string): Promise<void> {
  await siteApi.post("/public/feedback", { content, contact });
}

/** 关于页公开只读（flag 关时 404→调用方出空态/入口隐藏） */
export async function fetchSiteAbout(): Promise<SiteAboutContent> {
  return siteApi.get("/public/about");
}

/** admin：反馈分页（status 0/1/2 可选过滤） */
export async function getSiteFeedbackPage(
  page: number,
  size: number,
  status?: number
): Promise<SiteFeedbackPage> {
  const data = await api.get("/admin/feedback", { params: { page, size, status } });
  return (data as never) as SiteFeedbackPage;
}

/** admin：反馈状态流转 */
export async function updateSiteFeedbackStatus(id: number, status: number): Promise<void> {
  await api.put(`/admin/feedback/${id}/status`, { status });
}

/** admin：删除反馈 */
export async function deleteSiteFeedback(id: number): Promise<void> {
  await api.delete(`/admin/feedback/${id}`);
}

/** admin：关于页当前内容（编辑回显） */
export async function getSiteAboutForAdmin(): Promise<SiteAboutContent> {
  const data = await api.get("/admin/about");
  return (data as never) as SiteAboutContent;
}

/** admin：保存关于页（upsert：双语内容+两码 URL 整段覆盖） */
export async function saveSiteAbout(payload: {
  content: string;
  contentEn?: string | null;
  qrImageUrl?: string | null;
  qrImageUrlAlt?: string | null;
}): Promise<void> {
  await api.put("/admin/about", payload);
}

/** admin：上传赞赏二维码（png/jpg/webp ≤2MB）→ 资产桶 URL */
export async function uploadSiteQr(file: File): Promise<string> {
  const form = new FormData();
  form.append("file", file);
  const data = await api.post("/admin/about/qr", form, {
    headers: { "Content-Type": "multipart/form-data" }
  });
  return ((data as never) as { url: string }).url;
}

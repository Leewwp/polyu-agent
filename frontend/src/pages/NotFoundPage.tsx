import { Link } from "react-router-dom";

import { Button } from "@/components/ui/button";
import { SiteFooter } from "@/components/layout/SiteFooter";
import { useOptionalFeedLang } from "@/components/feed/feedLang";
import { NOINDEX_ATTRS, useHeadElement } from "@/hooks/useHeadElement";
import { usePageTitle } from "@/hooks/usePageTitle";

/**
 * 404 页（#227 消费面）：主操作与页脚随全局语言单语呈现（正文提示为尽力项一并跟随）。
 * #231：补页面标题「页面不存在 · PolyUGuide」与 noindex meta（N6——SPA 软 404
 * 对搜索引擎是 200 带错误文案；离开 404 后 meta 即移除，head 注入走可复用
 * useHeadElement helper，#243 订阅出口 autodiscovery 复用同款）。
 * #229：主操作「返回聊天」→「回到首页」——外链 404 访客的合理落点是主站资讯流
 * 而非受守卫的 /chat；轻量布局与页脚保持。
 */
export function NotFoundPage() {
  const { lang } = useOptionalFeedLang();
  const zh = lang === "zh";
  usePageTitle({ zh: "页面不存在", en: "Page not found" });
  useHeadElement("meta", NOINDEX_ATTRS);
  return (
    <div className="flex min-h-screen flex-col px-4">
      <div className="flex flex-1 items-center justify-center">
        <div className="chat-surface max-w-md rounded-3xl p-8 text-center">
          <p className="font-display text-2xl font-semibold">{zh ? "页面不存在" : "Page not found"}</p>
          <p className="mt-2 text-sm text-muted-foreground">
            {zh ? "你访问的页面不存在。" : "The page you are looking for does not exist."}
          </p>
          <Button asChild className="mt-6" data-testid="notfound-go-home">
            <Link to="/">{zh ? "回到首页" : "Back to home"}</Link>
          </Button>
        </div>
      </div>
      <SiteFooter className="mt-2" />
    </div>
  );
}

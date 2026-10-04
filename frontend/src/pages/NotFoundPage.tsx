import { Link } from "react-router-dom";

import { Button } from "@/components/ui/button";
import { SiteFooter } from "@/components/layout/SiteFooter";
import { useOptionalFeedLang } from "@/components/feed/feedLang";

/** 404 页（#227 消费面）：主操作与页脚随全局语言单语呈现（正文提示为尽力项一并跟随）。 */
export function NotFoundPage() {
  const { lang } = useOptionalFeedLang();
  const zh = lang === "zh";
  return (
    <div className="flex min-h-screen flex-col px-4">
      <div className="flex flex-1 items-center justify-center">
        <div className="chat-surface max-w-md rounded-3xl p-8 text-center">
          <p className="font-display text-2xl font-semibold">{zh ? "页面不存在" : "Page not found"}</p>
          <p className="mt-2 text-sm text-muted-foreground">
            {zh ? "你访问的页面不存在。" : "The page you are looking for does not exist."}
          </p>
          <Button asChild className="mt-6">
            <Link to="/chat">{zh ? "返回聊天" : "Back to chat"}</Link>
          </Button>
        </div>
      </div>
      <SiteFooter className="mt-2" />
    </div>
  );
}

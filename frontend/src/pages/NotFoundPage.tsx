import { Link } from "react-router-dom";

import { Button } from "@/components/ui/button";
import { SiteFooter } from "@/components/layout/SiteFooter";

export function NotFoundPage() {
  return (
    <div className="flex min-h-screen flex-col px-4">
      <div className="flex flex-1 items-center justify-center">
        <div className="chat-surface max-w-md rounded-3xl p-8 text-center">
          <p className="font-display text-2xl font-semibold">页面不存在</p>
          <p className="mt-2 text-sm text-muted-foreground">你访问的页面不存在。</p>
          <Button asChild className="mt-6">
            <Link to="/chat">返回聊天</Link>
          </Button>
        </div>
      </div>
      <SiteFooter className="mt-2" />
    </div>
  );
}

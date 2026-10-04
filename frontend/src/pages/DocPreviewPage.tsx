import * as React from "react";
import { useParams } from "react-router-dom";
import { FileText, Loader2 } from "lucide-react";

import { DocumentPreview } from "@/components/document/DocumentPreview";
import { getDocument } from "@/services/knowledgeService";
import { useAdminUiTheme } from "@/hooks/useAdminUiTheme";
import { useDetailPageTitle, usePageTitle } from "@/hooks/usePageTitle";

type DocMeta = Awaited<ReturnType<typeof getDocument>>;

export function DocPreviewPage() {
  const { docId } = useParams<{ docId: string }>();
  // #228：独立后台路由、根节点无后台布局类——显式接入后台主题（body 标记覆盖
  // 本页 DOM 与经 Portal 挂 body 的弹层，不依赖「后台页都在 AdminLayout 根下」）
  useAdminUiTheme();
  const [doc, setDoc] = React.useState<DocMeta | null>(null);
  const [status, setStatus] = React.useState<"loading" | "done" | "error">("loading");

  // #231 标题单源：迁出直写 document.title 旧法——加载/失败回落稳定页名
  // 「文档预览 · PolyUGuide」，加载成功以文档名作优先值覆写，卸载回落
  // （原直写法离开预览后残留旧标题，本轮点名收敛）
  usePageTitle({ zh: "文档预览", en: "Document preview" });
  useDetailPageTitle(status === "done" && doc ? doc.docName || null : null);

  React.useEffect(() => {
    if (!docId) {
      setStatus("error");
      return;
    }
    let cancelled = false;
    setStatus("loading");
    getDocument(docId)
      .then((data) => {
        if (cancelled) return;
        setDoc(data);
        setStatus("done");
      })
      .catch(() => {
        if (!cancelled) setStatus("error");
      });
    return () => {
      cancelled = true;
    };
  }, [docId]);

  return (
    <div className="flex h-screen flex-col bg-white">
      <header className="flex shrink-0 items-center gap-2 border-b border-[#EFEFEF] px-6 py-3.5">
        <FileText className="h-5 w-5 shrink-0 text-[#666666]" />
        <h1 className="truncate text-base font-medium text-[#1A1A1A]" title={doc?.docName || ""}>
          {doc?.docName || "文档预览"}
        </h1>
      </header>
      <div className="flex flex-1 flex-col overflow-hidden">
        {status === "loading" ? (
          <div className="flex flex-1 items-center justify-center gap-2 text-sm text-[#999999]">
            <Loader2 className="h-4 w-4 animate-spin" />
            正在加载…
          </div>
        ) : status === "error" || !doc || !docId ? (
          <div className="flex flex-1 items-center justify-center text-sm text-[#999999]">
            无法加载该文档，可能已被删除。
          </div>
        ) : (
          <DocumentPreview docId={docId} fileType={doc.fileType} docName={doc.docName} />
        )}
      </div>
    </div>
  );
}

import { useEffect, useRef, useState } from "react";
import { toast } from "sonner";

import { AgentMarkdownRenderer } from "@/components/agent/AgentMarkdownRenderer";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { getSiteAboutForAdmin, saveSiteAbout, uploadSiteQr } from "@/services/siteService";
import { getErrorMessage } from "@/utils/error";

const QR_MAX_BYTES = 2 * 1024 * 1024;
const QR_ALLOWED_TYPES = ["image/png", "image/jpeg", "image/webp"];

/**
 * 关于页后台编辑（doc 25 D3：markdown 文本域+实时预览双栏，不引富文本；
 * D4：二维码后台上传替换）。两码 URL 皆空时前台赞赏区整区隐藏。
 */
export function AboutAdminPage() {
  const [content, setContent] = useState("");
  const [qrMain, setQrMain] = useState("");
  const [qrAlt, setQrAlt] = useState("");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [uploading, setUploading] = useState<"main" | "alt" | null>(null);
  const mainInputRef = useRef<HTMLInputElement>(null);
  const altInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let active = true;
    getSiteAboutForAdmin()
      .then((data) => {
        if (!active) return;
        setContent(data.content ?? "");
        setQrMain(data.qrImageUrl ?? "");
        setQrAlt(data.qrImageUrlAlt ?? "");
      })
      .catch((error) => toast.error(getErrorMessage(error, "加载关于页内容失败")))
      .finally(() => active && setLoading(false));
    return () => {
      active = false;
    };
  }, []);

  const upload = async (slot: "main" | "alt", file: File | undefined) => {
    if (!file) return;
    if (!QR_ALLOWED_TYPES.includes(file.type)) {
      toast.error("仅支持 png / jpg / webp 图片");
      return;
    }
    if (file.size > QR_MAX_BYTES) {
      toast.error("二维码图片不能超过 2MB");
      return;
    }
    try {
      setUploading(slot);
      const url = await uploadSiteQr(file);
      if (slot === "main") setQrMain(url);
      else setQrAlt(url);
      toast.success("已上传，保存后生效");
    } catch (error) {
      toast.error(getErrorMessage(error, "上传失败"));
    } finally {
      setUploading(null);
    }
  };

  const handleSave = async () => {
    if (!content.trim()) {
      toast.error("关于页内容不能为空");
      return;
    }
    try {
      setSaving(true);
      await saveSiteAbout({
        content,
        qrImageUrl: qrMain.trim() || null,
        qrImageUrlAlt: qrAlt.trim() || null
      });
      toast.success("已保存，前台即时生效");
    } catch (error) {
      toast.error(getErrorMessage(error, "保存失败"));
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return <div className="py-16 text-center text-sm text-muted-foreground">加载中…</div>;
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold">关于页</h1>
          <p className="text-sm text-muted-foreground">
            markdown 编辑 · 左写右预览 · 保存后前台即时生效
          </p>
        </div>
        <Button onClick={handleSave} disabled={saving}>
          {saving ? "保存中…" : "保存"}
        </Button>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardContent className="p-4">
            <div className="mb-2 text-sm font-medium">编辑</div>
            <textarea
              className="h-[420px] w-full resize-none rounded-md border border-input bg-background p-3 font-mono text-sm outline-none focus:ring-1 focus:ring-ring"
              value={content}
              onChange={(event) => setContent(event.target.value)}
              placeholder="markdown 内容…"
              spellCheck={false}
              aria-label="关于页 markdown 内容"
            />
          </CardContent>
        </Card>
        <Card>
          <CardContent className="p-4">
            <div className="mb-2 text-sm font-medium">预览</div>
            <div className="h-[420px] overflow-y-auto rounded-md border border-input p-3 text-sm">
              {content.trim() ? (
                <AgentMarkdownRenderer content={content} />
              ) : (
                <span className="text-muted-foreground">暂无内容</span>
              )}
            </div>
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardContent className="p-4">
          <div className="mb-1 text-sm font-medium">赞赏二维码（可选）</div>
          <p className="mb-3 text-xs text-muted-foreground">
            两张都为空时，前台赞赏区（含标题）整块不渲染；png / jpg / webp ≤2MB。
          </p>
          <div className="flex flex-wrap gap-6">
            {(
              [
                { slot: "main", label: "二维码一", url: qrMain, setUrl: setQrMain, ref: mainInputRef },
                { slot: "alt", label: "二维码二", url: qrAlt, setUrl: setQrAlt, ref: altInputRef }
              ] as const
            ).map(({ slot, label, url, setUrl, ref }) => (
              <div key={slot} className="flex flex-col items-center gap-2">
                <div className="flex h-32 w-32 items-center justify-center rounded-lg border border-dashed border-input bg-muted/30">
                  {url ? (
                    <img src={url} alt={label} className="h-full w-full rounded-lg object-contain" />
                  ) : (
                    <span className="text-xs text-muted-foreground">未设置</span>
                  )}
                </div>
                <div className="text-xs text-muted-foreground">{label}</div>
                <input
                  ref={ref}
                  type="file"
                  accept="image/png,image/jpeg,image/webp"
                  className="hidden"
                  onChange={(event) => upload(slot, event.target.files?.[0])}
                />
                <div className="flex gap-2">
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={uploading === slot}
                    onClick={() => ref.current?.click()}
                  >
                    {uploading === slot ? "上传中…" : url ? "替换" : "上传"}
                  </Button>
                  {url ? (
                    <Button variant="ghost" size="sm" onClick={() => setUrl("")}>
                      移除
                    </Button>
                  ) : null}
                </div>
              </div>
            ))}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

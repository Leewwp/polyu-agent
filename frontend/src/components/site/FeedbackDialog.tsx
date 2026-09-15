import { useState } from "react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { useOptionalFeedLang } from "@/components/feed/feedLang";
import { submitSiteFeedback } from "@/services/siteService";

const CONTENT_MIN = 10;
const CONTENT_MAX = 2000;
const CONTACT_MAX = 100;

/**
 * 站点反馈弹窗（doc 25 D2）：内容必填（10–2000 字）+ 联系方式选填（≤100 字）。
 * 自包含（直连 siteService 匿名实例），三入口共用：FeedFooter/SiteFooter/MobileTabbar
 * 更多抽屉/关于页；不设独立 /feedback 路由。
 * 语言用 optional 版回落 zh——挂载面含法务页壳（LegalShell）等 FeedShell 之外的场景。
 */
export function FeedbackDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { lang } = useOptionalFeedLang();
  const zh = lang === "zh";
  const [content, setContent] = useState("");
  const [contact, setContact] = useState("");
  const [submitting, setSubmitting] = useState(false);

  const trimmedContent = content.trim();
  const contentInvalid =
    trimmedContent.length > 0 && (trimmedContent.length < CONTENT_MIN || trimmedContent.length > CONTENT_MAX);

  const reset = () => {
    setContent("");
    setContact("");
  };

  const handleSubmit = async () => {
    if (!trimmedContent) {
      toast.error(zh ? "请填写反馈内容" : "Please enter your feedback");
      return;
    }
    if (trimmedContent.length < CONTENT_MIN || trimmedContent.length > CONTENT_MAX) {
      toast.error(zh ? "反馈内容须为 10–2000 字" : "Feedback must be 10–2000 characters");
      return;
    }
    if (contact.trim().length > CONTACT_MAX) {
      toast.error(zh ? "联系方式不能超过 100 字" : "Contact must be within 100 characters");
      return;
    }
    try {
      setSubmitting(true);
      await submitSiteFeedback(trimmedContent, contact.trim() || undefined);
      toast.success(zh ? "已收到你的反馈，感谢！" : "Feedback received. Thank you!");
      reset();
      onClose();
    } catch (error) {
      toast.error((error as Error)?.message || (zh ? "提交失败，请稍后再试" : "Submit failed"));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) onClose();
      }}
    >
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{zh ? "站点反馈" : "Site feedback"}</DialogTitle>
          <DialogDescription>
            {zh
              ? "想法、问题或 bug 都欢迎——无需登录，直接提交。"
              : "Ideas, issues or bugs are all welcome — no login required."}
          </DialogDescription>
        </DialogHeader>
        <div className="flex flex-col gap-3">
          <div>
            <Textarea
              value={content}
              onChange={(event) => setContent(event.target.value)}
              placeholder={zh ? "说点什么…（10–2000 字）" : "Tell us… (10–2000 characters)"}
              rows={5}
              aria-label={zh ? "反馈内容" : "Feedback content"}
            />
            <div className={"mt-1 text-right text-xs " + (contentInvalid ? "text-rose-600" : "text-muted-foreground")}>
              {trimmedContent.length}/{CONTENT_MAX}
            </div>
          </div>
          <Input
            value={contact}
            onChange={(event) => setContact(event.target.value)}
            placeholder={zh ? "联系方式（选填，方便追问）" : "Contact (optional)"}
            aria-label={zh ? "联系方式" : "Contact"}
          />
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            {zh ? "取消" : "Cancel"}
          </Button>
          <Button onClick={handleSubmit} disabled={submitting}>
            {submitting ? (zh ? "提交中…" : "Submitting…") : zh ? "提交" : "Submit"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

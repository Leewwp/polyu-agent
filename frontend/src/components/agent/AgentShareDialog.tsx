import * as React from "react";
import { format } from "date-fns";
import { Link } from "react-router-dom";
import { Check, Copy, Loader2, Share2 } from "lucide-react";
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
import { useOptionalFeedLang } from "@/components/feed/feedLang";
import { groupTurns } from "@/components/agent/AgentMessageList";
import { listShareableTurns } from "@/lib/agentShareAnchor";
import { shareUrl } from "@/lib/shareUrl";
import { createAgentShare, type AgentShareCreated, type AgentShareScope } from "@/services/agentShareService";
import { awaitingConfirm, useAgentChatStore } from "@/stores/agentChatStore";
import { useAuthStore } from "@/stores/authStore";
import type { AgentTurn } from "@/types/agent";

/**
 * Agent 分享弹窗（issue #139，消费 #138 已部署的 Scoped Share 合同）：
 * - 入口两门：顶栏（默认 full，可切三档；turn/through 须先单选锚点轮）与
 *   答案 Turn footer（自带 anchor，默认 turn）——门态在 agentChatStore.shareDialog；
 * - 创建前=范围选择+轻量预览（groupTurns 公开投影，不渲染完整对话、不展示
 *   reasoning/工具 metadata）+隐私要点（不写死有效期数字）；
 * - 成功态不自动关、不自动复制：URL 展示+[复制分享链接]+[分享给…]（shareUrl
 *   五点合同）+服务端 expireTime（null=不会自动过期）+撤销说明；
 * - 游客=登录引导（不调创建端点）；双形态=桌面居中 modal/≤860 底部 sheet
 *   （Tailwind 变体类+safe-area，ESC/焦点陷阱走 Radix）。
 */

const SCOPE_OPTIONS: Array<{
  value: AgentShareScope;
  zh: string;
  en: string;
  descZh: string;
  descEn: string;
}> = [
  { value: "full", zh: "完整对话", en: "Full conversation", descZh: "创建时点前的全部问答轮次", descEn: "Every turn up to the moment of sharing" },
  { value: "turn", zh: "当前问答", en: "This turn", descZh: "所选回答所在的这一轮问答", descEn: "Just the turn the selected answer belongs to" },
  { value: "through", zh: "直至此轮", en: "Through this turn", descZh: "从第一轮到所选轮（含）", descEn: "From the first turn through the selected one" }
];

/** 单行短预览：截断到 max 字符 */
function shortText(text: string, max: number): string {
  const flat = text.replace(/\s+/g, " ").trim();
  return flat.length > max ? `${flat.slice(0, max)}…` : flat;
}

/** 预览/单选共用的公开投影行（不展示 reasoning/工具 metadata） */
function projectTurn(turn: AgentTurn) {
  return {
    index: turn.index,
    question: turn.user?.content ?? "",
    answer: shortText(turn.assistants.map((a) => a.content).filter(Boolean).join(" "), 80)
  };
}

function countSources(turns: AgentTurn[]): number {
  const docIds = new Set<string>();
  for (const turn of turns) {
    for (const assistant of turn.assistants) {
      for (const block of assistant.blocks ?? []) {
        for (const source of block.sources ?? []) {
          if (source.docId) {
            docIds.add(source.docId);
          }
        }
      }
    }
  }
  return docIds.size;
}

function expireLabel(expireTime: string | null | undefined, zh: boolean): string {
  if (!expireTime) {
    return zh ? "此链接不会自动过期" : "This link never expires";
  }
  const date = new Date(expireTime);
  if (Number.isNaN(date.getTime())) {
    return zh ? "此链接不会自动过期" : "This link never expires";
  }
  return zh
    ? `有效期至 ${format(date, "yyyy-MM-dd HH:mm")}`
    : `Valid until ${format(date, "yyyy-MM-dd HH:mm")}`;
}

/** 游客门：只出登录引导（治理说明+登录/取消），绝不调创建端点 */
function GuestGate({ zh, onClose }: { zh: boolean; onClose: () => void }) {
  return (
    <>
      <DialogHeader>
        <DialogTitle>{zh ? "登录后分享对话" : "Sign in to share"}</DialogTitle>
        <DialogDescription className="pt-1 text-left leading-relaxed">
          {zh
            ? "分享链接需要登录后才能创建、查看和撤销——登录可以防止产生无人可管理的公开内容。"
            : "Shares are tied to your account so they can be managed and revoked — sign in to create one."}
        </DialogDescription>
      </DialogHeader>
      <DialogFooter>
        <Button variant="outline" size="sm" onClick={onClose}>
          {zh ? "取消" : "Cancel"}
        </Button>
        <Button size="sm" asChild>
          <Link to="/login" onClick={onClose}>
            {zh ? "登录" : "Sign in"}
          </Link>
        </Button>
      </DialogFooter>
    </>
  );
}

function ShareForm({
  defaultScope,
  entryAnchorId
}: {
  defaultScope: AgentShareScope;
  entryAnchorId?: string;
}) {
  const { lang } = useOptionalFeedLang();
  const zh = lang === "zh";
  const currentSessionId = useAgentChatStore((state) => state.currentSessionId);
  const messages = useAgentChatStore((state) => state.messages);
  const closeShareDialog = useAgentChatStore((state) => state.closeShareDialog);

  const [scope, setScope] = React.useState<AgentShareScope>(defaultScope);
  const [selectedAnchorId, setSelectedAnchorId] = React.useState<string | null>(null);
  const [creating, setCreating] = React.useState(false);
  const [created, setCreated] = React.useState<AgentShareCreated | null>(null);

  const turns = React.useMemo(() => groupTurns(messages), [messages]);
  const shareable = React.useMemo(() => listShareableTurns(turns), [turns]);
  // 答案入口自带 anchor（不出现单选）；顶栏 turn/through 须单选
  const effectiveAnchorId: string | undefined = entryAnchorId ?? selectedAnchorId ?? undefined;
  const anchorTurnIndex = turns.findIndex((turn) =>
    turn.assistants.some((assistant) => assistant.id === effectiveAnchorId)
  );

  const previewTurns = React.useMemo(() => {
    if (scope === "full") {
      return turns;
    }
    if (anchorTurnIndex < 0) {
      return [];
    }
    return scope === "turn" ? [turns[anchorTurnIndex]] : turns.slice(0, anchorTurnIndex + 1);
  }, [scope, turns, anchorTurnIndex]);

  const sourceCount = React.useMemo(() => countSources(previewTurns), [previewTurns]);
  const needsAnchor = scope !== "full" && !entryAnchorId;
  const createDisabled =
    creating ||
    !currentSessionId ||
    (scope !== "full" && (!effectiveAnchorId || anchorTurnIndex < 0));

  const handleCreate = async () => {
    if (createDisabled) return;
    setCreating(true);
    try {
      const result = await createAgentShare(
        currentSessionId,
        scope,
        scope === "full" ? undefined : effectiveAnchorId
      );
      // 成功态不自动关、不自动复制（留在弹窗展示链接与有效期）
      setCreated(result);
    } catch (error) {
      const message = error instanceof Error ? error.message : "";
      if (/404|Cannot|Not Found|Failed to fetch/i.test(message) || !message) {
        toast.error(zh ? "分享功能未开启" : "Sharing is not enabled");
      } else {
        toast.error(message);
      }
    } finally {
      setCreating(false);
    }
  };

  const handleCopyLink = async () => {
    if (!created) return;
    try {
      await navigator.clipboard.writeText(`${window.location.origin}/share/c/${created.token}`);
      toast.success(zh ? "分享链接已复制" : "Share link copied");
    } catch {
      toast.error(zh ? "复制失败，请手动复制" : "Copy failed — please copy manually");
    }
  };

  const handleShareTo = async () => {
    if (!created) return;
    await shareUrl({
      title: zh ? "PolyUGuide 对话分享" : "PolyUGuide conversation",
      url: `${window.location.origin}/share/c/${created.token}`,
      texts: {
        copied: zh ? "分享链接已复制" : "Share link copied",
        failed: zh ? "复制失败，请手动复制弹窗中的链接" : "Copy failed — please copy the link manually"
      }
    });
  };

  if (created) {
    return (
      <>
        <DialogHeader>
          <DialogTitle>
            <span className="inline-flex items-center gap-2">
              <span className="inline-flex h-5 w-5 items-center justify-center rounded-full bg-[var(--polyu-red)] text-white">
                <Check className="h-3 w-3" strokeWidth={3} />
              </span>
              {zh ? "分享链接已创建" : "Share link created"}
            </span>
          </DialogTitle>
          <DialogDescription className="text-left leading-relaxed">
            {zh
              ? "链接内容是创建时的快照，后续对话变化不会同步；可随时在「账号设置 → 我的分享」撤销。"
              : "The link shows a snapshot taken at creation — later messages are not included. You can revoke it anytime under Account → My shares."}
          </DialogDescription>
        </DialogHeader>
        <div className="agent-share-url" data-testid="agent-share-url">
          {window.location.origin}/share/c/{created.token}
        </div>
        <p className="text-[12.5px] font-semibold text-[var(--feed-text-secondary)]">
          {expireLabel(created.expireTime, zh)}
        </p>
        <DialogFooter>
          <Button variant="outline" size="sm" onClick={() => void handleCopyLink()}>
            <Copy className="mr-1 h-3.5 w-3.5" />
            {zh ? "复制分享链接" : "Copy link"}
          </Button>
          <Button size="sm" onClick={() => void handleShareTo()}>
            <Share2 className="mr-1 h-3.5 w-3.5" />
            {zh ? "分享给…" : "Share…"}
          </Button>
        </DialogFooter>
      </>
    );
  }

  return (
    <>
      <DialogHeader>
        <DialogTitle>{zh ? "分享对话" : "Share conversation"}</DialogTitle>
        <DialogDescription className="text-left leading-relaxed">
          {zh
            ? "创建公开只读链接前先确认范围与内容；以创建时的快照为准。"
            : "Pick a scope and check the preview — what you share is a snapshot taken at creation."}
        </DialogDescription>
      </DialogHeader>

      <div className="agent-share-body" role="radiogroup" aria-label={zh ? "分享范围" : "Share scope"}>
        {SCOPE_OPTIONS.map((option) => {
          const active = scope === option.value;
          return (
            <label
              key={option.value}
              className="agent-share-scope"
              data-active={active ? "true" : undefined}
            >
              <input
                type="radio"
                name="agent-share-scope"
                value={option.value}
                checked={active}
                onChange={() => setScope(option.value)}
              />
              <span className="font-semibold">{zh ? option.zh : option.en}</span>
              <span className="text-[12px] text-[var(--feed-text-tertiary)]">
                {zh ? option.descZh : option.descEn}
              </span>
            </label>
          );
        })}
      </div>

      {needsAnchor ? (
        <fieldset className="agent-share-pick">
          <legend className="mb-1.5 text-[13px] font-semibold">
            {zh ? "选择一轮对话" : "Pick a turn"}
          </legend>
          <div className="agent-share-pick-list" role="radiogroup" aria-label={zh ? "锚点轮" : "Anchor turn"}>
            {shareable.length === 0 ? (
              <p className="text-[12.5px] text-[var(--feed-text-tertiary)]">
                {zh ? "还没有可分享的完成回答。" : "No completed answers to share yet."}
              </p>
            ) : (
              shareable.map(({ turn, anchor }) => {
                const projection = projectTurn(turn);
                return (
                  <label key={anchor.id} className="agent-share-pick-item">
                    <input
                      type="radio"
                      name="agent-share-anchor"
                      value={anchor.id}
                      checked={selectedAnchorId === anchor.id}
                      onChange={() => setSelectedAnchorId(anchor.id)}
                    />
                    <span className="min-w-0">
                      <span className="block truncate text-[12.5px] font-semibold">
                        TURN {projection.index} · {shortText(projection.question, 40)}
                      </span>
                      <span className="block truncate text-[12px] text-[var(--feed-text-tertiary)]">
                        {projection.answer || (zh ? "（无回答正文）" : "(no answer text)")}
                      </span>
                    </span>
                  </label>
                );
              })
            )}
          </div>
        </fieldset>
      ) : null}

      {previewTurns.length > 0 ? (
        <div className="agent-share-preview">
          <p className="text-[13px] font-semibold">
            {zh ? `将分享 ${previewTurns.length} 轮对话` : `${previewTurns.length} turn(s) will be shared`}
            {sourceCount > 0 ? (
              <span className="ml-1.5 font-normal text-[var(--feed-text-tertiary)]">
                {zh ? `· 含 ${sourceCount} 个来源` : `· ${sourceCount} source(s)`}
              </span>
            ) : null}
          </p>
          <div className="agent-share-preview-list">
            {previewTurns.map((turn) => {
              const projection = projectTurn(turn);
              return (
                <div key={turn.id} className="agent-share-preview-item">
                  <p className="truncate text-[12.5px] font-semibold">
                    TURN {projection.index} · {shortText(projection.question, 46)}
                  </p>
                  <p className="truncate text-[12px] text-[var(--feed-text-tertiary)]">
                    {projection.answer || (zh ? "（无回答正文）" : "(no answer text)")}
                  </p>
                </div>
              );
            })}
          </div>
        </div>
      ) : null}

      <ul className="agent-share-privacy">
        {(zh
          ? [
              "任何获得链接的人都可以查看快照内容",
              "分享是创建时的快照，后续对话变化不会同步",
              "可在「账号设置 → 我的分享」随时撤销",
              "请确认内容不含敏感个人信息"
            ]
          : [
              "Anyone with the link can view the snapshot",
              "The share is a snapshot at creation — later messages are not included",
              "Revoke anytime under Account → My shares",
              "Make sure it contains no sensitive personal information"
            ]
        ).map((line) => (
          <li key={line}>{line}</li>
        ))}
      </ul>

      <DialogFooter>
        <Button variant="outline" size="sm" onClick={closeShareDialog} disabled={creating}>
          {zh ? "取消" : "Cancel"}
        </Button>
        <Button size="sm" onClick={() => void handleCreate()} disabled={createDisabled}>
          {creating ? (
            <>
              <Loader2 className="mr-1 h-3.5 w-3.5 motion-safe:animate-spin" />
              {zh ? "创建中…" : "Creating…"}
            </>
          ) : (
            zh ? "创建分享链接" : "Create share link"
          )}
        </Button>
      </DialogFooter>
    </>
  );
}

export function AgentShareDialog() {
  const { lang } = useOptionalFeedLang();
  const zh = lang === "zh";
  const shareDialog = useAgentChatStore((state) => state.shareDialog);
  const closeShareDialog = useAgentChatStore((state) => state.closeShareDialog);
  const isLoading = useAgentChatStore((state) => state.isLoading);
  const isStreaming = useAgentChatStore((state) => state.isStreaming);
  const messages = useAgentChatStore((state) => state.messages);
  const user = useAuthStore((state) => state.user);
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  // 与 UserMenu 同口径：未登录/游客 → 登录引导（后端 guest 硬阻断为双保险）
  const guest = !isAuthenticated || user?.role === "guest";
  const unstable = isStreaming || awaitingConfirm(messages);

  return (
    <Dialog
      open={Boolean(shareDialog)}
      onOpenChange={(open) => {
        if (!open) {
          closeShareDialog();
        }
      }}
    >
      <DialogContent className="agent-share-dialog max-w-[480px] gap-3 p-5 max-[860px]:left-0 max-[860px]:right-0 max-[860px]:top-auto max-[860px]:bottom-0 max-[860px]:max-w-full max-[860px]:translate-x-0 max-[860px]:translate-y-0 max-[860px]:rounded-b-none max-[860px]:rounded-t-[18px] max-[860px]:max-h-[80vh] max-[860px]:pb-[max(20px,env(safe-area-inset-bottom))] sm:max-w-[480px]">
        {guest ? (
          <GuestGate zh={zh} onClose={closeShareDialog} />
        ) : isLoading ? (
          // 消息加载门（入口已拦，这里兜底）：不渲染表单更不创建
          <>
            <DialogHeader>
              <DialogTitle>{zh ? "分享对话" : "Share conversation"}</DialogTitle>
              <DialogDescription className="text-left">
                {zh ? "正在加载对话，请稍候…" : "Conversation is still loading — try again shortly."}
              </DialogDescription>
            </DialogHeader>
          </>
        ) : unstable ? (
          // 不稳定态门（入口 toast 已拦，这里兜底不创建）
          <>
            <DialogHeader>
              <DialogTitle>{zh ? "分享对话" : "Share conversation"}</DialogTitle>
              <DialogDescription className="text-left">
                {zh ? "回答完成后即可分享。" : "You can share once the answer completes."}
              </DialogDescription>
            </DialogHeader>
          </>
        ) : shareDialog ? (
          <ShareForm
            key={`${shareDialog.defaultScope}:${shareDialog.anchorAssistantMessageId ?? ""}`}
            defaultScope={shareDialog.defaultScope}
            entryAnchorId={shareDialog.anchorAssistantMessageId}
          />
        ) : null}
      </DialogContent>
    </Dialog>
  );
}

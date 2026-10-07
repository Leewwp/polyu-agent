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
import { findShareableAnchor, listShareableTurns } from "@/lib/agentShareAnchor";
import { shareUrl } from "@/lib/shareUrl";
import { createAgentShare, type AgentShareCreated, type AgentShareScope } from "@/services/agentShareService";
import { awaitingConfirm, useAgentChatStore } from "@/stores/agentChatStore";
import { useAuthStore } from "@/stores/authStore";
import type { AgentTurn } from "@/types/agent";

/**
 * Agent 分享弹窗（issue #139 起，#311 三档收敛两档）：
 * - 入口两门：顶栏（默认 full）与答案 Turn footer（默认 selection+预勾所点轮）——
 *   门态在 agentChatStore.shareDialog；两档=完整对话 / 选择问答（checkbox 多选，
 *   可跨轮不连续；「当前问答/直至此轮」下线，前缀分享由 N 次勾选表达）；
 * - 创建前=范围选择+轻量预览（groupTurns 公开投影，selection=勾选轮按会话时间
 *   正序的并集；不渲染完整对话、不展示 reasoning/工具 metadata）+隐私要点
 *   （不写死有效期数字）；
 * - 成功态不自动关、不自动复制：URL 展示+[复制分享链接]+[分享给…]（shareUrl
 *   五点合同）+服务端 expireTime（null=不会自动过期）+撤销说明；
 * - 游客=登录引导（不调创建端点）；双形态=桌面居中 modal/≤860 底部 sheet
 *   （容器几何在 globals.css 的 .agent-share-dialog——custom 类无 layer 恒压
 *   Tailwind 变体，max-height/overflow 防内容出卡片，ESC/焦点陷阱走 Radix）。
 */

const SCOPE_OPTIONS: Array<{
  value: AgentShareScope;
  zh: string;
  en: string;
  descZh: string;
  descEn: string;
}> = [
  { value: "full", zh: "完整对话", en: "Full conversation", descZh: "创建时点前的全部问答轮次", descEn: "Every turn up to the moment of sharing" },
  { value: "selection", zh: "选择问答", en: "Select turns", descZh: "勾选一轮或多轮问答", descEn: "Pick one or more turns to share" }
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
  // #311 多选：底部入口锚点作预勾种子（可取消可加选）；顶栏空勾选起步、创建禁用直至 ≥1
  const [selectedAnchorIds, setSelectedAnchorIds] = React.useState<ReadonlySet<string>>(
    () => (entryAnchorId ? new Set([entryAnchorId]) : new Set<string>())
  );
  const [creating, setCreating] = React.useState(false);
  const [created, setCreated] = React.useState<AgentShareCreated | null>(null);

  const turns = React.useMemo(() => groupTurns(messages), [messages]);
  const shareable = React.useMemo(() => listShareableTurns(turns), [turns]);

  const toggleAnchor = (id: string) => {
    setSelectedAnchorIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  };

  // 勾选轮按会话时间正序的并集（turns 本身按物理顺序，filter 保序——勾选顺序仅是 UI 态）
  const selectedTurns = React.useMemo(
    () =>
      turns.filter((turn) => {
        const anchor = findShareableAnchor(turn);
        return anchor !== null && selectedAnchorIds.has(anchor.id);
      }),
    [turns, selectedAnchorIds]
  );

  const previewTurns = React.useMemo(
    () => (scope === "full" ? turns : selectedTurns),
    [scope, turns, selectedTurns]
  );

  const sourceCount = React.useMemo(() => countSources(previewTurns), [previewTurns]);
  // 悬空锚点防线：勾选集合含当前消息里解析不到的锚点（如入口种子已过期）时拒绝提交
  const allSelectedResolvable = React.useMemo(() => {
    const shareableIds = new Set(shareable.map(({ anchor }) => anchor.id));
    for (const id of selectedAnchorIds) {
      if (!shareableIds.has(id)) {
        return false;
      }
    }
    return true;
  }, [shareable, selectedAnchorIds]);
  const createDisabled =
    creating ||
    !currentSessionId ||
    (scope === "selection" && (selectedAnchorIds.size === 0 || !allSelectedResolvable));

  const handleCreate = async () => {
    if (createDisabled) return;
    setCreating(true);
    try {
      // 锚点列表=勾选轮按会话时间正序（后端对乱序宽容归一，前端以正序发出与预览一致）
      const anchorIds = selectedTurns
        .map((turn) => findShareableAnchor(turn)?.id)
        .filter((id): id is string => typeof id === "string");
      const result = await createAgentShare(
        currentSessionId,
        scope,
        scope === "full" ? undefined : anchorIds
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

      {scope === "selection" ? (
        <fieldset className="agent-share-pick">
          <legend className="mb-1.5 text-[13px] font-semibold">
            {zh ? "选择要分享的问答" : "Pick turns to share"}
          </legend>
          <div className="agent-share-pick-list" role="group" aria-label={zh ? "选择要分享的问答" : "Pick turns to share"}>
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
                      type="checkbox"
                      name="agent-share-anchor"
                      value={anchor.id}
                      checked={selectedAnchorIds.has(anchor.id)}
                      onChange={() => toggleAnchor(anchor.id)}
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
      <DialogContent className="agent-share-dialog max-w-[480px] gap-3 p-5">
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

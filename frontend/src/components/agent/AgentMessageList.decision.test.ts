import { describe, expect, it } from "vitest";

import { shouldStickToBottom } from "./AgentMessageList";

/**
 * #140 贴底决策纯判定（票面：单测只测状态决策逻辑，不用 jsdom 布局数值冒充
 * 滚动验证——真实滚动由浏览器 smoke 终验）。六验收映射：
 * ①新流开始主动落底=streaming 起止效应（组件既有，不在本函数）；
 * ②底部持续跟随=streaming+atBottom；③上滚离底不强拉=streaming+!atBottom；
 * ④回底恢复跟随=atBottom 翻 true（atBottomStateChange 回调侧）；
 * ⑤切会话初始落底=pendingInitialScroll 窗口；⑥展开高度变化=非流式非初始不贴。
 */
describe("shouldStickToBottom", () => {
  it("初始落底窗口恒贴（含切会话装载期）", () => {
    expect(shouldStickToBottom({ isStreaming: false, pendingInitialScroll: true, atBottom: false })).toBe(true);
    expect(shouldStickToBottom({ isStreaming: true, pendingInitialScroll: true, atBottom: false })).toBe(true);
  });

  it("流式中在底部：跟随（新 token 贴底）", () => {
    expect(shouldStickToBottom({ isStreaming: true, pendingInitialScroll: false, atBottom: true })).toBe(true);
  });

  it("流式中上滚离底：释放不强拉", () => {
    expect(shouldStickToBottom({ isStreaming: true, pendingInitialScroll: false, atBottom: false })).toBe(false);
  });

  it("非流式非初始窗口（含 Reasoning/Tool 展开高度变化）：不贴", () => {
    expect(shouldStickToBottom({ isStreaming: false, pendingInitialScroll: false, atBottom: true })).toBe(false);
    expect(shouldStickToBottom({ isStreaming: false, pendingInitialScroll: false, atBottom: false })).toBe(false);
  });
});

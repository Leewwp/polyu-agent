/**
 * 聊天输入区共享文案（ChatInput/WelcomeScreen 两处同形；双语化时
 * 按 Standards 轴 Duplicated Code 收敛——数据级映射，不引入 i18n 框架，同 feedLang 先例）。
 */
export function composerText(zh: boolean) {
  return {
    placeholderIdle: zh ? "输入你的问题..." : "Type your question...",
    placeholderDeep: zh ? "输入需要深度分析的问题..." : "Ask a question that needs deep analysis...",
    deepThinking: zh ? "深度思考" : "Deep thinking",
    deepThinkingOn: zh
      ? "深度思考模式已开启，AI将进行更深入的分析推理"
      : "Deep thinking is on — the AI will reason more thoroughly",
    sendMessage: zh ? "发送消息" : "Send message",
    stopGenerating: zh ? "停止生成" : "Stop generating",
    enterHint: zh ? "发送" : "to send",
    newlineHint: zh ? "换行" : "for a new line",
    generating: zh ? "生成中..." : "Generating..."
  };
}

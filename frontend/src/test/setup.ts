/**
 * vitest 全局 setup：jsdom 缺少浏览器 API 的最小桩。
 * Radix Checkbox（BubbleInput 经 @radix-ui/react-use-size）渲染即实例化
 * ResizeObserver，不桩则首个渲染 Checkbox 的组件测试整体崩。
 */
class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

if (typeof globalThis.ResizeObserver === "undefined") {
  globalThis.ResizeObserver = ResizeObserverStub as unknown as typeof ResizeObserver;
}

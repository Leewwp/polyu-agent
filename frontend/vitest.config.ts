import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import path from "path";

// 前端测试底座（ROADMAP-20260907 第 9 步起步）：
// vitest 5.x 对齐项目 vite 8.x；jsdom 供组件渲染；@ 别名与 vite 主配置一致。
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      // import.meta.dirname：__dirname 不被 vite configLoader:'native' 支持（未来默认）
      "@": path.resolve(import.meta.dirname, "./src")
    }
  },
  test: {
    environment: "jsdom",
    globals: true,
    include: ["src/**/*.test.ts", "src/**/*.test.tsx"],
    setupFiles: ["src/test/setup.ts"],
    css: false
  }
});

import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import path from "path";

// 前端测试底座（ROADMAP-20260907 第 9 步起步）：
// vitest 2.x 对齐项目 vite 5.x；jsdom 供组件渲染；@ 别名与 vite 主配置一致。
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src")
    }
  },
  test: {
    environment: "jsdom",
    globals: true,
    include: ["src/**/*.test.ts", "src/**/*.test.tsx"],
    css: false
  }
});

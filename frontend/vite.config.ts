import { defineConfig, type Plugin } from "vite";
import react from "@vitejs/plugin-react";
import path from "path";

import { MOCK_DAILY_DIGESTS, MOCK_DAILY_DIGEST_SUMMARIES } from "./src/services/newsMockData";

/**
 * #241 日报截图仿真中间件（仅 dev server，VITE_DAILY_SIM=1 显式开启）：
 * 生产/开发一律真实 API（页面代码零 mock 分支），本中间件只在本地截图/
 * 视觉验收时于 /api/ragent/public/news/daily** 上服务 30 天确定性仿真
 * （newsMockData 的 vitest fixture 同源）。存档外日期回 code:"0"+data:null
 * ——与生产后端同形（PublicNewsController 口径），越界态可被真实演练。
 * 其余请求原样放行（next → 代理到本地后端）。
 * #272：VITE_DAILY_SIM_DELAY_MS 可注入响应延迟——真实浏览器在慢请求下
 * 验证骨架加载反馈用；不设置即零延迟。
 */
function dailySimPlugin(): Plugin {
  const simDelayMs = Number(process.env.VITE_DAILY_SIM_DELAY_MS ?? "0") || 0;
  return {
    name: "daily-sim-dev-middleware",
    configureServer(server) {
      // 直接注册（pre 钩子）——先于内置代理中间件拦截
      server.middlewares.use((req, res, next) => {
        const url = req.url ?? "";
        const base = "/api/ragent/public/news/daily";
        if (req.method !== "GET" || !url.startsWith(base)) {
          next();
          return;
        }
        const tail = url.slice(base.length).split("?")[0];
        const respond = (data: unknown) => {
          res.statusCode = 200;
          res.setHeader("content-type", "application/json");
          setTimeout(() => {
            res.end(JSON.stringify({ code: "0", data, message: null, requestId: null, success: true }));
          }, simDelayMs);
        };
        if (tail === "" || tail === "/") {
          respond(MOCK_DAILY_DIGEST_SUMMARIES);
          return;
        }
        const detailMatch = /^\/(\d{4}-\d{2}-\d{2})$/.exec(tail);
        if (detailMatch) {
          const found = MOCK_DAILY_DIGESTS.find((digest) => digest.digestDate === detailMatch[1]);
          respond(found ?? null);
          return;
        }
        next();
      });
    }
  };
}

// 判例：esbuild 打包 config 时 `import type` 被擦除，newsMockData 无运行时依赖可安全引入
const ENABLE_DAILY_SIM = process.env.VITE_DAILY_SIM === "1";

export default defineConfig({
  plugins: [react(), ...(ENABLE_DAILY_SIM ? [dailySimPlugin()] : [])],
  resolve: {
    alias: {
      // import.meta.dirname：__dirname 不被 vite configLoader:'native' 支持（未来默认）
      "@": path.resolve(import.meta.dirname, "./src")
    }
  },
  server: {
    host: "127.0.0.1",
    port: 5173,
    proxy: {
      "/api": {
        target: "http://localhost:9090",
        changeOrigin: true,
        secure: false
      }
    }
  }
});

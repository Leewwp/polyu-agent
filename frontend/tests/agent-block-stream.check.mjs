/** 实际 SSE 解析 → Agent store → 时间线块；只替换网络响应，不调用在线服务。 */
import assert from "node:assert/strict";
import { build } from "esbuild";
import { mkdirSync, rmSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const outDir = resolve(root, ".output/agent-block-stream");
mkdirSync(outDir, { recursive: true });
const originalFetch = globalThis.fetch;
try {
  await build({
    entryPoints: [resolve(root, "src/stores/agentChatStore.ts")],
    outfile: resolve(outDir, "store.mjs"),
    bundle: true,
    platform: "node",
    format: "esm",
    packages: "external",
    tsconfig: resolve(root, "tsconfig.app.json"),
    define: { "import.meta.env": "{}" }
  });
  const { useAgentChatStore: store } = await import(pathToFileURL(resolve(outDir, "store.mjs")));
  const initial = store.getState();
  const text = (kind, delta) => ["message", { type: kind, delta }];
  const seal = (kind, start, end) => ["block", {
    kind, at: "2026-09-28T01:00:00", startedAt: start, endedAt: end, durationMs: end - start
  }];
  const tool = (id, status, extra = {}) => ["block", {
    kind: "tool", toolCallId: id, name: "query_order", displayName: "查询订单", status, ...extra
  }];
  const finish = ["finish", { messageId: "saved-1", messageStatus: "NORMAL" }];
  const done = ["done", "[DONE]"];
  const run = async (frames) => {
    store.setState(initial, true);
    const wire = new TextEncoder().encode(frames.map(([name, payload]) =>
      `event: ${name}\ndata: ${JSON.stringify(payload)}\n\n`).join(""));
    globalThis.fetch = async () => new Response(new ReadableStream({
      start(controller) {
        // 包含拆开的 UTF-8 中文、事件名和 JSON，验证真实解析入口。
        for (let i = 0; i < wire.length; i += 7) controller.enqueue(wire.slice(i, i + 7));
        controller.close();
      }
    }), { headers: { "content-type": "text/event-stream" } });
    await store.getState().sendMessage("测试块更新");
    const assistant = store.getState().messages.at(-1);
    assert.notEqual(assistant.status, "error");
    return assistant;
  };

  // 旧思考块的计时晚于下一段回答到达，不能把新回答切成两块；工具更新则必须分块。
  let message = await run([
    text("reasoning", "先想"), text("answer", "先查"), seal("reasoning", 1000, 1100),
    text("answer", "订单"), tool("c1", "pending"), seal("answer", 1100, 1200),
    tool("c1", "running"), tool("c1", "done", { result: "订单一", durationMs: 8 }),
    text("answer", "查到了"), seal("answer", 1300, 1400), finish, done
  ]);
  assert.deepEqual(message.blocks.map(b => b.kind), ["reasoning", "answer", "tool", "answer"]);
  assert.deepEqual(message.blocks.map(b => b.text), ["先想", "先查订单", undefined, "查到了"]);
  assert.equal(message.blocks[2].status, "done");
  assert.equal(message.blocks[2].result, "订单一");
  assert.deepEqual(message.blocks.map(b => b.durationMs), [100, 100, 8, 100]);
  assert.equal(message.id, "saved-1");
  console.log("ok 混合 BLOCK 按 kind 分发，迟到的文本时间不切断新文字，工具仍会分块");

  message = await run([
    tool("c1", "pending"), tool("c2", "pending"), tool("c1", "running"), tool("c2", "running"),
    tool("c2", "done", { result: "订单二" }), tool("c1", "done", { result: "订单一" }), finish, done
  ]);
  assert.deepEqual(message.blocks.map(b => [b.toolCallId, b.result]), [["c1", "订单一"], ["c2", "订单二"]]);
  console.log("ok 同名并行工具反序完成，结果仍按 toolCallId 匹配");

  message = await run([
    tool("c1", "pending", { name: "cancel_order" }), tool("c2", "pending"),
    ["confirm", { messageId: "confirm-1", calls: [{ toolCallId: "c1", name: "cancel_order" }] }], done
  ]);
  assert.equal(message.messageStatus, "AWAITING_CONFIRM");
  assert.deepEqual(message.blocks.map(b => [b.kind, b.status]),
    [["tool", "awaiting"], ["tool", "awaiting"], ["confirm", "pending"]]);
  console.log("ok 确认卡仍使同批两个工具等待");

  message = await run([
    tool("c1", "pending"), tool("c1", "running"), text("error", "本轮中断，请核对"),
    ["finish", { messageId: "error-1", messageStatus: "INTERRUPTED" }], done
  ]);
  assert.deepEqual(message.blocks.map(b => b.kind), ["tool", "error"]);
  assert.equal(message.blocks[0].status, "interrupted");
  assert.equal(message.blocks[1].text, "本轮中断，请核对");
  console.log("ok 异常收尾保留错误提示并终结未完成工具");
  console.log("4/4 passed");
} finally {
  globalThis.fetch = originalFetch;
  rmSync(outDir, { recursive: true, force: true });
}

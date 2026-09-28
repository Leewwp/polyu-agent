#!/usr/bin/env python3
"""任务 A 双臂采集器：每题采 (question, contexts, answer) 三元组。

- contexts 源 = GET /rag/eval（检索漏斗过程指标 + 采集面对照）
- answer 源 = POST /agent/v1/chat（生产 agent 链，SSE），权威答案取
  GET /agent/v1/conversations/{id}/messages 末条 assistant content
  （finish 事件前已落库，AgentStreamEventBridge.settleAndPersistMessage 先于 sendTerminal）。
- chat 链路的真实 contexts 由后端埋点（RAG_EVAL_DUMP_FILE）旁路落盘，
  本脚本记录每题 ts_sent/ts_done 供离线按时间窗关联。

纪律：代理显式绕行（localhost 直连）；凭据经环境变量 EXP_CREDS（user:pass）传入，
token 落 /tmp 600 文件；题间限速；失败重试 ≤2。

用法：
  EXP_CREDS=<user>:<pass> \
  python3 collect_arms.py --arm on --evalset <evalset.jsonl> --out <triples-on.jsonl>
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import pathlib
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

# 7892 代理劫持判例：打本机后端必须显式绕行
for k in ("http_proxy", "https_proxy", "all_proxy", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY"):
    os.environ.pop(k, None)
os.environ["no_proxy"] = "localhost,127.0.0.1"
os.environ["NO_PROXY"] = "localhost,127.0.0.1"

TOKEN_FILE = pathlib.Path("/tmp/polyu-exp-login.json")


def _req(url: str, method: str = "GET", body: dict | None = None, token: str | None = None,
         timeout: float = 300.0, stream: bool = False):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", token)  # sa-token token-name（application.yaml:465）
    return urllib.request.urlopen(req, timeout=timeout)


def login(base: str) -> str:
    creds = os.environ.get("EXP_CREDS")
    if not creds or ":" not in creds:
        print("[collect] 缺少 EXP_CREDS（user:pass）", file=sys.stderr)
        sys.exit(2)
    user, pwd = creds.split(":", 1)
    with _req(f"{base}/auth/login", "POST", {"username": user, "password": pwd}) as resp:
        body = json.loads(resp.read().decode())
    token = (body.get("data") or {}).get("token")
    if not token:
        print(f"[collect] 登录失败: {str(body)[:200]}", file=sys.stderr)
        sys.exit(2)
    TOKEN_FILE.write_text(json.dumps({"token": token}))
    os.chmod(TOKEN_FILE, 0o600)
    return token


def load_token() -> str | None:
    if TOKEN_FILE.exists():
        try:
            return json.loads(TOKEN_FILE.read_text())["token"]
        except Exception:
            return None
    return None


def funnel_once(base: str, token: str, question: str) -> dict:
    qs = urllib.parse.urlencode({"question": question})
    with _req(f"{base}/rag/eval?{qs}", token=token, timeout=180.0) as resp:
        body = json.loads(resp.read().decode())
    if not body.get("success"):
        raise RuntimeError(f"eval success={body.get('success')} code={body.get('code')}")
    return body.get("data") or {}


def chat_once(base: str, token: str, question: str, conversation_id: str | None = None) -> dict:
    """发一轮 agent chat，SSE 消费到 finish；返回会话与流式累积。"""
    out = {"conversation_id": None, "stream_answer": [], "finish_status": None,
           "n_tool_events": 0, "error": None}
    event = None
    with _req(f"{base}/agent/v1/chat", "POST",
              {"question": question, "conversationId": conversation_id}, token=token) as resp:
        for raw in resp:
            line = raw.decode("utf-8", "replace").rstrip("\n")
            if line.startswith("event:"):
                event = line.split(":", 1)[1].strip()
                continue
            if line.startswith("data:"):
                payload = line.split(":", 1)[1].strip()
                try:
                    data = json.loads(payload)
                except json.JSONDecodeError:
                    data = payload
                if event == "meta":
                    out["conversation_id"] = data.get("conversationId")
                elif event == "message":
                    if data.get("type") == "response":  # TextChannel.ANSWER.deltaType
                        out["stream_answer"].append(data.get("delta") or "")
                elif event == "tool":
                    out["n_tool_events"] += 1
                elif event == "finish":
                    out["finish_status"] = data.get("status") or data.get("messageStatus")
                    break
                elif event == "error":
                    out["error"] = str(data)[:300]
                    break
    return out


def last_assistant(base: str, token: str, conversation_id: str) -> dict | None:
    with _req(f"{base}/agent/v1/conversations/{conversation_id}/messages",
              token=token, timeout=60.0) as resp:
        body = json.loads(resp.read().decode())
    msgs = (body.get("data") or [])
    for m in reversed(msgs):
        if m.get("role") == "assistant":
            return m
    return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--arm", required=True, help="on|off（进 run_id 与输出行）")
    ap.add_argument("--evalset", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--base", default="http://localhost:9090/api/ragent")
    ap.add_argument("--interval", type=float, default=1.2)
    ap.add_argument("--retries", type=int, default=2)
    ap.add_argument("--limit", type=int, default=0, help="只跑前 N 题（冒烟用），0=全量")
    args = ap.parse_args()

    out_path = pathlib.Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    stamp = dt.datetime.now().astimezone().strftime("%Y%m%d-%H%M%S")
    run_id = f"ragas-ablation-{args.arm}-{stamp}"

    token = load_token()
    questions = [json.loads(l) for l in open(args.evalset, encoding="utf-8") if l.strip()]
    if args.limit:
        questions = questions[:args.limit]

    done_qids = set()
    if out_path.exists():  # 断点续采：跳过已完成 q_id
        for l in open(out_path, encoding="utf-8"):
            try:
                done_qids.add(json.loads(l)["q_id"])
            except Exception:
                pass

    rows = 0
    with out_path.open("a", encoding="utf-8") as f:
        for i, q in enumerate(questions):
            if q["q_id"] in done_qids:
                continue
            time.sleep(args.interval)
            ts_sent = dt.datetime.now().astimezone().isoformat()

            funnel = None
            err = None
            for attempt in range(args.retries + 1):
                try:
                    if token is None:
                        token = login(args.base)
                    funnel = funnel_once(args.base, token, q["question"])
                    break
                except Exception as e:  # noqa: BLE001
                    err = e
                    token = None
                    if attempt < args.retries:
                        time.sleep(3 * (attempt + 1))
            if funnel is None:
                print(f"[collect] FAIL funnel {q['q_id']}: {err}", file=sys.stderr)
                return 1

            chat = None
            err = None
            for attempt in range(args.retries + 1):
                try:
                    if token is None:
                        token = login(args.base)
                    chat = chat_once(args.base, token, q["question"])
                    break
                except Exception as e:  # noqa: BLE001
                    err = e
                    token = None
                    if attempt < args.retries:
                        time.sleep(3 * (attempt + 1))
            if chat is None:
                print(f"[collect] FAIL chat {q['q_id']}: {err}", file=sys.stderr)
                return 1
            if chat.get("error"):
                print(f"[collect] WARN chat error event {q['q_id']}: {chat['error']}",
                      file=sys.stderr)

            answer = ""
            msg_status = None
            if chat.get("conversation_id"):
                try:
                    m = last_assistant(args.base, token, chat["conversation_id"])
                    if m:
                        answer = m.get("content") or ""
                        msg_status = m.get("messageStatus")
                except Exception as e:  # noqa: BLE001
                    print(f"[collect] WARN messages {q['q_id']}: {e}", file=sys.stderr)
                    answer = "".join(chat["stream_answer"])

            ts_done = dt.datetime.now().astimezone().isoformat()
            row = {
                "q_id": q["q_id"],
                "run_id": run_id,
                "arm": args.arm,
                "question": q["question"],
                "ts_sent": ts_sent,
                "ts_done": ts_done,
                "answer": answer,
                "answer_stream": "".join(chat["stream_answer"]),
                "message_status": msg_status,
                "finish_status": chat.get("finish_status"),
                "conversation_id": chat.get("conversation_id"),
                "n_tool_events": chat.get("n_tool_events"),
                "funnel": {
                    "latency_ms": funnel.get("latencyMs"),
                    "has_kb": funnel.get("hasKb"),
                    "sub_intents": funnel.get("subIntents") or [],
                    "retrieved_chunk_ids": funnel.get("retrievedChunkIds") or [],
                    "retrieved_doc_ids": funnel.get("retrievedDocIds") or [],
                    "retrieved_context_doc_ids": funnel.get("retrievedContextDocIds") or [],
                    "retrieved_contexts": funnel.get("retrievedContexts") or [],
                },
            }
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
            f.flush()
            rows += 1
            print(f"[collect] {i + 1}/{len(questions)} {q['q_id']} "
                  f"ans={len(answer)}ch funnel={funnel.get('latencyMs')}ms status={msg_status}",
                  flush=True)
    print(f"[collect] OK arm={args.arm} rows={rows} -> {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

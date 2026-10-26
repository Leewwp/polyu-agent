#!/usr/bin/env python3
"""run_probes.py — 红队探针公开采集器（evaluation/redteam 夹具，见 #320）。

流程：
  1. POST /auth/login 换取 token（测试账号经 --account/--password 本地注入，不入库；
     响应取 data.token）。
  2. 每题一个新会话：POST /rag/v3/chat，body={"question": ...}（不带 conversationId），
     请求头 Authorization: <裸 token>（sa-token token-name，无 Bearer 前缀）、
     Accept: text/event-stream。
  3. SSE 消费：event: meta 帧取 taskId/conversationId；event: message 帧取
     type in ("response", "answer") 的 delta 拼接为答案；event: finish 或
     data: [DONE] 为正常终止；event: error 记为技术失败。
  4. 题间 --sleep 限速；技术失败（异常/无终止事件/error 帧）补采 ≤1 次；
     断点续采：--out 中已有 tech_ok=true 的 q_id 跳过。

输出 jsonl 行字段：q_id/tier/category/lang/question/answer/tech_ok/finish/
task_id/conversation_id/error/collected_at。tech_ok=true 的行供
judge_redteam.py 消费；采集输出属本地实验产物，不入库。

自包含：仅标准库（urllib），无第三方依赖；代理显式绕行（localhost 直连）。

用法：
  python3 run_probes.py \
      --account <test-user@example.com> --password <pass> \
      --probes probes-redteam-public-v1.jsonl \
      --out results-redteam-public.jsonl
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import pathlib
import sys
import time
import urllib.request

# 本机后端必须显式绕行系统代理（与 evaluation/collect_arms.py 同纪律）
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
HERE = pathlib.Path(__file__).parent
CHAT_TIMEOUT_S = 300
ANSWER_TYPES = ("response", "answer")


def now_iso() -> str:
    return dt.datetime.now().astimezone().isoformat()


def login(base: str, account: str, password: str) -> str:
    req = urllib.request.Request(
        base + "/auth/login",
        data=json.dumps({"username": account, "password": password}).encode(),
        headers={"Content-Type": "application/json"}, method="POST")
    with OPENER.open(req, timeout=30) as resp:
        body = json.loads(resp.read().decode("utf-8", "replace"))
    token = (body.get("data") or {}).get("token")
    if not token:
        raise RuntimeError(f"login failed: {str(body)[:200]}")
    return token


def chat_once(base: str, token: str, question: str, timeout_s: int = CHAT_TIMEOUT_S) -> dict:
    """发一轮 rag chat（新会话），SSE 消费到终止；返回流式累积与元信息。"""
    req = urllib.request.Request(
        base + "/rag/v3/chat",
        data=json.dumps({"question": question}).encode(),
        headers={"Content-Type": "application/json",
                 "Authorization": token,  # 裸 token（sa-token token-name）
                 "Accept": "text/event-stream"}, method="POST")
    out = {"answer": "", "finish": None, "task_id": None,
           "conversation_id": None, "error": None}
    parts: list[str] = []
    event = None
    with OPENER.open(req, timeout=timeout_s) as resp:
        for raw in resp:
            line = raw.decode("utf-8", "replace").rstrip("\r\n")
            if line.startswith("event:"):
                event = line.split(":", 1)[1].strip()
                continue
            if not line.startswith("data:"):
                if line == "":
                    event = None
                continue
            payload = line.split(":", 1)[1].strip()
            if payload == "[DONE]":
                out["finish"] = out["finish"] or "done"
                break
            try:
                data = json.loads(payload)
            except json.JSONDecodeError:
                continue
            if event == "meta" and isinstance(data, dict):
                out["conversation_id"] = out["conversation_id"] or data.get("conversationId")
                out["task_id"] = out["task_id"] or data.get("taskId")
            elif event == "message" and isinstance(data, dict):
                if data.get("type") in ANSWER_TYPES:
                    parts.append(data.get("delta") or "")
            elif event == "finish":
                out["finish"] = "finish"
            elif event == "error":
                out["error"] = str(data)[:300]
                out["finish"] = "error"
                break
    if out["finish"] is None:
        out["finish"] = "NO_FINISH"
    out["answer"] = "".join(parts)
    return out


def collect_one(base: str, token_getter, question: str) -> dict:
    """技术失败（异常/无终止/error）补采 ≤1 次；返回采集记录。"""
    last_err = None
    for _ in range(2):  # 首采 + 补采 ≤1
        try:
            chat = chat_once(base, token_getter(), question)
            if chat.get("finish") in ("finish", "done") and not chat.get("error"):
                chat["error"] = None
                return chat
            last_err = chat.get("error") or f"finish={chat.get('finish')}"
        except Exception as e:  # noqa: BLE001
            last_err = f"{type(e).__name__}: {e}"
            token_getter.reset()
        time.sleep(2)
    return {"answer": "", "finish": "TECH_FAIL", "task_id": None,
            "conversation_id": None, "error": last_err}


class TokenGetter:
    """token 惰性获取 + 失败后强制重登（补采时重建会话凭据）。"""

    def __init__(self, base: str, account: str, password: str):
        self._base = base
        self._account = account
        self._password = password
        self._token = None

    def __call__(self) -> str:
        if self._token is None:
            self._token = login(self._base, self._account, self._password)
        return self._token

    def reset(self) -> None:
        self._token = None


def main() -> int:
    here = HERE
    ap = argparse.ArgumentParser(description="红队探针公开采集器（rag 链 SSE）")
    ap.add_argument("--base", default="http://127.0.0.1:9090/api/ragent")
    ap.add_argument("--account", required=True, help="测试账号（本地注入，不入库）")
    ap.add_argument("--password", required=True, help="测试账号密码（本地注入，不入库）")
    ap.add_argument("--probes", default=str(here / "probes-redteam-public-v1.jsonl"))
    ap.add_argument("--out", default=str(here / "results-redteam-public.jsonl"))
    ap.add_argument("--sleep", type=float, default=2.0, help="题间限速秒数")
    args = ap.parse_args()

    probes = [json.loads(l) for l in open(args.probes, encoding="utf-8") if l.strip()]

    done = set()  # 断点续采：tech_ok=true 的 q_id 跳过
    if os.path.exists(args.out):
        for l in open(args.out, encoding="utf-8"):
            try:
                r = json.loads(l)
                if r.get("tech_ok"):
                    done.add(r["q_id"])
            except Exception:  # noqa: BLE001
                continue

    tokens = TokenGetter(args.base, args.account, args.password)
    rows = 0
    with open(args.out, "a", encoding="utf-8") as f:
        for p in probes:
            if p["q_id"] in done:
                continue
            chat = collect_one(args.base, tokens, p["question"])
            tech_ok = chat["finish"] in ("finish", "done") and not chat.get("error")
            rec = {"q_id": p["q_id"], "tier": p.get("tier"), "category": p.get("category"),
                   "lang": p.get("lang"), "question": p["question"],
                   "answer": chat["answer"], "tech_ok": tech_ok, "finish": chat["finish"],
                   "task_id": chat.get("task_id"), "conversation_id": chat.get("conversation_id"),
                   "error": chat.get("error"), "collected_at": now_iso()}
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
            f.flush()
            rows += 1
            print(f"[collect] {p['q_id']} finish={rec['finish']} "
                  f"answer_chars={len(rec['answer'])}", flush=True)
            time.sleep(args.sleep)
    print(f"[collect] DONE rows={rows} -> {args.out}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())

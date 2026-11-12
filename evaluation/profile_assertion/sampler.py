# -*- coding: utf-8 -*-
"""画像采样器（#488 T3 可选交付；live 轮=批2 评测窗，本票零调用）。

采样口径（票面 §3 / spike-1 §2）：
1. 每题空 conversationId 新会话 ``POST /agent/v1/chat``（普通用户 token；
   每题新会话=天然隔离，runGate 会话级互斥自动错峰）。
2. SSE 消费到 finish（超时上限 sseTimeoutMs 900s 默认）；**finish.messageId
   为最强锚点**（先落库后发帧，AgentStreamEventBridge L214-216）。
3. 经 messages API 取该行 blocks+messageStatus（DB 直读与 API 读等价，同一 JSON）。
4. 断言器以 blocks JSON 为唯一事实源（SSE 只做完成信号）。

风险处置（票面 §4）：③断流/超时/无 finish/无 assistant 行=采样失败
（返回 blocks=SAMPLING_FAILED，非 0 调用）；②live 轮评测臂应关长期记忆防
flush_memory 漂移（断言器侧默认已排除）；①题集避开 require_confirm=1 工具。

纪律：transport 可注入——单测/干跑全部走 FakeTransport，禁止实际调用任何
在线端点/LLM（本票验收 6）；批2 live 轮才用默认 urllib transport。
"""
from __future__ import annotations

import json
import sys
import urllib.error
import urllib.request
from typing import Callable, List, Optional, Tuple

import profile_assertor as pa

# Transport 合同：transport(url, method, body, token, timeout) → file-like
# （.read() 整读 REST / 逐行迭代 SSE bytes）。默认实现=urllib；测试注入假件。
Transport = Callable[..., object]

DEFAULT_SSE_TIMEOUT = 900.0  # sseTimeoutMs 默认（AgentProperties.java:59）


def default_transport(url: str, method: str = "GET", body: Optional[dict] = None,
                      token: Optional[str] = None, timeout: float = 60.0):
    """urllib 实现（仅供批2 live 轮；单测/干跑禁用）。"""
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "text/event-stream" if method == "POST" else "application/json")
    if token:
        req.add_header("Authorization", token)  # sa-token token-name
    return urllib.request.urlopen(req, timeout=timeout)


def login(transport: Transport, base: str, creds: Tuple[str, str]) -> str:
    """EXP_CREDS（user:pass）→ 普通用户 token（/agent/v1 不在 admin 清单）。

    契约对齐 evaluation/collect_arms.py 实测形态（2026-11-12 复核逐位一致）：
    POST /auth/login，body={username,password}，token=data.token，
    Authorization 头=裸 token 值（sa-token，无 Bearer 前缀）。
    """
    user, pwd = creds
    resp = transport(f"{base}/auth/login", "POST",
                     {"username": user, "password": pwd}, timeout=30.0)
    body = json.loads(resp.read().decode())
    token = (body.get("data") or {}).get("token")
    if not token:
        raise RuntimeError(f"登录失败: {str(body)[:200]}")
    return token


def _dispatch_sse_event(out: dict, event: Optional[str], payload: str) -> bool:
    """单事件派发：payload=多行 data: 以 \\n 拼接后的完整载荷；返回 True=终止事件（finish/error）。"""
    try:
        data = json.loads(payload)
    except json.JSONDecodeError:
        data = {}
    if event == "meta":
        out["conversation_id"] = (data or {}).get("conversationId")
        return False
    if event == "finish":
        out["finish_seen"] = True
        out["message_id"] = (data or {}).get("messageId") or None
        out["message_status"] = (data or {}).get("messageStatus")
        return True
    if event == "error":
        out["error"] = str(data)[:300]
        return True
    return False


def sse_chat(transport: Transport, base: str, token: str, question: str,
             conversation_id: Optional[str] = None,
             sse_timeout: float = DEFAULT_SSE_TIMEOUT) -> dict:
    """发一轮 chat 并消费 SSE 到 finish/error/EOF；只做完成信号，不采答案。

    SSE 解析按 whatwg 规范：同一事件的多个 ``data:`` 行以 ``\\n`` 拼接成一个载荷
    （拼接前的半段不是合法 JSON，禁止逐行解析只取末行），空行=事件分界；
    EOF 时若仍有未派发的缓冲事件则补派发一次（规范对流尾的要求）。
    """
    out = {"conversation_id": conversation_id, "message_id": None,
           "message_status": None, "finish_seen": False, "error": None}
    body = {"question": question}
    if conversation_id:
        body["conversationId"] = conversation_id
    event: Optional[str] = None
    data_lines: List[str] = []
    resp = transport(f"{base}/agent/v1/chat", "POST", body,
                     token=token, timeout=sse_timeout)
    for raw in resp:
        line = raw.decode("utf-8", "replace").rstrip("\r\n")
        if not line:
            # 空行=事件分界：派发缓冲载荷并复位字段缓冲
            if data_lines and _dispatch_sse_event(out, event, "\n".join(data_lines)):
                return out
            event, data_lines = None, []
            continue
        if line.startswith(":"):
            continue  # SSE 注释行（心跳等）
        field, sep, value = line.partition(":")
        if sep and value.startswith(" "):
            value = value[1:]  # 规范：字段值至多去一个前导空格
        if field == "event":
            event = value
        elif field == "data":
            data_lines.append(value)
        # 其余字段（id/retry）与单行多事件之外的形态不在本消费面
    # EOF：派发残留缓冲事件（断流时最后一段无空行收尾也按事件处理）
    if data_lines and _dispatch_sse_event(out, event, "\n".join(data_lines)):
        return out
    return out


def fetch_message_blocks(transport: Transport, base: str, token: str,
                         conversation_id: str, message_id: str) -> Optional[dict]:
    """messages API 按 messageId 取 assistant 行 → {blocks, message_status}。"""
    resp = transport(
        f"{base}/agent/v1/conversations/{conversation_id}/messages",
        token=token, timeout=60.0)
    body = json.loads(resp.read().decode())
    for msg in (body.get("data") or []):
        if msg.get("id") == message_id and msg.get("role") == "assistant":
            return {"blocks": msg.get("blocks"),
                    "message_status": msg.get("messageStatus")}
    return None


def sample_one(transport: Transport, base: str, token: str, question: str,
               sse_timeout: float = DEFAULT_SSE_TIMEOUT) -> dict:
    """单题采样：新会话 chat → finish.messageId → messages API 取 blocks。

    任一失败面（error 事件/断流无 finish/finish 无 messageId/行缺失/传输异常）
    → blocks=pa.SAMPLING_FAILED（风险处置③：采样失败≠0 调用）。
    """
    out = {"conversation_id": None, "message_id": None,
           "message_status": None, "blocks": pa.SAMPLING_FAILED}
    try:
        chat = sse_chat(transport, base, token, question,
                        conversation_id=None, sse_timeout=sse_timeout)
    except Exception as exc:  # noqa: BLE001 —— 传输层任何异常都归采样失败
        out["error"] = f"chat 传输异常: {exc}"
        return out
    out["conversation_id"] = chat["conversation_id"]
    out["message_id"] = chat["message_id"]
    if chat["error"] or not chat["finish_seen"] or not chat["message_id"]:
        out["error"] = chat["error"] or (
            "采样失败：SSE 未收到带 messageId 的 finish（断流/超时/取消，非 0 调用）")
        return out
    try:
        row = fetch_message_blocks(transport, base, token,
                                   chat["conversation_id"], chat["message_id"])
    except Exception as exc:  # noqa: BLE001
        out["error"] = f"messages 传输异常: {exc}"
        return out
    if row is None:
        out["error"] = "采样失败：messages API 未找到该 assistant 行"
        return out
    out["blocks"] = row["blocks"]
    out["message_status"] = row["message_status"]
    return out


def load_evalset(path: str) -> List[dict]:
    """载入题集（本地私有件）：逐行校验 q_id/question 与画像四要素。"""
    rows: List[dict] = []
    with open(path, encoding="utf-8") as fh:
        for lineno, line in enumerate(fh, 1):
            if not line.strip():
                continue
            row = json.loads(line)
            problems = []
            if not isinstance(row.get("q_id"), str) or not row.get("q_id"):
                problems.append("q_id 缺失")
            if not isinstance(row.get("question"), str) or not row.get("question"):
                problems.append("question 缺失")
            problems += pa.validate_expected(row.get("expected_tool_profile"))
            if problems:
                raise ValueError(
                    f"evalset 第 {lineno} 行（{row.get('q_id', '?')}）非法: {problems}")
            rows.append(row)
    return rows


def main(argv: Optional[List[str]] = None) -> int:
    """干跑入口：--evalset 只做载入+画像分布统计，零 HTTP 零调用。"""
    import argparse
    import collections

    ap = argparse.ArgumentParser(description="题集干跑校验（零网络）")
    ap.add_argument("--evalset", required=True)
    args = ap.parse_args(argv)
    rows = load_evalset(args.evalset)
    dist = collections.Counter(
        (r["expected_tool_profile"]["expect"],
         r["expected_tool_profile"]["terminal"]) for r in rows)
    print(f"[dryrun] {len(rows)} 题；expect×terminal 分布: {dict(dist)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

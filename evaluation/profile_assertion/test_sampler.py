# -*- coding: utf-8 -*-
"""采样器单测（#488 T3 可选交付）——全部 mock transport，零网络零 LLM。

验收合同：login→POST /agent/v1/chat（空 conversationId）→finish.messageId→
messages API 取 blocks；断流/超时/无 finish/无行=采样失败（SAMPLING_FAILED），
与 0 调用画像严格区分。禁止实际调用任何在线端点。
"""
from __future__ import annotations

import json

import pytest

import profile_assertor as pa
import sampler


class FakeResponse:
    """file-like：可整读（REST）也可逐行迭代（SSE）。"""

    def __init__(self, lines):
        self._lines = [l if isinstance(l, bytes) else l.encode() for l in lines]

    def read(self):
        return b"".join(self._lines)

    def __iter__(self):
        return iter(self._lines)


class FakeTransport:
    """按 (method, url_path) 脚本化响应；记录请求供断言。"""

    def __init__(self, script):
        self.script = script  # {(method, path): FakeResponse 或 Exception}
        self.calls = []

    def __call__(self, url, method="GET", body=None, token=None, timeout=30.0):
        path = url.split("/api/ragent", 1)[-1]
        self.calls.append({"method": method, "path": path, "body": body, "token": token})
        result = self.script[(method, path)]
        if isinstance(result, Exception):
            raise result
        return result


def sse(*events):
    """(event, payload) 序列 → SSE 行列表。"""
    lines = []
    for event, payload in events:
        lines.append(f"event: {event}")
        lines.append(f"data: {json.dumps(payload)}")
        lines.append("")
    return lines


BASE = "http://synthetic.invalid/api/ragent"  # 不会被真正调用（FakeTransport 截获）

ASSISTANT_ROW = {
    "id": "m1",
    "role": "assistant",
    "messageStatus": "NORMAL",
    "content": "（合成）作答",
    "blocks": [
        {"kind": "tool", "name": "search_knowledge", "status": "done",
         "result": "（合成）检索结果"},
        {"kind": "answer", "text": "（合成）作答"},
    ],
}


def happy_transport():
    return FakeTransport({
        ("POST", "/agent/v1/chat"): FakeResponse(sse(
            ("meta", {"conversationId": "c1", "taskId": "t1"}),
            ("block", {"kind": "tool", "name": "search_knowledge", "status": "done"}),
            ("finish", {"messageId": "m1", "title": None, "messageStatus": "NORMAL",
                        "durationMs": 1234}),
            ("done", {}),
        )),
        ("GET", "/agent/v1/conversations/c1/messages"): FakeResponse(
            [json.dumps({"success": True, "data": [{"id": "u1", "role": "user"},
                                                   ASSISTANT_ROW]})]),
    })


class TestSampleOne:
    def test_happy_path_returns_blocks_and_status(self):
        t = happy_transport()
        out = sampler.sample_one(t, BASE, token="tok", question="（合成）问题")
        assert out["blocks"] == ASSISTANT_ROW["blocks"]
        assert out["message_status"] == "NORMAL"
        assert out["message_id"] == "m1"
        assert out["conversation_id"] == "c1"
        # 空 conversationId=新会话：请求体不带 conversationId 键
        chat_call = t.calls[0]
        assert chat_call["path"] == "/agent/v1/chat"
        assert set(chat_call["body"]) == {"question"}

    def test_error_event_is_sampling_failed(self):
        t = FakeTransport({
            ("POST", "/agent/v1/chat"): FakeResponse(
                sse(("meta", {"conversationId": "c1"}), ("error", {"message": "x"}))),
        })
        out = sampler.sample_one(t, BASE, token="tok", question="q")
        assert out["blocks"] is pa.SAMPLING_FAILED

    def test_stream_cut_without_finish_is_sampling_failed(self):
        t = FakeTransport({
            ("POST", "/agent/v1/chat"): FakeResponse(
                sse(("meta", {"conversationId": "c1"}))),  # 断流：无 finish
        })
        out = sampler.sample_one(t, BASE, token="tok", question="q")
        assert out["blocks"] is pa.SAMPLING_FAILED

    def test_finish_without_message_id_is_sampling_failed(self):
        t = FakeTransport({
            ("POST", "/agent/v1/chat"): FakeResponse(sse(
                ("meta", {"conversationId": "c1"}),
                ("finish", {"messageId": "", "messageStatus": "NORMAL"}))),
        })
        out = sampler.sample_one(t, BASE, token="tok", question="q")
        assert out["blocks"] is pa.SAMPLING_FAILED

    def test_message_row_missing_is_sampling_failed(self):
        t = FakeTransport({
            ("POST", "/agent/v1/chat"): FakeResponse(sse(
                ("meta", {"conversationId": "c1"}),
                ("finish", {"messageId": "mX", "messageStatus": "NORMAL"}))),
            ("GET", "/agent/v1/conversations/c1/messages"): FakeResponse(
                [json.dumps({"success": True, "data": []})]),
        })
        out = sampler.sample_one(t, BASE, token="tok", question="q")
        assert out["blocks"] is pa.SAMPLING_FAILED

    def test_sampler_output_feeds_assertor(self):
        out = sampler.sample_one(happy_transport(), BASE, token="tok", question="q")
        verdict = pa.assert_profile(out["blocks"], out["message_status"], {
            "allowed_tools": ["search_knowledge"], "calls": {"search_knowledge": [1, 1]},
            "expect": "answer", "terminal": "NORMAL"})
        assert verdict.ok is True


class TestLogin:
    def test_login_extracts_token(self):
        t = FakeTransport({
            ("POST", "/auth/login"): FakeResponse(
                [json.dumps({"success": True, "data": {"token": "satoken-1"}})]),
        })
        assert sampler.login(t, BASE, ("u", "p")) == "satoken-1"
        assert t.calls[0]["body"] == {"username": "u", "password": "p"}


class TestSseChat:
    def test_multiline_data_joined_by_newline_per_sse_spec(self):
        # SSE 规范：同一事件的多行 data: 以 \n 拼接成完整载荷后才能 json 解析；
        # 逐行解析只取末行会丢失前半段（半段 JSON 非法）。在 token 边界（冒号后）拆两行模拟分帧，
        # 拼接载荷 = '{"conversationId":\n "c-multiline"}'（token 间换行对 JSON 合法）。
        t = FakeTransport({
            ("POST", "/agent/v1/chat"): FakeResponse([
                "event: meta",
                'data: {"conversationId":',
                'data:  "c-multiline"}',
                "",
            ]),
        })
        out = sampler.sse_chat(t, BASE, token="tok", question="q")
        assert out["conversation_id"] == "c-multiline"
        assert out["finish_seen"] is False  # 断流无 finish

    def test_eof_dispatches_buffered_event_without_trailing_blank_line(self):
        # 流尾无空行收尾：EOF 时仍须派发缓冲中的事件（规范对流尾的要求）
        t = FakeTransport({
            ("POST", "/agent/v1/chat"): FakeResponse([
                "event: finish",
                "data: " + json.dumps({"messageId": "m-eof", "messageStatus": "NORMAL"}),
            ]),
        })
        out = sampler.sse_chat(t, BASE, token="tok", question="q")
        assert out["finish_seen"] is True
        assert out["message_id"] == "m-eof"
        assert out["message_status"] == "NORMAL"


class TestLoadEvalset:
    def test_load_evalset_validates_four_elements(self, tmp_path):
        good = {
            "q_id": "sample-1",
            "question": "（合成样例）问题",
            "expected_tool_profile": {
                "allowed_tools": ["search_knowledge"],
                "calls": {"search_knowledge": [1, 2]},
                "expect": "answer",
                "terminal": "NORMAL",
            },
        }
        bad = dict(good, q_id="sample-2",
                   expected_tool_profile={"allowed_tools": [], "calls": {}})  # 缺 expect/terminal
        path = tmp_path / "evalset.jsonl"
        path.write_text(json.dumps(good, ensure_ascii=False) + "\n"
                        + json.dumps(bad, ensure_ascii=False) + "\n", encoding="utf-8")
        with pytest.raises(ValueError, match="sample-2"):
            sampler.load_evalset(str(path))

    def test_load_evalset_returns_rows(self, tmp_path):
        row = {
            "q_id": "sample-1",
            "question": "（合成样例）问题",
            "expected_tool_profile": {
                "allowed_tools": [], "calls": {}, "expect": "refuse", "terminal": "NORMAL",
            },
        }
        path = tmp_path / "evalset.jsonl"
        path.write_text(json.dumps(row, ensure_ascii=False) + "\n", encoding="utf-8")
        assert sampler.load_evalset(str(path)) == [row]

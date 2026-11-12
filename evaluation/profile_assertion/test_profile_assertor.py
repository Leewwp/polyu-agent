# -*- coding: utf-8 -*-
"""轨迹调用画像断言器确定性测试（#488 T3）。

夹具全部为脱敏合成样本（fixtures/ 下 JSON），零 LLM、零 DB、零网络。
用例合同=票面 #488 验收标准 1：
answer 区间内/越界、refuse（无 tool 块/守卫 blocks=null）、terminal（NORMAL/INTERRUPTED）、
status 集合断言（failed 容忍/可选 denied_expected）、flush_memory 排除、
同名并行两块按 name 计数=2（无 toolCallId 不影响）、采样失败信号≠0 调用。
"""
from __future__ import annotations

import json
import pathlib

import profile_assertor as pa

FIXTURES = pathlib.Path(__file__).parent / "fixtures"


def load_fixture(name: str):
    """读合成夹具；guards_refuse 夹具本身是 JSON null。"""
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


# ---------------------------------------------------------------- 解析面


class TestParseBlocks:
    def test_none_and_empty_and_json_null_all_mean_no_blocks(self):
        assert pa.parse_blocks(None) == []
        assert pa.parse_blocks("") == []
        assert pa.parse_blocks("null") == []
        assert pa.parse_blocks([]) == []

    def test_json_array_string_parsed(self):
        raw = json.dumps([{"kind": "answer", "text": "合成文本"}])
        assert pa.parse_blocks(raw) == [{"kind": "answer", "text": "合成文本"}]

    def test_malformed_json_raises_parse_error(self):
        try:
            pa.parse_blocks("[{\"kind\": broken")
        except pa.BlocksParseError:
            pass
        else:
            raise AssertionError("malformed JSON must raise BlocksParseError")


# ---------------------------------------------------------------- 画像提取面


class TestExtractToolProfile:
    def test_counts_by_name_with_status_sets_and_ignores_non_tool(self):
        blocks = load_fixture("msg_answer_multi_tool.json")
        profile = pa.extract_tool_profile(blocks)
        # confirm/reasoning/answer 块不进画像；search_knowledge 两块（done+failed）
        assert profile == {
            "search_knowledge": {"count": 2, "statuses": ["done", "failed"]},
        }

    def test_flush_memory_excluded_from_profile(self):
        blocks = load_fixture("msg_answer_multi_tool.json")
        assert "flush_memory" not in pa.extract_tool_profile(blocks)

    def test_no_tool_blocks_yields_empty_profile(self):
        assert pa.extract_tool_profile([{"kind": "answer", "text": "x"}]) == {}


# ---------------------------------------------------------------- 断言器：answer 形态

PROFILE_ANSWER = {
    "allowed_tools": ["search_knowledge"],
    "calls": {"search_knowledge": [1, 2]},
    "expect": "answer",
    "terminal": "NORMAL",
}


class TestAssertProfileAnswer:
    def test_answer_within_interval_passes(self):
        blocks = load_fixture("msg_answer_multi_tool.json")  # search_knowledge ×2（done+failed）
        verdict = pa.assert_profile(blocks, "NORMAL", PROFILE_ANSWER)
        assert verdict.ok is True
        assert verdict.kind == "PASS"

    def test_answer_below_min_fails(self):
        blocks = load_fixture("msg_answer_multi_tool.json")  # 2 次调用
        expected = dict(PROFILE_ANSWER, calls={"search_knowledge": [3, 4]})
        verdict = pa.assert_profile(blocks, "NORMAL", expected)
        assert verdict.ok is False
        assert verdict.kind == "PROFILE_MISMATCH"
        assert any("search_knowledge" in f for f in verdict.failures)

    def test_answer_above_max_fails(self):
        blocks = load_fixture("msg_answer_multi_tool.json")  # 2 次调用
        expected = dict(PROFILE_ANSWER, calls={"search_knowledge": [0, 1]})
        verdict = pa.assert_profile(blocks, "NORMAL", expected)
        assert verdict.ok is False

    def test_zero_call_answer_profile_passes_with_zero_interval(self):
        blocks = [{"kind": "answer", "text": "（合成）纯文本直答"}]
        expected = dict(PROFILE_ANSWER, calls={"search_knowledge": [0, 0]})
        verdict = pa.assert_profile(blocks, "NORMAL", expected)
        assert verdict.ok is True

    def test_unexpected_tool_outside_allowed_set_fails(self):
        blocks = [
            {"kind": "tool", "name": "search_exam_papers", "status": "done"},
        ]
        verdict = pa.assert_profile(blocks, "NORMAL", PROFILE_ANSWER)
        assert verdict.ok is False
        assert any("search_exam_papers" in f for f in verdict.failures)

    def test_missing_four_elements_is_invalid_expected(self):
        for broken in (
            {"allowed_tools": [], "calls": {}, "expect": "answer"},          # 缺 terminal
            {"allowed_tools": [], "calls": {}, "terminal": "NORMAL"},        # 缺 expect
            {"calls": {}, "expect": "answer", "terminal": "NORMAL"},         # 缺 allowed_tools
            {"allowed_tools": [], "expect": "answer", "terminal": "NORMAL"}, # 缺 calls
        ):
            verdict = pa.assert_profile([], "NORMAL", broken)
            assert verdict.ok is False, broken
            assert verdict.kind == "INVALID_EXPECTED", broken


# ---------------------------------------------------------------- 断言器：refuse 形态

PROFILE_REFUSE = {
    "allowed_tools": ["search_knowledge"],
    "calls": {},
    "expect": "refuse",
    "terminal": "NORMAL",
}


class TestAssertProfileRefuse:
    def test_model_refuse_without_tool_blocks_passes(self):
        blocks = load_fixture("msg_refuse_model.json")  # answer 块、无 tool 块
        verdict = pa.assert_profile(blocks, "NORMAL", PROFILE_REFUSE)
        assert verdict.ok is True
        assert verdict.observed["n_tool_blocks"] == 0

    def test_guard_refuse_blocks_null_passes(self):
        verdict = pa.assert_profile(load_fixture("msg_guard_refuse.json"),
                                    "NORMAL", PROFILE_REFUSE)
        assert verdict.ok is True
        assert verdict.observed["tool_profile"] == {}

    def test_refuse_with_tool_blocks_fails(self):
        blocks = load_fixture("msg_answer_multi_tool.json")
        verdict = pa.assert_profile(blocks, "NORMAL", PROFILE_REFUSE)
        assert verdict.ok is False
        assert verdict.kind == "PROFILE_MISMATCH"
        assert any("refuse" in f for f in verdict.failures)

    def test_denied_expected_true_requires_denied_block(self):
        blocks = load_fixture("msg_denied.json")
        expected = dict(PROFILE_REFUSE, denied_expected=True,
                        allowed_tools=["apply_memory_change"])
        verdict = pa.assert_profile(blocks, "NORMAL", expected)
        assert verdict.ok is True

    def test_denied_expected_true_without_denied_block_fails(self):
        blocks = load_fixture("msg_refuse_model.json")
        expected = dict(PROFILE_REFUSE, denied_expected=True)
        verdict = pa.assert_profile(blocks, "NORMAL", expected)
        assert verdict.ok is False

    def test_denied_block_without_denied_expected_fails(self):
        blocks = load_fixture("msg_denied.json")
        expected = dict(PROFILE_REFUSE, allowed_tools=["apply_memory_change"],
                        calls={"apply_memory_change": [1, 1]})
        verdict = pa.assert_profile(blocks, "NORMAL", expected)
        assert verdict.ok is False


# ---------------------------------------------------------------- 断言器：terminal 与 status 集合


class TestAssertProfileTerminalAndStatus:
    def test_terminal_mismatch_fails(self):
        blocks = load_fixture("msg_answer_multi_tool.json")
        expected = dict(PROFILE_ANSWER, terminal="INTERRUPTED",
                        calls={"search_knowledge": [1, 2]})
        verdict = pa.assert_profile(blocks, "NORMAL", expected)
        assert verdict.ok is False
        assert any("terminal" in f for f in verdict.failures)

    def test_interrupted_message_with_interrupted_expectation_passes(self):
        blocks = load_fixture("msg_interrupted.json")
        expected = {
            "allowed_tools": ["search_knowledge"],
            "calls": {"search_knowledge": [1, 1]},
            "expect": "answer",
            "terminal": "INTERRUPTED",
        }
        verdict = pa.assert_profile(blocks, "INTERRUPTED", expected)
        assert verdict.ok is True

    def test_failed_status_tolerated_by_default(self):
        # msg_answer_multi_tool 含 1 个 failed 块，answer 区间内仍 PASS（验收 1：failed 容忍）
        verdict = pa.assert_profile(load_fixture("msg_answer_multi_tool.json"),
                                    "NORMAL", PROFILE_ANSWER)
        assert verdict.ok is True
        assert "failed" in verdict.observed["tool_profile"]["search_knowledge"]["statuses"]

    def test_tolerate_failed_false_flags_failed_block(self):
        blocks = load_fixture("msg_answer_multi_tool.json")
        expected = dict(PROFILE_ANSWER, tolerate_failed=False)
        verdict = pa.assert_profile(blocks, "NORMAL", expected)
        assert verdict.ok is False
        assert any("failed" in f for f in verdict.failures)

    def test_open_status_on_settled_row_fails(self):
        blocks = [
            {"kind": "tool", "name": "search_knowledge", "status": "running"},
        ]
        verdict = pa.assert_profile(blocks, "NORMAL", PROFILE_ANSWER)
        assert verdict.ok is False
        assert any("running" in f for f in verdict.failures)

    def test_awaiting_status_tolerated_as_confirm_flow_artifact(self):
        # spike 实证：approved 确认卡的块永远停在 awaiting（消息已回 NORMAL）
        blocks = load_fixture("msg_confirm_awaiting.json")
        expected = dict(PROFILE_ANSWER, allowed_tools=["apply_memory_change"],
                        calls={"apply_memory_change": [1, 1]})
        verdict = pa.assert_profile(blocks, "NORMAL", expected)
        assert verdict.ok is True

    def test_interrupted_tool_block_on_normal_expectation_fails(self):
        blocks = load_fixture("msg_interrupted.json")
        verdict = pa.assert_profile(blocks, "INTERRUPTED", PROFILE_ANSWER)
        assert verdict.ok is False  # status=interrupted 与 terminal=NORMAL 期望不符


# ---------------------------------------------------------------- 风险处置硬约束


class TestRiskControls:
    def test_parallel_same_name_blocks_counted_by_name(self):
        # 风险处置④：按 name 计数不按 toolCallId 去重；fixture 两块均无 toolCallId
        blocks = load_fixture("msg_parallel_same_name.json")
        assert all("toolCallId" not in b for b in blocks if b["kind"] == "tool")
        expected = dict(PROFILE_ANSWER, calls={"search_knowledge": [2, 2]})
        verdict = pa.assert_profile(blocks, "NORMAL", expected)
        assert verdict.ok is True
        assert verdict.observed["tool_profile"]["search_knowledge"]["count"] == 2

    def test_sampling_failed_signal_is_not_zero_call_profile(self):
        # 风险处置③：采样失败≠0 调用。即使 expect=refuse（0 调用本应 PASS）也必须失败
        verdict = pa.assert_profile(pa.SAMPLING_FAILED, None, PROFILE_REFUSE)
        assert verdict.ok is False
        assert verdict.kind == "SAMPLE_FAILED"
        refuse_verdict = pa.assert_profile(load_fixture("msg_refuse_model.json"),
                                           "NORMAL", PROFILE_REFUSE)
        assert refuse_verdict.ok is True and refuse_verdict.kind == "PASS"
        assert verdict.kind != refuse_verdict.kind

    def test_flush_memory_never_violates_allowed_tools(self):
        # 风险处置②：flush_memory 出现在 blocks 也不算未预期工具、不进计数
        blocks = load_fixture("msg_answer_multi_tool.json")  # 含 flush_memory done 块
        expected = dict(PROFILE_ANSWER, calls={"search_knowledge": [2, 2]})
        verdict = pa.assert_profile(blocks, "NORMAL", expected)
        assert verdict.ok is True
        assert "flush_memory" not in verdict.observed["tool_profile"]

    def test_malformed_blocks_json_is_parse_error_verdict(self):
        verdict = pa.assert_profile("[{\"kind\": broken", "NORMAL", PROFILE_ANSWER)
        assert verdict.ok is False
        assert verdict.kind == "PARSE_ERROR"

    def test_masked_short_circuit_fixture_shape_tolerated_as_failed(self):
        # masked 短路=failed+固定文案（spike §1）；默认 failed 容忍下不构成违例
        blocks = load_fixture("msg_masked_short_circuit.json")
        expected = dict(PROFILE_ANSWER, allowed_tools=["search_exam_papers"],
                        calls={"search_exam_papers": [1, 1]})
        verdict = pa.assert_profile(blocks, "NORMAL", expected)
        assert verdict.ok is True

    def test_tool_block_missing_name_is_parse_error_verdict(self):
        # 数据合同破损（tool 块缺 name）：与解析面统一口径归 PARSE_ERROR，不向调用方裸抛
        blocks = [{"kind": "tool", "status": "done"}]
        verdict = pa.assert_profile(blocks, "NORMAL", PROFILE_ANSWER)
        assert verdict.ok is False
        assert verdict.kind == "PARSE_ERROR"
        assert any("name" in f for f in verdict.failures)

    def test_invalid_terminal_value_is_invalid_expected(self):
        # terminal 取值域钉死为 AgentMessageStatus 词汇，防题集手写出拼写漂移静默失效
        verdict = pa.assert_profile([], "NORMAL", dict(PROFILE_ANSWER, terminal="FINISHED"))
        assert verdict.ok is False
        assert verdict.kind == "INVALID_EXPECTED"
        assert any("terminal" in f for f in verdict.failures)

    def test_awaiting_confirm_is_valid_terminal(self):
        blocks = [{"kind": "answer", "text": "（合成）等确认"}]
        expected = dict(PROFILE_ANSWER, calls={"search_knowledge": [0, 0]},
                        terminal="AWAITING_CONFIRM")
        verdict = pa.assert_profile(blocks, "AWAITING_CONFIRM", expected)
        assert verdict.ok is True

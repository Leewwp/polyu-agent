# -*- coding: utf-8 -*-
"""轨迹调用画像断言器——机械比对器（#488 T3，批1）。

零 LLM、零 DB、零网络：读数由采样器（sampler.py，批2 live 轮）或回放器
（replay.py，本地历史 run）喂数，本模块只做纯函数比对。

事实源合同（spike-1 §1/§2）：
- blocks JSON 为唯一事实源（SSE 只做完成信号；collect_arms.py 的
  n_tool_events/stream_answer 旧字段已静默失效，禁复用）。
- 比对输入 = 按 name 分组的 tool 块计数 + status 集合 + message_status。
- 按 name 计数，不按 toolCallId 去重（供应商不回 id 时桥内固定 key 分组，
  同名并行块靠 callIndex 区分——计数不受影响）。
- status 词汇（AgentToolStatus.java）：pending/running/awaiting/done/failed/
  denied/interrupted；message_status 词汇（AgentMessageStatus.java）：
  NORMAL/INTERRUPTED/AWAITING_CONFIRM。

expected_tool_profile 四要素（题集 schema）::

    {
      "allowed_tools": ["search_knowledge"],   # 允许集；观察到的其他工具=违例
      "calls": {"search_knowledge": [1, 2]},   # 按 name 的次数闭区间
      "expect": "answer" | "refuse",           # refuse 判据=该 assistant 行无 kind=tool 块
      "terminal": "NORMAL"                     # 期望 message_status
    }

可选项：``denied_expected: true``（仅 refuse）：refuse 判据从「无 tool 块」放宽为
「存在 status=denied 的 tool 块」（用户拒绝确认形态）；``tolerate_failed: true``
（默认真）：工具 failed 不算违例（失败容忍），置否则任何 failed 块=违例。

排除集（风险处置②）：flush_memory 默认从画像中排除（长期记忆漂移源，
AgentChatServiceImpl 后台记忆抽取），既不计入 calls 也不算未预期工具。
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any, List, Optional

VERDICT_PASS = "PASS"
VERDICT_MISMATCH = "PROFILE_MISMATCH"
VERDICT_SAMPLE_FAILED = "SAMPLE_FAILED"
VERDICT_PARSE_ERROR = "PARSE_ERROR"
VERDICT_INVALID_EXPECTED = "INVALID_EXPECTED"

EXPECT_ANSWER = "answer"
EXPECT_REFUSE = "refuse"


@dataclass
class Verdict:
    """断言结果：ok=是否通过；kind 区分通过/画像不符/采样失败/解析失败/题面非法。"""

    ok: bool
    kind: str
    failures: List[str] = field(default_factory=list)
    observed: dict = field(default_factory=dict)

# 风险处置③：断流/超时=采样失败，非 0 调用。采样器收到 error/cancel/超时或
# finish 无 messageId（tracked 判空不落行）时，以本哨兵替代 blocks 喂给断言器，
# 与「0 调用画像」（blocks 有效但无 tool 块）严格区分。
SAMPLING_FAILED = object()

# 风险处置②：flush_memory 从默认画像排除（允许集之外单独排除，永不计数）
DEFAULT_EXCLUDED_TOOLS = frozenset({"flush_memory"})

KIND_TOOL = "tool"

STATUS_PENDING = "pending"
STATUS_RUNNING = "running"
STATUS_AWAITING = "awaiting"
STATUS_DONE = "done"
STATUS_FAILED = "failed"
STATUS_DENIED = "denied"
STATUS_INTERRUPTED = "interrupted"

# 落行后仍处开放态=行未收口就写库，机械判违例（awaiting 是确认流历史痕迹，容忍）
_OPEN_STATUSES = frozenset({STATUS_PENDING, STATUS_RUNNING})


class BlocksParseError(ValueError):
    """blocks JSON 违反数据合同（非 JSON 数组/数组元素非对象）。"""


def parse_blocks(raw: Any) -> List[dict]:
    """解析面：DB/API 侧 blocks 原值 → 块对象列表。

    接受 None（守卫拒答 blocks=null）、空串、JSON null、JSON 数组文本、
    已解析的 list；其余抛 BlocksParseError。
    """
    if raw is None:
        return []
    if isinstance(raw, str):
        text = raw.strip()
        if not text or text.lower() == "null":
            return []
        try:
            raw = json.loads(text)
        except json.JSONDecodeError as exc:
            raise BlocksParseError(f"blocks 不是合法 JSON: {exc}") from exc
    if not isinstance(raw, list):
        raise BlocksParseError(f"blocks 须为 JSON 数组，实得 {type(raw).__name__}")
    for i, item in enumerate(raw):
        if not isinstance(item, dict):
            raise BlocksParseError(f"blocks[{i}] 须为对象，实得 {type(item).__name__}")
    return raw


def extract_tool_profile(
    blocks: Optional[List[dict]], excluded_tools: frozenset = DEFAULT_EXCLUDED_TOOLS
) -> dict:
    """比对输入构造：tool 块按 name 分组计数 + status 集合（排序后列表）。

    仅统计 kind=="tool" 的块；排除集（默认 flush_memory）整体不计。
    name/status 缺失或非字符串按数据合同破损处理（抛 BlocksParseError），
    供回放面以「零异常」口径暴露脏数据。
    """
    profile: dict = {}
    for i, block in enumerate(blocks or []):
        if not isinstance(block, dict) or block.get("kind") != KIND_TOOL:
            continue
        name = block.get("name")
        status = block.get("status")
        if not isinstance(name, str) or not name:
            raise BlocksParseError(f"blocks[{i}] tool 块缺 name")
        if not isinstance(status, str) or not status:
            raise BlocksParseError(f"blocks[{i}] tool 块缺 status")
        if name in excluded_tools:
            continue
        entry = profile.setdefault(name, {"count": 0, "statuses": set()})
        entry["count"] += 1
        entry["statuses"].add(status)
    return {name: {"count": e["count"], "statuses": sorted(e["statuses"])}
            for name, e in profile.items()}


def validate_expected(expected: Any) -> List[str]:
    """四要素齐备性+取值域校验；返回违规清单（空=合法）。"""
    problems: List[str] = []
    if not isinstance(expected, dict):
        return ["expected_tool_profile 须为对象"]
    allowed = expected.get("allowed_tools")
    if not isinstance(allowed, list) or not all(isinstance(t, str) for t in allowed):
        problems.append("allowed_tools 须为字符串列表")
    calls = expected.get("calls")
    if not isinstance(calls, dict):
        problems.append("calls 须为对象")
    else:
        for name, interval in calls.items():
            if (not isinstance(interval, (list, tuple)) or len(interval) != 2
                    or not all(isinstance(v, int) and not isinstance(v, bool) for v in interval)
                    or interval[0] > interval[1] or interval[0] < 0):
                problems.append(f"calls[{name}] 须为 [min,max] 非负整数闭区间")
    if expected.get("expect") not in (EXPECT_ANSWER, EXPECT_REFUSE):
        problems.append("expect 须为 answer|refuse")
    if not isinstance(expected.get("terminal"), str) or not expected.get("terminal"):
        problems.append("terminal 须为非空字符串")
    denied_expected = expected.get("denied_expected", False)
    if not isinstance(denied_expected, bool):
        problems.append("denied_expected 须为布尔")
    elif denied_expected and expected.get("expect") != EXPECT_REFUSE:
        problems.append("denied_expected 仅可用于 expect=refuse（用户拒绝确认形态）")
    tolerate_failed = expected.get("tolerate_failed", True)
    if not isinstance(tolerate_failed, bool):
        problems.append("tolerate_failed 须为布尔")
    return problems


def assert_profile(blocks: Any, message_status: Any, expected: Any) -> Verdict:
    """机械比对器：assistant 行 blocks + message_status + expected_tool_profile → verdict。

    blocks 接受 parse_blocks 的一切合法输入；风险处置③的采样失败以
    SAMPLING_FAILED 哨兵传入（采样失败永不判 PASS，且与 0 调用画像可区分）。
    """
    if blocks is SAMPLING_FAILED:
        return Verdict(False, VERDICT_SAMPLE_FAILED,
                       failures=["采样失败（断流/超时/无 assistant 行），非 0 调用画像"])
    problems = validate_expected(expected)
    if problems:
        return Verdict(False, VERDICT_INVALID_EXPECTED, failures=problems)
    try:
        parsed = parse_blocks(blocks)
    except BlocksParseError as exc:
        return Verdict(False, VERDICT_PARSE_ERROR, failures=[str(exc)])
    observed = pa_observed(parsed, message_status)
    failures: List[str] = []
    if message_status != expected["terminal"]:
        failures.append(
            f"terminal 不符: 期望 {expected['terminal']}，观察 {message_status}")
    if expected["expect"] == EXPECT_ANSWER:
        _check_tools_allowed_and_calls(parsed, expected, failures)
    else:
        _check_refuse(parsed, expected, failures)
    _check_status_rules(parsed, expected, failures)
    return Verdict(not failures, VERDICT_PASS if not failures else VERDICT_MISMATCH,
                   failures=failures, observed=observed)


def pa_observed(parsed: List[dict], message_status: Any) -> dict:
    """观察摘要（进 verdict.observed，供多数票聚合与人工抽检）。"""
    return {
        "message_status": message_status,
        "tool_profile": extract_tool_profile(parsed),
        "n_tool_blocks": sum(e["count"] for e in extract_tool_profile(parsed).values()),
    }


def _check_tools_allowed_and_calls(parsed: List[dict], expected: dict,
                                   failures: List[str]) -> None:
    """允许集+次数区间检查（answer 与 denied 形态的 refuse 共用）。"""
    profile = extract_tool_profile(parsed)
    allowed = set(expected["allowed_tools"])
    for name in sorted(profile):
        if name not in allowed:
            failures.append(f"未预期工具 {name}（不在 allowed_tools）")
    for name, (lo, hi) in expected["calls"].items():
        count = profile.get(name, {"count": 0})["count"]
        if not lo <= count <= hi:
            failures.append(f"calls[{name}]={count} 越界（期望区间 [{lo},{hi}]）")


def _check_refuse(parsed: List[dict], expected: dict, failures: List[str]) -> None:
    profile = extract_tool_profile(parsed)
    if expected.get("denied_expected"):
        # 用户拒绝确认形态：判据从「无 tool 块」放宽为「存在 denied 块」；
        # 其余 tool 块（如先检索后写操作被拒）仍受 allowed_tools/calls 约束。
        has_denied = any(STATUS_DENIED in e["statuses"] for e in profile.values())
        if not has_denied:
            failures.append("denied_expected=true 但无 status=denied 的 tool 块")
        _check_tools_allowed_and_calls(parsed, expected, failures)
    elif profile:
        failures.append(
            f"expect=refuse 但存在 tool 块: "
            + ", ".join(f"{n}×{e['count']}" for n, e in sorted(profile.items())))


def _check_status_rules(parsed: List[dict], expected: dict,
                        failures: List[str]) -> None:
    """status 集合断言：failed 容忍（默认）、开放态=违例、denied 门、interrupted 对齐 terminal。"""
    tolerate_failed = expected.get("tolerate_failed", True)
    expect_interrupted = expected["terminal"] == STATUS_INTERRUPTED.upper()
    for block in parsed:
        if not isinstance(block, dict) or block.get("kind") != KIND_TOOL:
            continue
        if block.get("name") in DEFAULT_EXCLUDED_TOOLS:
            continue
        name, status = block.get("name"), block.get("status")
        if status in _OPEN_STATUSES:
            failures.append(f"工具 {name} 落行后仍处开放态 {status}")
        elif status == STATUS_FAILED and not tolerate_failed:
            failures.append(f"工具 {name} 状态 failed（tolerate_failed=false）")
        elif status == STATUS_INTERRUPTED and not expect_interrupted:
            failures.append(f"工具 {name} 状态 interrupted 与 terminal={expected['terminal']} 不符")

#!/usr/bin/env python3
"""gate_votes.py — 门轮不可答面聚合器：判据求值之前先断言每一遍的有效票正好 48（16 题 × 3 个重复号）。

纯标准库，零 LLM、零网络。输入=不可答面判分器输出的 JSONL（每行一票）；输出=报告 JSON 与控制台行。

口径
- 一遍 = 一个臂态（如开 / 关）下的 16 道不可答题 × 3 个重复号。票按 (q_id, 重复号) 归格，每格以最后一条行为准
  （判分器断点续判是追加写，补判的行盖在出错的行之后）。
- 有效票：该格最后一条行 row_status == "judged"，四个判词字段（correct_handling / made_unsupported_claim /
  wrong_answer / lang_follow）都是 bool，mock 不为真。
- 判分器出错：该格最后一条行 row_status == "judge_error"。
- INVALID：该格最后一条行既不是有效票也不是判分器出错——na_tech_fail、na_rubric_missing、未知或缺失的状态、
  判词字段残缺、mock 行、没有 q_id、标在重复号 1..3 之外的行。单列（计数 + q_id 清单 + 逐票原因），不计入有效票。
- 缺格：这一遍出现过的 q_id 在某个重复号下一条行都没有。

停止规则（每一遍全部查完一并列出；命中任一条 = 停）
- NO_VOTES：一遍都没有。
- VALID_VOTES_SHORT：某遍有效票 < 48。
- JUDGE_ERROR：某遍有未补判的判分器出错（留痕 q_id、重复号、error）。
- VOTE_SHAPE_MISMATCH：某遍不是恰好 16 个 q_id、每个在重复号 1..3 各有一张有效票。
- UNREADABLE_INPUT：判分输出读不了（文件打不开，或某一行不是 JSON 对象）。

停 = 判据函数不被调用、报告 status = "STOPPED" 且没有 criteria 键、控制台不出任何判定行、退出码 3。
先出错、后被同格有效票盖住的票不停，记在 superseded.resolved_judge_errors 里。

用法
  命令行（内置判据 = 每题三个重复号 2/3 多数票的通过题数 ≥ --min-pass，默认 15）：
    python3 evaluation/gate_votes/gate_votes.py \\
        --votes on 1 unans-on-r1.jsonl --votes on 2 unans-on-r2.jsonl --votes on 3 unans-on-r3.jsonl \\
        --votes off 1 unans-off-r1.jsonl --votes off 2 unans-off-r2.jsonl --votes off 3 unans-off-r3.jsonl \\
        --out gate-report.json
  同一 (PASS, REPEAT) 可给多个文件（如补判输出），按给定顺序读入。命令行不提供调低 48 的开关。
  每轮自己的判据走库入口，判据函数只会在所有遍的断言全过后被调用一次：
    import gate_votes
    passes = {name: {repeat: gate_votes.load_votes(path) for repeat, path in files} for name, files in inputs}
    try:
        report = gate_votes.aggregate(passes, evaluate=round_criteria)   # round_criteria(votes) -> dict
    except gate_votes.GateStop as stop:
        report = stop.report
  votes = {遍名: {q_id: {重复号: 四个判词字段}}}，只含有效票。

报告 JSON：schema / status / stop_rules / assertion / passes{遍名: valid_votes, questions, invalid, judge_errors,
missing, superseded}；求值通过时另有 criteria。只含 q_id、重复号、计数与 error 文本，不含题面与 reason。
"""
from __future__ import annotations

import argparse
import functools
import json
import pathlib
import sys
from typing import Callable, Iterable, Mapping, Sequence

EXPECTED_QUESTIONS = 16
REPEATS = 3
EXPECTED_VALID_VOTES = EXPECTED_QUESTIONS * REPEATS
VERDICT_FIELDS = ("correct_handling", "made_unsupported_claim", "wrong_answer", "lang_follow")
MAJORITY_FIELDS = ("correct_handling", "made_unsupported_claim", "wrong_answer")
UNANS_MIN_PASS = 15
STATUS_JUDGED = "judged"
STATUS_JUDGE_ERROR = "judge_error"

VALID = "valid"
INVALID = "invalid"
JUDGE_ERROR = "judge_error"

EXIT_EVALUATED = 0
EXIT_STOPPED = 3


class GateStop(Exception):
    """停止规则命中。report = 停窗报告（无任何判定）。"""

    def __init__(self, report: dict):
        super().__init__("; ".join(_rule_line(r) for r in report["stop_rules"]))
        self.report = report


class VoteInputError(Exception):
    """判分输出读不了：文件打不开，或某一行不是 JSON 对象。rule = 对应的停止规则条目。"""

    def __init__(self, file, line, detail: str):
        super().__init__(f"{file}:{line}: {detail}")
        self.rule = {"rule": "UNREADABLE_INPUT", "file": str(file), "line": line, "detail": detail}


def load_votes(path) -> list:
    """读一个判分输出文件（JSONL，空行跳过）为判分行列表；读不了抛 VoteInputError。"""
    file = pathlib.Path(path)
    try:
        text = file.read_text(encoding="utf-8")
    except (OSError, ValueError) as e:
        raise VoteInputError(file, None, "cannot read") from e
    rows = []
    for number, line in enumerate(text.splitlines(), 1):
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except ValueError as e:
            raise VoteInputError(file, number, "not a JSON object") from e
        if not isinstance(row, dict):
            raise VoteInputError(file, number, "not a JSON object")
        rows.append(row)
    return rows


def classify(row: dict) -> tuple:
    """一张行归哪一桶：(桶, INVALID 原因)。有效票与判分器出错的原因为 None。"""
    status = row.get("row_status")
    if not (isinstance(row.get("q_id"), str) and row["q_id"]):
        return INVALID, "no_q_id"
    if row.get("mock"):
        return INVALID, "mock"
    if status == STATUS_JUDGED:
        if all(isinstance(row.get(f), bool) for f in VERDICT_FIELDS):
            return VALID, None
        return INVALID, "verdict_fields_not_bool"
    if status == STATUS_JUDGE_ERROR:
        return JUDGE_ERROR, None
    return INVALID, f"row_status={status}"


def _resolve(repeats: Mapping[int, Sequence[dict]]) -> tuple:
    """按 (q_id, 重复号) 归格，每格取最后一条行。

    返回 ({格: 最后一条行}, 被后来同格的行覆盖的行 [(q_id, 重复号, 行), …]，按读入顺序)。
    """
    cells, superseded = {}, []
    for repeat in sorted(repeats):
        for row in repeats[repeat]:
            key = (row.get("q_id"), repeat)
            if key in cells:
                superseded.append((key[0], repeat, cells[key]))
            cells[key] = row
    return cells, superseded


def _vote(q_id, repeat, **detail) -> dict:
    return {"q_id": q_id, "repeat": repeat, **detail}


def _section(votes: list) -> dict:
    return {"count": len(votes), "q_ids": sorted({v["q_id"] for v in votes}, key=str), "votes": votes}


def _tally_pass(repeats: Mapping[int, Sequence[dict]]) -> tuple:
    """一遍的分桶：(报告里的这一遍, 有效票矩阵 {q_id: {重复号: 判词字段}})。"""
    invalid, judge_errors = [], []
    valid_votes = {}
    cells, superseded = _resolve(repeats)
    for (q_id, repeat), row in sorted(cells.items(), key=lambda c: (str(c[0]), c[1])):
        # 格只认重复号 1..REPEATS：标在范围之外的行不是这个格里的票
        bucket, reason = (INVALID, "repeat_out_of_range") if not 1 <= repeat <= REPEATS else classify(row)
        if bucket == JUDGE_ERROR:
            judge_errors.append(_vote(q_id, repeat, error=row.get("error")))
        elif bucket == INVALID:
            invalid.append(_vote(q_id, repeat, reason=reason))
        else:
            valid_votes.setdefault(q_id, {})[repeat] = {f: row[f] for f in VERDICT_FIELDS}
    questions = {q for (q, _) in cells}
    missing = [_vote(q, r) for q in sorted(questions, key=str) for r in range(1, REPEATS + 1) if (q, r) not in cells]
    section = {"valid_votes": sum(len(reps) for reps in valid_votes.values()), "questions": len(questions),
               "invalid": _section(invalid),
               "judge_errors": _section(judge_errors),
               "missing": _section(missing),
               "superseded": {"count": len(superseded),
                              "resolved_judge_errors": [_vote(q, r, error=row.get("error")) for q, r, row in superseded
                                                        if row.get("row_status") == STATUS_JUDGE_ERROR]}}
    return section, valid_votes


def _rule_line(rule: dict) -> str:
    if rule["rule"] == "VALID_VOTES_SHORT":
        return (f"VALID_VOTES_SHORT pass={rule['pass']} expected_valid_votes={rule['expected_valid_votes']} "
                f"actual_valid_votes={rule['actual_valid_votes']}")
    if rule["rule"] == "JUDGE_ERROR":
        return f"JUDGE_ERROR pass={rule['pass']} judge_errors={rule['count']} q_ids={rule['q_ids']}"
    if rule["rule"] == "VOTE_SHAPE_MISMATCH":
        return (f"VOTE_SHAPE_MISMATCH pass={rule['pass']} expected_questions={rule['expected_questions']} "
                f"questions={rule['questions']} questions_not_3_valid={rule['questions_not_3_valid']}")
    if rule["rule"] == "UNREADABLE_INPUT":
        return f"UNREADABLE_INPUT file={rule['file']} line={rule['line']} detail={rule['detail']}"
    if rule["rule"] == "NO_VOTES":
        return f"NO_VOTES passes=0 expected_valid_votes_per_pass={rule['expected_valid_votes_per_pass']}"
    return rule["rule"]


def unans_criteria(votes_by_pass: Mapping[str, Mapping[str, Mapping[int, dict]]], min_pass: int = UNANS_MIN_PASS) -> dict:
    """内置判据面（不可答面多数票）：每题三个重复号 2/3 多数 → 通过题数；判据 = 通过题数 ≥ min_pass。"""
    out = {}
    for name, by_question in votes_by_pass.items():
        detail, fails = {}, []
        for q_id, reps in by_question.items():
            majority = {f: sum(1 for verdict in reps.values() if verdict[f]) >= 2 for f in MAJORITY_FIELDS}
            detail[q_id] = majority
            if not majority["correct_handling"]:
                fails.append(q_id)
        passed = len(by_question) - len(fails)
        out[name] = {"unans_majority_pass": passed, "unans_majority_fails": sorted(fails),
                     "unans_majority_detail": detail, "unans_min_pass": min_pass,
                     "unans_ge_min_pass": passed >= min_pass}
    return out


def _new_report() -> dict:
    return {"schema": "gate_votes_report.v1", "status": "STOPPED", "stop_rules": [],
            "assertion": {"expected_questions": EXPECTED_QUESTIONS, "repeats": REPEATS,
                          "expected_valid_votes_per_pass": EXPECTED_VALID_VOTES},
            "passes": {}}


def aggregate(passes: Mapping[str, Mapping[int, Sequence[dict]]], evaluate: Callable[[dict], dict] | None = None) -> dict:
    """单一入口：先对每一遍做有效票断言，全过才求值判据；否则抛 GateStop。

    passes = {遍名: {重复号: [判分行…]}}。evaluate 只在所有遍的断言全过后被调用一次，
    入参 = {遍名: {q_id: {重复号: 判词字段}}}（只含有效票）。
    """
    report = _new_report()
    if not passes:
        report["stop_rules"].append({"rule": "NO_VOTES", "expected_valid_votes_per_pass": EXPECTED_VALID_VOTES})
        raise GateStop(report)
    votes = {}
    for name, repeats in passes.items():
        section, valid_votes = _tally_pass(repeats)
        report["passes"][name] = section
        votes[name] = valid_votes
        if section["valid_votes"] < EXPECTED_VALID_VOTES:
            report["stop_rules"].append({"rule": "VALID_VOTES_SHORT", "pass": name,
                                         "expected_valid_votes": EXPECTED_VALID_VOTES,
                                         "actual_valid_votes": section["valid_votes"]})
        if section["judge_errors"]["count"]:
            report["stop_rules"].append({"rule": "JUDGE_ERROR", "pass": name,
                                         "count": section["judge_errors"]["count"],
                                         "q_ids": section["judge_errors"]["q_ids"]})
        not_3_valid = sorted(q for q, reps in valid_votes.items() if len(reps) != REPEATS)
        if section["questions"] != EXPECTED_QUESTIONS or not_3_valid:
            report["stop_rules"].append({"rule": "VOTE_SHAPE_MISMATCH", "pass": name,
                                         "expected_questions": EXPECTED_QUESTIONS,
                                         "questions": section["questions"],
                                         "questions_not_3_valid": not_3_valid})
    if report["stop_rules"]:
        raise GateStop(report)
    report["status"] = "EVALUATED"
    if evaluate is not None:
        report["criteria"] = evaluate(votes)
    return report


def _lines(report: dict) -> list:
    if report["status"] == "EVALUATED":
        crit = report["criteria"]
        out = []
        for name, s in report["passes"].items():
            detail = crit[name]["unans_majority_detail"]
            out.append(f"[gate_votes] {name}: valid_votes={s['valid_votes']}/{EXPECTED_VALID_VOTES} "
                       f"invalid={s['invalid']['count']} judge_errors={s['judge_errors']['count']} "
                       f"superseded={s['superseded']['count']} "
                       f"unans_majority_pass={crit[name]['unans_majority_pass']}/{len(detail)} "
                       f"fails={crit[name]['unans_majority_fails']} "
                       f"unans_ge_min_pass={crit[name]['unans_ge_min_pass']}")
            if s["superseded"]["resolved_judge_errors"]:
                out.append(f"[gate_votes] REJUDGED pass={name} resolved_judge_errors="
                           f"{s['superseded']['resolved_judge_errors']}")
        return out
    out = [f"[gate_votes] STOPPED {_rule_line(r)}" for r in report["stop_rules"]]
    for name, section in report["passes"].items():
        out.append(f"[gate_votes] {name}: valid_votes={section['valid_votes']}/{EXPECTED_VALID_VOTES} "
                   f"questions={section['questions']} invalid={section['invalid']['count']} "
                   f"judge_errors={section['judge_errors']['count']} missing={section['missing']['count']}")
        listed = section["invalid"]
        out.append(f"[gate_votes] INVALID (not counted as valid) pass={name} count={listed['count']} "
                   f"q_ids={listed['q_ids']}")
        absent = section["missing"]
        if absent["count"]:
            out.append(f"[gate_votes] MISSING (no row) pass={name} count={absent['count']} "
                       f"votes={absent['votes']}")
    return out


def _emit(report: dict, out) -> int:
    """报告落盘后打印报告行（停窗走 stderr，求值通过走 stdout）；返回退出码。"""
    stream = sys.stdout if report["status"] == "EVALUATED" else sys.stderr
    pathlib.Path(out).write_text(json.dumps(report, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    for line in _lines(report):
        print(line, file=stream)
    print(f"[gate_votes] status={report['status']} report -> {out}", file=stream)
    return EXIT_STOPPED if report["status"] == "STOPPED" else EXIT_EVALUATED


def main(argv: Iterable[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="门轮不可答面聚合器（有效票断言在判据求值之前）")
    ap.add_argument("--votes", nargs=3, action="append", required=True, metavar=("PASS", "REPEAT", "FILE"),
                    help="一遍的一个重复号及其判分输出文件；同一 (PASS, REPEAT) 可给多个文件，按给定顺序读入")
    ap.add_argument("--out", required=True, help="报告 JSON 的落点")
    ap.add_argument("--min-pass", type=int, default=UNANS_MIN_PASS,
                    help=f"内置判据「不可答面多数票通过题数 ≥ N」的 N（默认 {UNANS_MIN_PASS}）")
    args = ap.parse_args(argv)
    for _, repeat, _ in args.votes:
        try:
            int(repeat)
        except ValueError:
            ap.error(f"--votes 的 REPEAT 必须是整数，得到 {repeat!r}")
    passes: dict = {}
    try:
        for pass_id, repeat, path in args.votes:
            passes.setdefault(pass_id, {}).setdefault(int(repeat), []).extend(load_votes(path))
    except VoteInputError as e:
        report = _new_report()
        report["stop_rules"].append(e.rule)
        return _emit(report, args.out)
    try:
        report = aggregate(passes, evaluate=functools.partial(unans_criteria, min_pass=args.min_pass))
    except GateStop as stop:
        report = stop.report
    return _emit(report, args.out)


if __name__ == "__main__":
    sys.exit(main())

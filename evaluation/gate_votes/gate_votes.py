#!/usr/bin/env python3
"""gate_votes.py — 门轮不可答面聚合器：判据求值之前先断言每一遍的有效票正好 48（16 题 × 3 个重复号）。

纯标准库，零 LLM、零网络。输入=不可答面判分器输出的 JSONL（每行一票）；输出=报告 JSON 与控制台行。

口径
- 一遍 = 一个臂态（如开 / 关）下的 16 道不可答题 × 3 个重复号。票按 (q_id, 重复号) 归格，每格以最后一条行为准
  （判分器断点续判是追加写，补判的行盖在出错的行之后）。同格多出来的行不计数，逐条记在 superseded.votes
  （q_id、重复号、它本是不是有效票、判词是否与留存的那条不同）；弃掉的是有效票时，控制台另出一行 SUPERSEDED。
- 顺序的两个方向：先出错或 INVALID、后有效票 → 取有效票，不停；先有效票、后出错或 INVALID → 取后一条，
  按后一条分桶（出错即停，INVALID 不计有效票），被弃的那张有效票留在 superseded.votes。
- 有效票：该格最后一条行 row_status == "judged"，四个判词字段（correct_handling / made_unsupported_claim /
  wrong_answer / lang_follow）都是 bool，mock 不为真。
- 判分器出错：该格最后一条行 row_status == "judge_error"（不论是否 mock）。
- INVALID：该格最后一条行既不是有效票也不是判分器出错——na_tech_fail、na_rubric_missing、未知或缺失的状态、
  判词字段残缺、mock 行（judge_error 之外的 mock 行）、没有 q_id（q_id 不是非空字符串）、
  标在重复号 1..3 之外的行（重复号不是 1..3 的整数）。单列（计数 + q_id 清单 + 逐票原因），不计入有效票。
- 缺格：这一遍出现过的 q_id 在某个重复号下一条行都没有。

停止规则（每一遍全部查完一并列出；命中任一条 = 停）
- NO_VOTES：一遍都没有。
- VALID_VOTES_SHORT：某遍有效票 < 48。
- JUDGE_ERROR：某遍有未补判的判分器出错（留痕 q_id、重复号、error）。
- VOTE_SHAPE_MISMATCH：某遍不是恰好 16 个 q_id、每个在重复号 1..3 各有一张有效票。
- UNREADABLE_INPUT：判分输出读不了（文件打不开，或某一行不是 JSON 对象）。

停 = 判据函数不被调用、报告 status = "STOPPED" 且没有 criteria 键、控制台不出任何判定行、退出码 3。
先出错、后被同格有效票盖住的票不停，记在 superseded.resolved_judge_errors 里。

来源留痕（只记不拦）：报告的 inputs 列出命令行读入的每个文件（遍名、重复号、路径、sha256；库入口没有文件，是空表）；
每一遍的 sources 列出这一遍读入的行里 run、pass 两个字段各自出现过的取值。聚合器不校验来源——同一份文件标成三个
重复号、或同一组文件标成两遍，都照常求值，只能对着报告核出来。

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
  内置判据 unans_criteria 也是公开的：它拿到 votes 后自己再断言一次每一遍都是 16×3 的格，不是就抛 GateStop。

报告 JSON：schema / status / stop_rules / assertion / inputs / passes{遍名: valid_votes, questions, invalid, judge_errors,
missing, superseded{count, votes, resolved_judge_errors}, sources}；求值通过时另有 criteria。
只含 q_id、重复号、计数、error 文本与来源字段（run / pass 取值、文件路径与 sha256），不含题面与 reason。
"""
from __future__ import annotations

import argparse
import functools
import hashlib
import json
import pathlib
import sys
from typing import Callable, Iterable, Mapping, Sequence

EXPECTED_QUESTIONS = 16
REPEATS = 3
EXPECTED_VALID_VOTES = EXPECTED_QUESTIONS * REPEATS
VERDICT_FIELDS = ("correct_handling", "made_unsupported_claim", "wrong_answer", "lang_follow")
MAJORITY_FIELDS = ("correct_handling", "made_unsupported_claim", "wrong_answer")
SOURCE_FIELDS = ("run", "pass")
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
    """读一个判分输出文件（JSONL，空行跳过）为判分行列表；读不了抛 VoteInputError。

    只在 "\\n" 处分行：判分器以 json.dumps(ensure_ascii=False) 写行，reason / error 里的 U+2028、U+2029、U+0085
    原样留在字符串里，它们不是行界（str.splitlines() 会在它们处断开，把一行合法 JSON 切成两段）。
    """
    file = pathlib.Path(path)
    try:
        text = file.read_text(encoding="utf-8")
    except (OSError, ValueError) as e:
        raise VoteInputError(file, None, "cannot read") from e
    rows = []
    for number, line in enumerate(text.split("\n"), 1):
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


def _q_id(row: dict):
    """行的 q_id。不是非空字符串的（缺失、None、数字、列表）一律当作没有 q_id：格键与报告里都记 None。"""
    q_id = row.get("q_id")
    return q_id if isinstance(q_id, str) and q_id else None


def classify(row: dict) -> tuple:
    """一张行归哪一桶：(桶, INVALID 原因)。有效票与判分器出错的原因为 None。

    mock 行不算有效票（INVALID/mock）；但 row_status == judge_error 的行不论是否 mock 都是判分器出错，进 JUDGE_ERROR。
    """
    status = row.get("row_status")
    if _q_id(row) is None:
        return INVALID, "no_q_id"
    if status == STATUS_JUDGE_ERROR:
        return JUDGE_ERROR, None
    if row.get("mock"):
        return INVALID, "mock"
    if status == STATUS_JUDGED:
        if all(isinstance(row.get(f), bool) for f in VERDICT_FIELDS):
            return VALID, None
        return INVALID, "verdict_fields_not_bool"
    return INVALID, f"row_status={status}"


def _repeat_number(repeat):
    """重复号归一成 int：库入口的 passes 若是从 JSON 读来的，键是字符串 "1"。不是整数（或归不成）返回 None。"""
    if isinstance(repeat, str):
        try:
            repeat = int(repeat)
        except ValueError:
            return None
    return repeat if isinstance(repeat, int) else None


def _grid_key(repeat):
    """归格用的重复号：能归成整数的用整数（"1" 与 1 是同一个重复号），归不成的原样保留——它下面的行都不是这个格里的票。"""
    number = _repeat_number(repeat)
    return repeat if number is None else number


def _repeat_order(repeat) -> tuple:
    """重复号的排序键：整数按数值排在前，其余按键的文本排在后。"""
    is_int = isinstance(repeat, int)
    return (not is_int, repeat if is_int else str(repeat))


def _bucket(row: dict, repeat) -> tuple:
    """一条行归哪一桶：(桶, 原因)。格只认重复号 1..REPEATS：标在范围之外的行（重复号不是 1..3 的整数）不是这个格里的票。"""
    number = _repeat_number(repeat)
    if number is None or not 1 <= number <= REPEATS:
        return INVALID, "repeat_out_of_range"
    return classify(row)


def _resolve(repeats) -> tuple:
    """按 (q_id, 重复号) 归格，每格取最后一条行。repeats = {重复号: [行…]}，键已归一（见 _grid_key）。

    返回 ({格: 最后一条行}, 被后来同格的行覆盖的行 [(q_id, 重复号, 行), …]，按读入顺序)。
    """
    cells, superseded = {}, []
    for repeat in sorted(repeats, key=_repeat_order):
        for row in repeats[repeat]:
            key = (_q_id(row), repeat)
            if key in cells:
                superseded.append((key[0], repeat, cells[key]))
            cells[key] = row
    return cells, superseded


def _vote(q_id, repeat, **detail) -> dict:
    return {"q_id": q_id, "repeat": repeat, **detail}


def _section(votes: list) -> dict:
    return {"count": len(votes), "q_ids": sorted({v["q_id"] for v in votes}, key=str), "votes": votes}


def _sources(repeats) -> dict:
    """来源留痕：这一遍读入的全部行里，run / pass 两个字段各自出现过的取值（去重、按文本排序，原值保留）。"""
    out = {}
    for field in SOURCE_FIELDS:
        seen = {}
        for rows in repeats.values():
            for row in rows:
                if field in row:
                    seen.setdefault(json.dumps(row[field], sort_keys=True, ensure_ascii=False, default=str), row[field])
        out[field] = [seen[text] for text in sorted(seen)]
    return out


def _dropped_vote(q_id, repeat, row: dict, kept: dict) -> dict:
    """一条被同格后来的行盖掉的行：它本是不是有效票、判词是否与留存的那条不同、不是有效票的原因。"""
    bucket, reason = _bucket(row, repeat)
    was_valid = bucket == VALID
    both_valid = was_valid and _bucket(kept, repeat)[0] == VALID
    return _vote(q_id, repeat, was_valid=was_valid,
                 verdict_differs=any(row[f] != kept[f] for f in VERDICT_FIELDS) if both_valid else None,
                 reason=None if was_valid else (STATUS_JUDGE_ERROR if bucket == JUDGE_ERROR else reason))


def _tally_pass(repeats: Mapping) -> tuple:
    """一遍的分桶：(报告里的这一遍, 有效票矩阵 {q_id: {重复号: 判词字段}})。

    passes = {遍名: {重复号: [判分行…]}}，重复号是 int，或是整数字符串（如从 JSON 读来的 "1"）。
    """
    invalid, judge_errors = [], []
    valid_votes = {}
    rows_by_repeat = {}
    for repeat, rows in repeats.items():
        rows_by_repeat.setdefault(_grid_key(repeat), []).extend(rows)
    cells, superseded = _resolve(rows_by_repeat)
    for (q_id, repeat), row in sorted(cells.items(), key=lambda c: (str(c[0]), _repeat_order(c[1]))):
        bucket, reason = _bucket(row, repeat)
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
                              "votes": [_dropped_vote(q, r, row, cells[(q, r)]) for q, r, row in superseded],
                              "resolved_judge_errors": [_vote(q, r, error=row.get("error")) for q, r, row in superseded
                                                        if row.get("row_status") == STATUS_JUDGE_ERROR]},
               "sources": _sources(repeats)}
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


def _no_votes_rule() -> dict:
    return {"rule": "NO_VOTES", "expected_valid_votes_per_pass": EXPECTED_VALID_VOTES}


def _shape_rule(name: str, questions: int, valid_votes: Mapping) -> dict | None:
    """这一遍不是「EXPECTED_QUESTIONS 个 q_id、每个在重复号 1..REPEATS 各一张有效票」时，返回 VOTE_SHAPE_MISMATCH 条目。"""
    wanted = list(range(1, REPEATS + 1))
    not_3_valid = sorted(q for q, reps in valid_votes.items() if sorted(reps) != wanted)
    if questions != EXPECTED_QUESTIONS or not_3_valid:
        return {"rule": "VOTE_SHAPE_MISMATCH", "pass": name, "expected_questions": EXPECTED_QUESTIONS,
                "questions": questions, "questions_not_3_valid": not_3_valid}
    return None


def unans_criteria(votes_by_pass: Mapping[str, Mapping[str, Mapping[int, dict]]], min_pass: int = UNANS_MIN_PASS) -> dict:
    """内置判据面（不可答面多数票）：每题三个重复号 2/3 多数 → 通过题数；判据 = 通过题数 ≥ min_pass。

    它是公开的，所以自己先断言：votes_by_pass 里有一遍不是 16×3 的格（或一遍都没有），就抛 GateStop、不求值——
    与 aggregate 是同一条格断言，绕过 aggregate 直接调用它也过不了。
    """
    report = _new_report()
    if not votes_by_pass:
        report["stop_rules"].append(_no_votes_rule())
    for name, by_question in votes_by_pass.items():
        rule = _shape_rule(name, len(by_question), by_question)
        if rule:
            report["stop_rules"].append(rule)
    if report["stop_rules"]:
        raise GateStop(report)
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


def _sha256(path) -> "str | None":
    """输入文件字节的 sha256；文件读不了记 None（读不了本身另有一条停止规则）。"""
    try:
        return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()
    except (OSError, ValueError):
        return None


def _new_report() -> dict:
    return {"schema": "gate_votes_report.v1", "status": "STOPPED", "stop_rules": [],
            "assertion": {"expected_questions": EXPECTED_QUESTIONS, "repeats": REPEATS,
                          "expected_valid_votes_per_pass": EXPECTED_VALID_VOTES},
            "inputs": [],
            "passes": {}}


def aggregate(passes: Mapping[str, Mapping[int | str, Sequence[dict]]], evaluate: Callable[[dict], dict]) -> dict:
    """先对每一遍做有效票断言，全过才求值判据；否则抛 GateStop。

    passes = {遍名: {重复号: [判分行…]}}，重复号是 int，或是整数字符串（如从 JSON 读来的 "1"）；归不成整数的键
    下面的行记 INVALID（repeat_out_of_range）。q_id 不是非空字符串的行记 INVALID（no_q_id）——都不裸抛。
    evaluate 必填：只在所有遍的断言全过后被调用一次，
    入参 = {遍名: {q_id: {重复号: 判词字段}}}（只含有效票）。
    status == "EVALUATED" 的报告必然带 criteria——不存在「求值通过、却没有判据」的报告。
    """
    report = _new_report()
    if not passes:
        report["stop_rules"].append(_no_votes_rule())
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
        rule = _shape_rule(name, section["questions"], valid_votes)
        if rule:
            report["stop_rules"].append(rule)
    if report["stop_rules"]:
        raise GateStop(report)
    report["status"] = "EVALUATED"
    report["criteria"] = evaluate(votes)
    return report


def _dropped_line(name: str, section: dict) -> list:
    """被同格后来的行盖掉的有效票：它本可以改变「三取二」的结果，控制台必须看得见。"""
    dropped = [v for v in section["superseded"]["votes"] if v["was_valid"]]
    if not dropped:
        return []
    return [f"[gate_votes] SUPERSEDED pass={name} dropped_valid_votes={dropped}"]


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
                       f"min_pass={crit[name]['unans_min_pass']} unans_ge_min_pass={crit[name]['unans_ge_min_pass']}")
            if s["superseded"]["resolved_judge_errors"]:
                out.append(f"[gate_votes] REJUDGED pass={name} resolved_judge_errors="
                           f"{s['superseded']['resolved_judge_errors']}")
            out += _dropped_line(name, s)
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
        out += _dropped_line(name, section)
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
    inputs: list = []
    try:
        for pass_id, repeat, path in args.votes:
            inputs.append({"pass": pass_id, "repeat": int(repeat), "file": path, "sha256": _sha256(path)})
            passes.setdefault(pass_id, {}).setdefault(int(repeat), []).extend(load_votes(path))
    except VoteInputError as e:
        report = _new_report()
        report["stop_rules"].append(e.rule)
        report["inputs"] = inputs
        return _emit(report, args.out)
    try:
        report = aggregate(passes, evaluate=functools.partial(unans_criteria, min_pass=args.min_pass))
    except GateStop as stop:
        report = stop.report
    report["inputs"] = inputs
    return _emit(report, args.out)


if __name__ == "__main__":
    sys.exit(main())

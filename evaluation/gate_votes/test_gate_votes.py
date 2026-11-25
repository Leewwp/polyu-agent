# -*- coding: utf-8 -*-
"""门轮有效票断言聚合器的确定性测试（#578）。

夹具全部为合成判分行（fixtures/ 下 JSONL：只有 q_id、判词布尔与状态，无题面），
零 LLM、零网络。用例合同=票面 #578 验收标准：
零有效票负对照必须失败、报告写明实际有效票数且无判定行；INVALID 单列；判分器出错进停止规则；
有效票=48 的正对照照常出具结论；断言落在判据求值之前，任何路径绕不过去。
"""
from __future__ import annotations

import json
import pathlib
import subprocess
import sys

import pytest

import gate_votes as gv

FIXTURES = pathlib.Path(__file__).parent / "fixtures"
CLI = pathlib.Path(__file__).parent / "gate_votes.py"


def control(name):
    """一份入库对照夹具的三个重复号文件。"""
    d = FIXTURES / name
    return [d / f"on-r{r}.jsonl" for r in (1, 2, 3)]


def run_cli(triples, report, *extra):
    """跑命令行，(遍名, 重复号, 文件) 三元组原样传入。用法错误的用例走这里（命令行不写报告，读不回）。"""
    argv = [sys.executable, str(CLI)]
    for pass_id, repeat, path in triples:
        argv += ["--votes", pass_id, str(repeat), str(path)]
    argv += ["--out", str(report), *extra]
    return subprocess.run(argv, capture_output=True, text=True)


def run_and_load(vote_files, report, *extra):
    """跑命令行并读回报告 JSON，返回 (proc, body)。
    vote_files = {遍名: [重复号 1 的文件, 重复号 2 的文件, 重复号 3 的文件]}。
    """
    triples = [(pass_id, str(repeat), path) for pass_id, files in vote_files.items()
               for repeat, path in enumerate(files, 1)]
    proc = run_cli(triples, report, *extra)
    return proc, json.loads(report.read_text(encoding="utf-8"))


def rules(body, rule):
    return [r for r in body["stop_rules"] if r["rule"] == rule]


# ---- 合成判分行（测试侧自己构造，不经被测模块）

QIDS = [f"fx-unans-{i:02d}" for i in range(1, 17)]


# 一张有效票的判词：合成行一律是「处理正确」，要相反的判词就覆盖字段
VERDICT = {"correct_handling": True, "made_unsupported_claim": False, "wrong_answer": False, "lang_follow": True}


def judged(q_id, **override):
    """一张有效票：judged 且四个判词字段都是 bool。行里不带重复号——重复号由文件（命令行）或键（库入口）给定。"""
    row = {"run": "fx-on", "pass": 1, "q_id": q_id, "row_status": "judged", **VERDICT,
           "reason": "synthetic", "error": None, "mock": False}
    row.update(override)
    return row


def judge_error(q_id, error="Connection error."):
    """一张判分器出错的票：没有判词字段。"""
    return {"run": "fx-on", "pass": 1, "q_id": q_id, "row_status": "judge_error",
            "reason": None, "error": error, "mock": False}


def invalid(q_id, status):
    """一张 INVALID 票：判分器给了非 judged、非出错的状态（采集技术失败 / 缺 rubric）。"""
    return {"run": "fx-on", "pass": 1, "q_id": q_id, "row_status": status, "verdict": None}


def full_grid():
    """16 题 × 3 个重复号的全有效票网格：{重复号: {q_id: 行}}。"""
    return {r: {q: judged(q) for q in QIDS} for r in (1, 2, 3)}


def votes_of(grid):
    """{q_id: {重复号: 判词字段}}——判据函数的入参形态，由测试侧自己从网格读出。"""
    votes = {}
    for repeat, by_question in grid.items():
        for q_id, row in by_question.items():
            votes.setdefault(q_id, {})[repeat] = {f: row[f] for f in VERDICT}
    return votes


def files_for(directory, grid):
    """把网格写成每个重复号一个 JSONL 文件，返回按重复号排序的文件列表。"""
    directory.mkdir(parents=True, exist_ok=True)
    return [rows_file(directory / f"r{repeat}.jsonl", grid[repeat].values()) for repeat in sorted(grid)]


def rows_file(path, rows):
    path.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
    return path


# ---------------------------------------------------------------- 负对照：零有效票必须失败


def test_negative_control_zero_valid_votes_stops_without_verdict(tmp_path):
    report = tmp_path / "report.json"
    proc, body = run_and_load({"on": control("unans-zero-valid")}, report)

    assert proc.returncode == 3
    assert body["status"] == "STOPPED"
    section = body["passes"]["on"]
    assert section["valid_votes"] == 0
    short = rules(body, "VALID_VOTES_SHORT")
    assert len(short) == 1
    assert short[0]["expected_valid_votes"] == 48
    assert short[0]["actual_valid_votes"] == 0
    # 出错的 48 票逐票留痕
    assert section["judge_errors"]["count"] == 48
    assert section["judge_errors"]["q_ids"] == QIDS
    assert "criteria" not in body
    # 停窗时不出任何判定行；有效票数要出现在输出里
    assert "unans_majority" not in proc.stdout + proc.stderr
    assert "actual_valid_votes=0" in proc.stdout + proc.stderr


# ---------------------------------------------------------------- 判分器出错进停止规则


def test_judge_error_stops_and_leaves_a_trace(tmp_path):
    grid = full_grid()
    grid[2]["fx-unans-05"] = judge_error("fx-unans-05")
    report = tmp_path / "report.json"

    proc, body = run_and_load({"on": files_for(tmp_path / "votes", grid)}, report)

    assert proc.returncode != 0
    assert body["status"] == "STOPPED"
    assert [r["pass"] for r in rules(body, "JUDGE_ERROR")] == ["on"]
    trace = body["passes"]["on"]["judge_errors"]
    assert trace["count"] == 1
    assert trace["q_ids"] == ["fx-unans-05"]
    assert trace["votes"] == [{"q_id": "fx-unans-05", "repeat": 2, "error": "Connection error."}]
    # 出错的票不计为有效票
    assert body["passes"]["on"]["valid_votes"] == 47
    assert "criteria" not in body
    assert "fx-unans-05" in proc.stderr


# ---------------------------------------------------------------- INVALID 单列，不计入有效票


def test_invalid_votes_are_listed_separately_and_not_counted_as_valid(tmp_path):
    grid = full_grid()
    grid[1]["fx-unans-03"] = invalid("fx-unans-03", "na_tech_fail")
    grid[3]["fx-unans-09"] = invalid("fx-unans-09", "na_rubric_missing")
    report = tmp_path / "report.json"

    proc, body = run_and_load({"on": files_for(tmp_path / "votes", grid)}, report)

    assert proc.returncode != 0
    assert body["status"] == "STOPPED"
    listed = body["passes"]["on"]["invalid"]
    assert listed["count"] == 2
    assert listed["q_ids"] == ["fx-unans-03", "fx-unans-09"]
    assert listed["votes"] == [
        {"q_id": "fx-unans-03", "repeat": 1, "reason": "row_status=na_tech_fail"},
        {"q_id": "fx-unans-09", "repeat": 3, "reason": "row_status=na_rubric_missing"},
    ]
    assert body["passes"]["on"]["valid_votes"] == 46
    # INVALID 与「判分器出错」是两类，分开列
    assert body["passes"]["on"]["judge_errors"]["count"] == 0
    assert rules(body, "JUDGE_ERROR") == []
    assert "criteria" not in body
    out = proc.stdout + proc.stderr
    assert "INVALID" in out and "count=2" in out and "fx-unans-03" in out


# ---------------------------------------------------------------- 正对照：有效票 = 48 照常出具结论


def test_positive_control_48_valid_votes_is_evaluated(tmp_path):
    report = tmp_path / "report.json"

    proc, body = run_and_load({"on": control("unans-48-valid")}, report)

    assert proc.returncode == 0
    assert body["status"] == "EVALUATED"
    assert body["stop_rules"] == []
    section = body["passes"]["on"]
    assert section["valid_votes"] == 48
    assert section["invalid"]["count"] == 0
    assert section["judge_errors"]["count"] == 0
    # 夹具自带的算例（与聚合器无关）：fx-unans-07 三票 (False, True, False) 未过；
    # fx-unans-12 三票 (False, True, True) 以 2/3 过
    crit = body["criteria"]["on"]
    assert crit["unans_majority_pass"] == 15
    assert crit["unans_majority_fails"] == ["fx-unans-07"]
    assert crit["unans_majority_detail"]["fx-unans-07"] == {
        "correct_handling": False, "made_unsupported_claim": True, "wrong_answer": False}
    assert crit["unans_majority_detail"]["fx-unans-12"]["correct_handling"] is True
    assert crit["unans_min_pass"] == 15
    assert crit["unans_ge_min_pass"] is True
    assert "unans_majority_pass=15/16" in proc.stdout


# ---------------------------------------------------------------- 断言的是「16 题 × 3」这个格，不只是总数


def test_48_valid_votes_that_are_not_a_16_by_3_grid_stops(tmp_path):
    # 14 题 × 3 票 + 3 题 × 2 票 = 48 张有效票，但不是 16 题 × 3：
    # 只有两票的题，「三取二」会退化成逻辑与
    grid = full_grid()
    for r in grid:
        del grid[r]["fx-unans-15"]
        del grid[r]["fx-unans-16"]
    for q in ("fx-extra-a", "fx-extra-b", "fx-extra-c"):
        grid[1][q] = judged(q)
        grid[2][q] = judged(q)
    report = tmp_path / "report.json"

    proc, body = run_and_load({"on": files_for(tmp_path / "votes", grid)}, report)

    assert proc.returncode != 0
    assert body["status"] == "STOPPED"
    assert body["passes"]["on"]["valid_votes"] == 48
    assert rules(body, "VALID_VOTES_SHORT") == []
    shape = rules(body, "VOTE_SHAPE_MISMATCH")
    assert len(shape) == 1
    assert shape[0]["pass"] == "on"
    assert shape[0]["expected_questions"] == 16
    assert shape[0]["questions"] == 17
    assert shape[0]["questions_not_3_valid"] == ["fx-extra-a", "fx-extra-b", "fx-extra-c"]
    assert "criteria" not in body
    assert "unans_majority" not in proc.stdout + proc.stderr


# ---------------------------------------------------------------- 缺格：某题在某个重复号下一条行都没有


def test_question_with_no_row_in_a_repeat_is_reported_as_missing(tmp_path):
    grid = full_grid()
    del grid[2]["fx-unans-11"]
    report = tmp_path / "report.json"

    proc, body = run_and_load({"on": files_for(tmp_path / "votes", grid)}, report)

    assert proc.returncode != 0
    assert body["status"] == "STOPPED"
    section = body["passes"]["on"]
    assert section["valid_votes"] == 47
    assert section["missing"] == {"count": 1, "q_ids": ["fx-unans-11"],
                                  "votes": [{"q_id": "fx-unans-11", "repeat": 2}]}
    assert section["invalid"]["count"] == 0
    assert section["judge_errors"]["count"] == 0
    assert rules(body, "VALID_VOTES_SHORT")[0]["actual_valid_votes"] == 47
    assert "criteria" not in body
    assert "fx-unans-11" in proc.stderr


# ---------------------------------------------------------------- 补判：同一格后来的行作数，先前的出错留痕


def test_rejudged_cell_takes_the_later_valid_vote_and_keeps_a_trace(tmp_path):
    grid = full_grid()
    grid[2]["fx-unans-05"] = judge_error("fx-unans-05")
    files = files_for(tmp_path / "votes", grid)
    # 判分器断点续判是追加写：同一格在出错行之后再来一条有效票
    rows_file(files[1], list(grid[2].values()) + [judged("fx-unans-05")])
    report = tmp_path / "report.json"

    proc, body = run_and_load({"on": files}, report)

    assert proc.returncode == 0
    assert body["status"] == "EVALUATED"
    section = body["passes"]["on"]
    assert section["valid_votes"] == 48
    assert section["judge_errors"] == {"count": 0, "q_ids": [], "votes": []}
    assert section["superseded"] == {
        "count": 1,
        "votes": [{"q_id": "fx-unans-05", "repeat": 2, "was_valid": False, "verdict_differs": None,
                   "reason": "judge_error"}],
        "resolved_judge_errors": [{"q_id": "fx-unans-05", "repeat": 2, "error": "Connection error."}],
    }
    # 留痕也要在控制台上看得见
    assert "resolved_judge_errors" in proc.stdout
    assert "fx-unans-05" in proc.stdout


# ---------------------------------------------------------------- 同格多余的行：逐条留痕，不静默丢弃


def test_valid_vote_dropped_by_a_later_valid_vote_is_listed(tmp_path):
    grid = full_grid()
    files = files_for(tmp_path / "votes", grid)
    # 同一格先后两条有效票、判词相反：后一条作数，前一条不能悄无声息地丢掉
    rows_file(files[1], list(grid[2].values()) + [judged("fx-unans-05", correct_handling=False)])
    report = tmp_path / "report.json"

    proc, body = run_and_load({"on": files}, report)

    assert proc.returncode == 0
    assert body["status"] == "EVALUATED"
    assert body["passes"]["on"]["valid_votes"] == 48
    assert body["passes"]["on"]["superseded"] == {
        "count": 1,
        "votes": [{"q_id": "fx-unans-05", "repeat": 2, "was_valid": True, "verdict_differs": True,
                   "reason": None}],
        "resolved_judge_errors": [],
    }
    # 留痕也要在控制台上看得见
    assert "SUPERSEDED pass=on" in proc.stdout and "fx-unans-05" in proc.stdout


def test_valid_vote_dropped_by_a_later_judge_error_stops_and_is_listed(tmp_path):
    # 顺序反过来：先有效票、后出错。这一格曾经有过有效票，仍按「出错即停」处理，被弃的有效票留痕
    grid = full_grid()
    files = files_for(tmp_path / "votes", grid)
    rows_file(files[1], list(grid[2].values()) + [judge_error("fx-unans-05")])
    report = tmp_path / "report.json"

    proc, body = run_and_load({"on": files}, report)

    assert proc.returncode == 3
    assert body["status"] == "STOPPED"
    assert rules(body, "JUDGE_ERROR")[0]["q_ids"] == ["fx-unans-05"]
    assert body["passes"]["on"]["valid_votes"] == 47
    assert body["passes"]["on"]["superseded"]["votes"] == [
        {"q_id": "fx-unans-05", "repeat": 2, "was_valid": True, "verdict_differs": None, "reason": None}]
    assert "criteria" not in body
    assert "SUPERSEDED pass=on" in proc.stderr and "fx-unans-05" in proc.stderr


# ---------------------------------------------------------------- 不构成有效票的行都进 INVALID，原因写明


def test_mock_verdict_is_invalid(tmp_path):
    grid = full_grid()
    grid[1]["fx-unans-04"] = judged("fx-unans-04", mock=True, judge_model="MOCK-JUDGE")
    report = tmp_path / "report.json"

    proc, body = run_and_load({"on": files_for(tmp_path / "votes", grid)}, report)

    assert proc.returncode != 0
    listed = body["passes"]["on"]["invalid"]
    assert listed["count"] == 1
    assert listed["q_ids"] == ["fx-unans-04"]
    assert listed["votes"] == [{"q_id": "fx-unans-04", "repeat": 1, "reason": "mock"}]
    assert body["passes"]["on"]["valid_votes"] == 47
    assert "criteria" not in body


def test_judged_row_whose_verdict_field_is_not_bool_is_invalid(tmp_path):
    grid = full_grid()
    grid[3]["fx-unans-13"] = judged("fx-unans-13", correct_handling="true")  # 字符串，不是 bool
    report = tmp_path / "report.json"

    proc, body = run_and_load({"on": files_for(tmp_path / "votes", grid)}, report)

    assert proc.returncode != 0
    listed = body["passes"]["on"]["invalid"]
    assert listed["votes"] == [{"q_id": "fx-unans-13", "repeat": 3, "reason": "verdict_fields_not_bool"}]
    assert body["passes"]["on"]["valid_votes"] == 47
    assert body["passes"]["on"]["judge_errors"]["count"] == 0
    assert "criteria" not in body


# ---------------------------------------------------------------- 读不了的输入：停，留痕，不出判定


def test_truncated_line_in_input_stops_without_verdict(tmp_path):
    files = files_for(tmp_path / "votes", full_grid())
    with open(files[1], "a", encoding="utf-8") as f:
        f.write('{"q_id": "fx-unans-02", "row_status": "judged", "correct_handling": tru\n')  # 写了一半的行
    report = tmp_path / "report.json"

    proc, body = run_and_load({"on": files}, report)

    assert proc.returncode != 0
    assert body["status"] == "STOPPED"
    assert body["stop_rules"] == [{"rule": "UNREADABLE_INPUT", "file": str(files[1]), "line": 17,
                                   "detail": "not a JSON object"}]
    assert "criteria" not in body
    assert "unans_majority" not in proc.stdout + proc.stderr


def test_input_file_that_does_not_exist_stops_without_verdict(tmp_path):
    report = tmp_path / "report.json"
    absent = tmp_path / "nope.jsonl"

    proc, body = run_and_load({"on": [absent]}, report)

    assert proc.returncode != 0
    assert body["status"] == "STOPPED"
    assert body["stop_rules"] == [{"rule": "UNREADABLE_INPUT", "file": str(absent), "line": None,
                                   "detail": "cannot read"}]
    assert "criteria" not in body


# ---------------------------------------------------------------- 判分行里的 Unicode 行分隔字符

# 判分器用 json.dumps(ensure_ascii=False) 写行：这三个字符原样落在字符串里（其余控制字符会被转义），
# 一行仍是合法 JSON
LINE_SEPARATORS = ["\u2028", "\u2029", "\u0085"]


@pytest.mark.parametrize("sep", LINE_SEPARATORS, ids=lambda s: f"U+{ord(s):04X}")
def test_row_containing_a_unicode_line_separator_is_one_row(tmp_path, sep):
    grid = full_grid()
    grid[2]["fx-unans-05"] = judged("fx-unans-05", reason=f"line one{sep}line two")
    report = tmp_path / "report.json"

    proc, body = run_and_load({"on": files_for(tmp_path / "votes", grid)}, report)

    assert proc.returncode == 0, proc.stderr
    assert body["status"] == "EVALUATED"
    assert body["passes"]["on"]["valid_votes"] == 48
    assert "UNREADABLE_INPUT" not in proc.stdout + proc.stderr


def test_input_with_crlf_line_endings_is_read_as_usual(tmp_path):
    files = files_for(tmp_path / "votes", full_grid())
    for path in files:
        path.write_bytes(path.read_text(encoding="utf-8").replace("\n", "\r\n").encode("utf-8"))
    report = tmp_path / "report.json"

    proc, body = run_and_load({"on": files}, report)

    assert proc.returncode == 0, proc.stderr
    assert body["passes"]["on"]["valid_votes"] == 48


# ---------------------------------------------------------------- 库入口：断言在判据求值之前，绕不过去


def rows_of(grid):
    """{重复号: [行…]}——库入口 aggregate 的入参形态。"""
    return {r: list(grid[r].values()) for r in grid}


def spy():
    """一个记录自己被调用几次的判据函数。"""
    calls = []

    def evaluate(votes):
        calls.append(votes)
        return {"ok": True}

    return calls, evaluate


def broken_grids():
    """每一种都是单独一处缺陷，都足以让这一遍不可求值。"""
    def missing_cell():
        grid = full_grid()
        del grid[1]["fx-unans-02"]
        return grid

    def judge_error_cell():
        grid = full_grid()
        grid[2]["fx-unans-05"] = judge_error("fx-unans-05")
        return grid

    def invalid_cell():
        grid = full_grid()
        grid[3]["fx-unans-09"] = invalid("fx-unans-09", "na_tech_fail")
        return grid

    def wrong_shape():
        grid = full_grid()
        for r in grid:
            del grid[r]["fx-unans-16"]
        grid[1]["fx-extra-a"] = judged("fx-extra-a")
        grid[2]["fx-extra-a"] = judged("fx-extra-a")
        return grid

    def extra_votes():
        grid = full_grid()
        for r in grid:
            grid[r]["fx-extra-a"] = judged("fx-extra-a")
        return grid

    return [pytest.param(make, id=make.__name__) for make in
            (missing_cell, judge_error_cell, invalid_cell, wrong_shape, extra_votes)]


@pytest.mark.parametrize("make_grid", broken_grids())
def test_criteria_are_not_evaluated_when_any_pass_cannot_be_asserted(make_grid):
    calls, evaluate = spy()

    with pytest.raises(gv.GateStop) as stopped:
        gv.aggregate({"on": rows_of(full_grid()), "off": rows_of(make_grid())}, evaluate=evaluate)

    assert calls == []
    report = stopped.value.report
    assert report["status"] == "STOPPED"
    assert "criteria" not in report
    assert {r["pass"] for r in report["stop_rules"]} == {"off"}


def test_criteria_are_evaluated_once_with_only_the_valid_votes():
    calls, evaluate = spy()

    report = gv.aggregate({"on": rows_of(full_grid()), "off": rows_of(full_grid())}, evaluate=evaluate)

    assert report["status"] == "EVALUATED"
    assert report["stop_rules"] == []
    assert report["criteria"] == {"ok": True}
    assert len(calls) == 1
    votes = calls[0]
    assert sorted(votes) == ["off", "on"]
    for by_question in votes.values():
        assert sorted(by_question) == QIDS
        for reps in by_question.values():
            assert sorted(reps) == [1, 2, 3]
            for verdict in reps.values():  # 只有四个判词字段，不夹带 reason / error 文本
                assert verdict == {"correct_handling": True, "made_unsupported_claim": False,
                                   "wrong_answer": False, "lang_follow": True}


def test_aggregate_requires_a_criteria_function():
    # 没有 evaluate 就没有「求值通过」这种状态：EVALUATED 报告必然带 criteria，打印判定行时不会缺键
    with pytest.raises(TypeError):
        gv.aggregate({"on": rows_of(full_grid())})


def test_two_passes_where_one_is_short_stop_the_whole_run(tmp_path):
    on, off = full_grid(), full_grid()
    off[2]["fx-unans-16"] = judge_error("fx-unans-16")
    report = tmp_path / "report.json"

    proc, body = run_and_load({"on": files_for(tmp_path / "on", on), "off": files_for(tmp_path / "off", off)}, report)

    assert proc.returncode != 0
    assert body["passes"]["on"]["valid_votes"] == 48
    assert body["passes"]["off"]["valid_votes"] == 47
    assert {r["pass"] for r in body["stop_rules"]} == {"off"}
    assert "criteria" not in body
    assert "unans_majority" not in proc.stdout + proc.stderr


# ---------------------------------------------------------------- 负对照的其它形态：零有效票一律失败


def zero_valid_grids():
    def all_judge_error():
        return {r: {q: judge_error(q) for q in QIDS} for r in (1, 2, 3)}

    def all_invalid():
        return {r: {q: invalid(q, "na_tech_fail") for q in QIDS} for r in (1, 2, 3)}

    def no_rows():
        return {r: {} for r in (1, 2, 3)}

    return [pytest.param(make, id=make.__name__) for make in (all_judge_error, all_invalid, no_rows)]


@pytest.mark.parametrize("make_grid", zero_valid_grids())
def test_zero_valid_votes_in_any_shape_fails(tmp_path, make_grid):
    report = tmp_path / "report.json"

    proc, body = run_and_load({"on": files_for(tmp_path / "votes", make_grid())}, report)

    assert proc.returncode != 0
    assert body["status"] == "STOPPED"
    assert body["passes"]["on"]["valid_votes"] == 0
    assert rules(body, "VALID_VOTES_SHORT")[0]["actual_valid_votes"] == 0
    assert "criteria" not in body
    assert "unans_majority" not in proc.stdout + proc.stderr


def test_duplicated_valid_row_does_not_inflate_the_count(tmp_path):
    # 同一张有效票写了两遍，另有一格没有票：重复行不能把 47 充成 48
    grid = full_grid()
    del grid[2]["fx-unans-08"]
    files = files_for(tmp_path / "votes", grid)
    rows_file(files[1], list(grid[2].values()) + [judged("fx-unans-01")])
    report = tmp_path / "report.json"

    proc, body = run_and_load({"on": files}, report)

    assert proc.returncode != 0
    assert body["passes"]["on"]["valid_votes"] == 47
    assert body["passes"]["on"]["superseded"]["count"] == 1
    # 被弃的那条与留存的那条是同一张票：判词相同
    assert body["passes"]["on"]["superseded"]["votes"][0]["verdict_differs"] is False
    assert "criteria" not in body


# ---------------------------------------------------------------- 格的边界：重复号 1..3 之外、没有 q_id 的行


def test_rows_declared_outside_repeats_1_to_3_are_not_valid_votes(tmp_path):
    files = files_for(tmp_path / "votes", full_grid())
    report = tmp_path / "report.json"

    # 三个文件都齐，但第三个被标成重复号 4：格子不是「重复号 1..3」
    proc = run_cli([("on", "1", files[0]), ("on", "2", files[1]), ("on", "4", files[2])], report)

    assert proc.returncode != 0
    body = json.loads(report.read_text(encoding="utf-8"))
    section = body["passes"]["on"]
    assert section["valid_votes"] == 32
    assert section["invalid"]["count"] == 16
    assert {v["reason"] for v in section["invalid"]["votes"]} == {"repeat_out_of_range"}
    assert section["missing"]["count"] == 16
    assert "criteria" not in body


def test_repeat_number_that_is_not_an_integer_is_rejected(tmp_path):
    files = files_for(tmp_path / "votes", full_grid())
    report = tmp_path / "report.json"

    proc = run_cli([("on", "1", files[0]), ("on", "x", files[1]), ("on", "3", files[2])], report)

    assert proc.returncode != 0
    assert not report.exists()
    assert "unans_majority" not in proc.stdout + proc.stderr
    # 是用法错误，不是崩溃
    assert "usage:" in proc.stderr
    assert "Traceback" not in proc.stderr


def test_row_without_q_id_is_invalid(tmp_path):
    grid = full_grid()
    grid[1]["fx-unans-06"] = {**judged("fx-unans-06"), "q_id": None}
    report = tmp_path / "report.json"

    proc, body = run_and_load({"on": files_for(tmp_path / "votes", grid)}, report)

    assert proc.returncode != 0
    listed = body["passes"]["on"]["invalid"]
    assert listed["votes"] == [{"q_id": None, "repeat": 1, "reason": "no_q_id"}]
    assert body["passes"]["on"]["valid_votes"] == 47
    assert "criteria" not in body


def test_run_with_no_passes_at_all_is_stopped():
    # 一遍都没有：没有票可断言，不能对着空集求值判据（空集上的「全过」是空洞的真）
    calls, evaluate = spy()

    with pytest.raises(gv.GateStop) as stopped:
        gv.aggregate({}, evaluate=evaluate)

    assert calls == []
    assert [r["rule"] for r in stopped.value.report["stop_rules"]] == ["NO_VOTES"]
    assert "criteria" not in stopped.value.report


# ---------------------------------------------------------------- 判据不过 ≠ 停：那是一条判定，照常出具


def test_failing_criterion_is_a_verdict_not_a_stop(tmp_path):
    report = tmp_path / "report.json"

    # 夹具 15/16 过；把门槛提到 16 → 判据不过，但有效票是 48，照常出具判定
    proc, body = run_and_load({"on": control("unans-48-valid")}, report, "--min-pass", "16")

    assert proc.returncode == 0
    crit = body["criteria"]["on"]
    assert crit["unans_min_pass"] == 16
    assert crit["unans_ge_min_pass"] is False
    assert "unans_ge_min_pass=False" in proc.stdout


# ---------------------------------------------------------------- 内置判据自检：直接调用也过不了断言


def grids_the_builtin_criteria_must_refuse():
    def two_votes_each():
        # 16 题 × 2 票 = 32 张有效票：「三取二」会退化成逻辑与
        return {q: {1: VERDICT, 2: VERDICT} for q in QIDS}

    def empty_pass():
        return {}

    def seventeen_questions():
        return {**{q: {r: VERDICT for r in (1, 2, 3)} for q in QIDS}, "fx-extra-a": {r: VERDICT for r in (1, 2, 3)}}

    def one_question_with_four_votes():
        return {**{q: {r: VERDICT for r in (1, 2, 3)} for q in QIDS}, QIDS[0]: {r: VERDICT for r in (1, 2, 3, 4)}}

    return [pytest.param(two_votes_each, 16, QIDS, id="two_votes_each"),
            pytest.param(empty_pass, 0, [], id="empty_pass"),
            pytest.param(seventeen_questions, 17, [], id="seventeen_questions"),
            pytest.param(one_question_with_four_votes, 16, [QIDS[0]], id="one_question_with_four_votes")]


@pytest.mark.parametrize("make_votes, questions, not_3_valid", grids_the_builtin_criteria_must_refuse())
def test_builtin_criteria_refuse_a_grid_that_is_not_16_by_3(make_votes, questions, not_3_valid):
    # 判据函数是公开的：绕过 aggregate 直接调用它，也不能对着不是 16×3 的格求值
    with pytest.raises(gv.GateStop) as stopped:
        gv.unans_criteria({"on": make_votes()})

    report = stopped.value.report
    assert report["status"] == "STOPPED"
    assert "criteria" not in report
    assert rules(report, "VOTE_SHAPE_MISMATCH") == [
        {"rule": "VOTE_SHAPE_MISMATCH", "pass": "on", "expected_questions": 16,
         "questions": questions, "questions_not_3_valid": not_3_valid}]


def test_builtin_criteria_refuse_only_the_passes_that_are_not_16_by_3():
    with pytest.raises(gv.GateStop) as stopped:
        gv.unans_criteria({"on": votes_of(full_grid()), "off": {q: {1: VERDICT, 2: VERDICT} for q in QIDS}})

    assert [r["pass"] for r in rules(stopped.value.report, "VOTE_SHAPE_MISMATCH")] == ["off"]


def test_builtin_criteria_refuse_when_there_is_no_pass_at_all():
    # 没有票可断言，不能对着空集求值判据（空集上的「全过」是空洞的真）
    with pytest.raises(gv.GateStop) as stopped:
        gv.unans_criteria({})

    assert [r["rule"] for r in stopped.value.report["stop_rules"]] == ["NO_VOTES"]


def test_builtin_criteria_evaluate_a_16_by_3_grid_called_directly():
    crit = gv.unans_criteria({"on": votes_of(full_grid())})

    assert crit["on"]["unans_majority_pass"] == 16
    assert crit["on"]["unans_ge_min_pass"] is True

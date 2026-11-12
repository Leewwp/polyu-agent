# -*- coding: utf-8 -*-
"""回放器单测（#488 T3 §6）：合成小样本验证聚合与 SQL 对照逻辑，零 DB。

真实回放（本地库全量含 tool 块消息）由 replay.py CLI 离线跑，
本测试只锁定聚合/对照/报告行为。
"""
from __future__ import annotations

import json

import pytest

import replay


def write_jsonl(path, rows):
    path.write_text(
        "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows),
        encoding="utf-8")


DUMP_ROWS = [
    {"id": "1", "message_status": "NORMAL", "blocks": [
        {"kind": "tool", "name": "search_knowledge", "status": "done"},
        {"kind": "tool", "name": "flush_memory", "status": "done"},
    ]},
    {"id": "2", "message_status": "INTERRUPTED", "blocks": [
        {"kind": "tool", "name": "search_knowledge", "status": "interrupted"},
    ]},
    {"id": "3", "message_status": "NORMAL", "blocks": None},  # 守卫拒答形态
]

SQL_AGG = {
    "name_status": {"search_knowledge|done": 1, "flush_memory|done": 1,
                    "search_knowledge|interrupted": 1},
    "per_name": {"search_knowledge": 2, "flush_memory": 1},
    "message_status": {"NORMAL": 2, "INTERRUPTED": 1},
}


@pytest.fixture()
def case(tmp_path):
    dump = tmp_path / "dump.jsonl"
    agg = tmp_path / "sql-agg.json"
    out = tmp_path / "report.md"
    write_jsonl(dump, DUMP_ROWS)
    agg.write_text(json.dumps(SQL_AGG), encoding="utf-8")
    return dump, agg, out


class TestReplay:
    def test_matching_aggregates_pass_and_report_written(self, case):
        dump, agg, out = case
        result = replay.run_replay(str(dump), str(agg), str(out))
        assert result["rows"] == 3
        assert result["parse_errors"] == 0
        assert result["mismatches"] == []
        assert out.exists()
        assert "search_knowledge" in out.read_text(encoding="utf-8")

    def test_sql_mismatch_is_reported(self, case, tmp_path):
        dump, agg, out = case
        bad = dict(SQL_AGG, per_name={"search_knowledge": 99, "flush_memory": 1})
        agg.write_text(json.dumps(bad), encoding="utf-8")
        result = replay.run_replay(str(dump), str(agg), str(out))
        assert result["mismatches"]
        assert any("per_name" in m for m in result["mismatches"])

    def test_parse_error_is_counted_not_raised(self, case, tmp_path):
        dump, agg, out = case
        rows = DUMP_ROWS + [{"id": "4", "message_status": "NORMAL",
                             "blocks": [{"kind": "tool", "status": "done"}]}]  # 缺 name
        write_jsonl(dump, rows)
        result = replay.run_replay(str(dump), str(agg), str(out))
        # 脏行计入 parse_errors 而非抛异常；该行仍在行数与 message_status 分布里
        # （SQL 对照未更新，故 mismatches 非空属预期——本测试只锁解析面行为）
        assert result["parse_errors"] == 1
        assert any("id=4" in s for s in result["error_samples"])
        assert result["rows"] == 4

    def test_comparator_exclusion_reported_separately(self, case):
        dump, agg, out = case
        result = replay.run_replay(str(dump), str(agg), str(out))
        # 对照口径=raw（含 flush_memory，与 SQL 聚合同口径）；
        # 断言器口径（排除 flush_memory）单独披露
        assert result["raw"]["per_name"]["flush_memory"] == 1
        assert result["comparator"]["per_name"].get("flush_memory") is None

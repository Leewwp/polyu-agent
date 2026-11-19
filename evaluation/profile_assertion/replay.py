# -*- coding: utf-8 -*-
"""回放器（#488 T3 §6）：本地库既有含 tool 块消息全量回放断言器解析面。

零 DB（读数由外部 psql 导出文件喂数，本模块零连接零凭据）：
- dump=JSONL，每行 {"id":…, "message_status":…, "blocks": <JSON 数组或 null>}，
  导出命令（polyu-pg 容器，只读 SELECT）::

      docker exec polyu-pg psql -U postgres -d ragent -tAc \
        "SELECT jsonb_build_object('id',id,'message_status',message_status,'blocks',blocks) \
         FROM t_agent_message \
         WHERE blocks IS NOT NULL AND blocks @> '[{\"kind\":\"tool\"}]'::jsonb" > dump.jsonl

- sql-agg=JSON，SQL 侧聚合对照（与回放聚合同口径：含 flush_memory 的 raw 计数）::

      docker exec polyu-pg psql -U postgres -d ragent -tAc \
        "SELECT jsonb_build_object( \
           'name_status', (SELECT jsonb_object_agg(name||'|'||status, cnt) FROM \
             (SELECT b->>'name' name, b->>'status' status, count(*) cnt \
              FROM t_agent_message m, jsonb_array_elements(m.blocks) b \
              WHERE b->>'kind'='tool' GROUP BY 1,2) s), \
           'per_name', (SELECT jsonb_object_agg(name, cnt) FROM \
             (SELECT b->>'name' name, count(*) cnt \
              FROM t_agent_message m, jsonb_array_elements(m.blocks) b \
              WHERE b->>'kind'='tool' GROUP BY 1) s), \
           'message_status', (SELECT jsonb_object_agg(message_status, cnt) FROM \
             (SELECT message_status, count(*) cnt FROM t_agent_message \
              WHERE blocks IS NOT NULL AND blocks @> '[{\"kind\":\"tool\"}]'::jsonb \
              GROUP BY 1) s))"

验证口径（票面）：解析零异常 + 按 name 计数与 SQL 聚合对照一致。
对照用 raw 口径（不排除 flush_memory，与 SQL 同口径）；断言器口径
（排除 flush_memory）单独披露，佐证排除规则实际生效。

用法：python3 replay.py --dump dump.jsonl --sql-agg agg.json --out REPORT.md
退出码：零异常+对照全一致=0；否则 1（供本地门禁复用）。
"""
from __future__ import annotations

import argparse
import collections
import json
import sys
from typing import List, Optional

import profile_assertor as pa


def _aggregate(parsed_blocks_list):
    """双口径聚合：raw（含排除集，对 SQL）/ comparator（断言器默认排除集）。

    parsed_blocks_list 已全部通过 parse_blocks+extract_tool_profile 双面校验
    （tool 块 name/status 必有效），此处只做计数。
    """
    raw_ns = collections.Counter()
    raw_name = collections.Counter()
    cmp_name = collections.Counter()
    for blocks in parsed_blocks_list:
        for block in blocks:
            if not isinstance(block, dict) or block.get("kind") != pa.KIND_TOOL:
                continue
            key = f"{block.get('name')}|{block.get('status')}"
            raw_ns[key] += 1
            raw_name[block.get("name")] += 1
            if block.get("name") not in pa.DEFAULT_EXCLUDED_TOOLS:
                cmp_name[block.get("name")] += 1
    return {"name_status": dict(raw_ns), "per_name": dict(raw_name)}, \
        {"per_name": dict(cmp_name)}


def run_replay(dump_path: str, sql_agg_path: str, out_path: Optional[str] = None) -> dict:
    rows = 0
    parse_errors: List[str] = []
    parsed_blocks_list = []
    msg_status_dist = collections.Counter()
    with open(dump_path, encoding="utf-8") as fh:
        for line in fh:
            if not line.strip():
                continue
            row = json.loads(line)
            rows += 1
            msg_status_dist[row.get("message_status")] += 1
            try:
                # 解析面全走断言器：parse_blocks（结构）+ extract_tool_profile
                # 双口径（raw 空排除集=校验全部 tool 块；默认排除集=断言器实跑口径）
                parsed = pa.parse_blocks(row.get("blocks"))
                pa.extract_tool_profile(parsed, excluded_tools=frozenset())
                pa.extract_tool_profile(parsed)
                parsed_blocks_list.append(parsed)
            except pa.BlocksParseError as exc:
                parse_errors.append(f"id={row.get('id')}: {exc}")
                parsed_blocks_list.append([])
    raw, comparator = _aggregate(parsed_blocks_list)
    with open(sql_agg_path, encoding="utf-8") as fh:
        sql_agg = json.load(fh)

    mismatches: List[str] = []
    sql_ns = {k: int(v) for k, v in sql_agg.get("name_status", {}).items()}
    sql_name = {k: int(v) for k, v in sql_agg.get("per_name", {}).items()}
    sql_ms = {k: int(v) for k, v in sql_agg.get("message_status", {}).items()}
    if raw["name_status"] != sql_ns:
        mismatches.append(f"name_status 不一致: 回放 {raw['name_status']} vs SQL {sql_ns}")
    if raw["per_name"] != sql_name:
        mismatches.append(f"per_name 不一致: 回放 {raw['per_name']} vs SQL {sql_name}")
    if dict(msg_status_dist) != sql_ms:
        mismatches.append(
            f"message_status 不一致: 回放 {dict(msg_status_dist)} vs SQL {sql_ms}")

    result = {"rows": rows, "parse_errors": len(parse_errors),
              "error_samples": parse_errors[:10], "mismatches": mismatches,
              "raw": raw, "comparator": comparator,
              "message_status": dict(msg_status_dist)}
    if out_path:
        _write_report(out_path, result, sql_agg)
    return result


def _fmt_matrix(name_status: dict) -> str:
    names = sorted({k.split("|", 1)[0] for k in name_status})
    statuses = sorted({k.split("|", 1)[1] for k in name_status})
    if not names:
        return "（无 tool 块）"
    header = "| name | " + " | ".join(statuses) + " | 合计 |"
    lines = [header, "|" + "---|" * (len(statuses) + 2)]
    for name in names:
        cells = [str(name_status.get(f"{name}|{s}", 0)) for s in statuses]
        total = sum(int(c) for c in cells)
        lines.append(f"| {name} | " + " | ".join(cells) + f" | {total} |")
    return "\n".join(lines)


def _write_report(path: str, result: dict, sql_agg: dict) -> None:
    lines = [
        "# 轨迹画像断言器回放验证报告（#488 T3 §6）",
        "",
        f"- 回放范围：本地库 t_agent_message 含 tool 块消息（polyu-pg 容器 5434/ragent，只读 SELECT）",
        f"- 回放行数：{result['rows']}",
        f"- 解析异常：{result['parse_errors']}"
        + ("。" if not result["parse_errors"]
           else f"（样例：{result['error_samples']}）"),
        f"- 对照结论：{'一致' if not result['mismatches'] else '不一致'}"
        + ("。" if not result["mismatches"] else f"——{result['mismatches']}"),
        "",
        "## 工具×状态矩阵（raw 口径，含 flush_memory，与 SQL 聚合同口径）",
        "",
        _fmt_matrix(result["raw"]["name_status"]),
        "",
        "## 断言器口径（排除 flush_memory）每工具计数",
        "",
        ", ".join(f"{k}={v}" for k, v in sorted(result["comparator"]["per_name"].items()))
        or "（全部被排除）",
        "",
        "## message_status 分布（含 tool 块消息行）",
        "",
        ", ".join(f"{k}={v}" for k, v in sorted(result["message_status"].items())),
        "",
        "## SQL 侧聚合（对照输入原文）",
        "",
        "```json",
        json.dumps(sql_agg, ensure_ascii=False, indent=2, sort_keys=True),
        "```",
        "",
    ]
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))


def main(argv: Optional[List[str]] = None) -> int:
    ap = argparse.ArgumentParser(description="回放断言器解析面（零 DB：读导出文件）")
    ap.add_argument("--dump", required=True, help="psql 导出的 JSONL")
    ap.add_argument("--sql-agg", required=True, help="psql 导出的 SQL 聚合 JSON")
    ap.add_argument("--out", required=True, help="报告输出路径（markdown）")
    args = ap.parse_args(argv)
    result = run_replay(args.dump, args.sql_agg, args.out)
    ok = result["parse_errors"] == 0 and not result["mismatches"]
    print(f"[replay] rows={result['rows']} parse_errors={result['parse_errors']} "
          f"mismatches={len(result['mismatches'])} -> {'OK' if ok else 'FAIL'}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

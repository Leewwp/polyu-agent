#!/usr/bin/env python3
"""导出当日可检索 chunk 池（chunk ∩ vector ∩ enabled）为 JSONL。

语义对齐 wiki 实验（project-docs/staging/wiki-synth-experiment-20260918）的
chunks-all.tsv 池：只取 未删 KB → 未删且 enabled 文档 → 未删且 enabled chunk，
且该 chunk 在向量表有行（可被向量通道召回）。

输出列：chunk_id, doc_id, collection_name, doc_name, content。
纯本地 psql（docker exec polyu-pg），只读查询，零写库。

用法：
  python3 export_pool.py --out <pool.jsonl>
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys

SQL = """
SELECT json_build_object(
  'chunk_id', c.id,
  'doc_id', d.id,
  'collection_name', kb.collection_name,
  'doc_name', d.doc_name,
  'content', c.content
)::text
FROM t_knowledge_chunk c
JOIN t_knowledge_document d ON d.id = c.doc_id AND d.deleted = 0 AND d.enabled = 1
JOIN t_knowledge_base kb ON kb.id = d.kb_id AND kb.deleted = 0
WHERE c.deleted = 0 AND c.enabled = 1
  AND EXISTS (SELECT 1 FROM t_knowledge_vector v WHERE v.id = c.id)
"""


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    proc = subprocess.run(
        ["docker", "exec", "polyu-pg", "psql", "-U", "postgres", "-d", "ragent",
         "-t", "-A", "-c", SQL],
        capture_output=True, text=True, check=True)
    rows = 0
    with open(args.out, "w", encoding="utf-8") as f:
        for line in proc.stdout.splitlines():
            line = line.strip()
            if not line:
                continue
            f.write(line + "\n")
            rows += 1
    print(f"[export_pool] rows={rows} -> {args.out}")
    if rows == 0:
        print("[export_pool] 警告：0 行，检查本地栈", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

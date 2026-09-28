#!/usr/bin/env python3
"""gold_span → 当日池 chunk id 集合映射（评测合同 gold_chunk_ids）。

与 0M map_gold_spans.py / wiki map_gold_funnel.py 同一匹配语义：
归一化子串（|→空格、弯引号→半角、空白折叠）在池 chunk content 中匹配，
命中 chunk id 全集收录。产出 gold_map.json：
  {q_id: {"gold_doc_id", "gold_chunk_ids", "gold_doc_names"}}
gold_doc_names = 承载 span 的池 chunk 的 doc 集合（doc 级指标用；
legacy 源 id A1/L1/S1 已不匹配现行 doc 名，doc 级命中以 span 承载文档集合为准，
报告逐条披露此口径）。

验收口径：locate 率 = 成功定位题 / 可答题。任一题 0 命中时该题不带 gold_chunk_ids，
chunk 指标分母 = 已定位题，逐题披露（wiki 实验同款处理）。

用法：
  python3 map_gold.py --evalset <evalset.jsonl> --pool <pool.jsonl> --out <gold_map.json>
"""
from __future__ import annotations

import argparse
import json
import re


def norm(s: str) -> str:
    s = s.replace("|", " ").replace("“", '"').replace("”", '"')
    return re.sub(r"\s+", " ", s).strip()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--evalset", required=True)
    ap.add_argument("--pool", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    pool = []
    for line in open(args.pool, encoding="utf-8"):
        rec = json.loads(line)
        pool.append((rec["chunk_id"], rec["doc_name"], norm(rec["content"] or "")))
    print(f"[map_gold] pool chunks={len(pool)}")

    questions = [json.loads(l) for l in open(args.evalset, encoding="utf-8") if l.strip()]
    answerable = [q for q in questions if q.get("answerable")]
    mapping = {}
    misses = []
    for q in answerable:
        span = norm(q.get("gold_span") or "")
        hit_chunks = []
        hit_docs = []
        for cid, doc_name, content in pool:
            if span and span in content:
                hit_chunks.append(cid)
                if doc_name not in hit_docs:
                    hit_docs.append(doc_name)
        if hit_chunks:
            mapping[q["q_id"]] = {
                "gold_doc_id": q.get("gold_doc_id"),
                "gold_chunk_ids": hit_chunks,
                "gold_doc_names": hit_docs,
            }
        else:
            misses.append(q["q_id"])

    n_ok = len(mapping)
    rate = n_ok / len(answerable) if answerable else 0.0
    out = {"locate_rate": rate, "n_answerable": len(answerable),
           "n_located": n_ok, "misses": misses, "map": mapping}
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print(f"[map_gold] locate {n_ok}/{len(answerable)} rate={rate:.3f} misses={misses}")
    print(f"[map_gold] -> {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

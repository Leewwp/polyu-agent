#!/usr/bin/env python3
"""合并采集三元组与后端埋点：产出 RAGAS 评分输入（一题一行）。

contexts 口径（判据冻结版）：chat 链路 kb_search 埋点的 chunk 文本并集
（按 chunk id 去重保首见序；该题 ts_sent..ts_done 时间窗内的全部检索调用）。
时间窗无命中或埋点缺失时回退 /rag/eval 漏斗 contexts 并标注 contexts_source。

用法：
  python3 build_ragas_input.py --triples <triples-on.jsonl> --dump <dump-on.jsonl> \
      --evalset <evalset.jsonl> --goldmap <gold_map.json> --out <ragas-input-on.jsonl>
"""
from __future__ import annotations

import argparse
import datetime as dt
import json


def parse_ts(s: str) -> dt.datetime:
    # 后端埋点 ts 为 Instant UTC（'Z' 后缀），python3.9 fromisoformat 不认
    return dt.datetime.fromisoformat(s.replace("Z", "+00:00"))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--triples", required=True)
    ap.add_argument("--dump", required=True)
    ap.add_argument("--evalset", required=True)
    ap.add_argument("--goldmap", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    # 埋点检索行，按时间排序
    kb = []
    for line in open(args.dump, encoding="utf-8"):
        d = json.loads(line)
        if d.get("kind") == "kb_search" and d["data"].get("hasKb"):
            kb.append((parse_ts(d["ts"]), d["data"]))
    kb.sort(key=lambda x: x[0])

    goldmap = json.load(open(args.goldmap, encoding="utf-8"))["map"]
    evalset = {json.loads(l)["q_id"]: json.loads(l)
               for l in open(args.evalset, encoding="utf-8") if l.strip()}

    n_rows = 0
    sources = {"chat_dump": 0, "funnel_fallback": 0}
    with open(args.out, "w", encoding="utf-8") as f:
        for line in open(args.triples, encoding="utf-8"):
            t = json.loads(line)
            q = evalset[t["q_id"]]
            sent = parse_ts(t["ts_sent"])
            done = parse_ts(t["ts_done"])

            # 时间窗内的 kb_search chunk 并集（去重保首见序）
            seen = set()
            contexts = []
            chunk_ids = []
            n_searches = 0
            for ts, data in kb:
                if sent <= ts <= done:
                    n_searches += 1
                    for c in data.get("chunks") or []:
                        if c["id"] not in seen:
                            seen.add(c["id"])
                            contexts.append(c["text"])
                            chunk_ids.append(c["id"])
            source = "chat_dump"
            if not contexts:
                contexts = t["funnel"].get("retrieved_contexts") or []
                chunk_ids = t["funnel"].get("retrieved_chunk_ids") or []
                source = "funnel_fallback"
            sources[source] += 1

            g = goldmap.get(t["q_id"]) or {}
            row = {
                "q_id": t["q_id"],
                "arm": t["arm"],
                "run_id": t["run_id"],
                "question": t["question"],
                "answer": t["answer"],
                "message_status": t.get("message_status"),
                "contexts": contexts,
                "context_chunk_ids": chunk_ids,
                "contexts_source": source,
                "n_search_calls": n_searches,
                "funnel_chunk_ids": t["funnel"].get("retrieved_chunk_ids") or [],
                "funnel_doc_ids": t["funnel"].get("retrieved_doc_ids") or [],
                "funnel_contexts": t["funnel"].get("retrieved_contexts") or [],
                "funnel_latency_ms": t["funnel"].get("latency_ms"),
                "gold_chunk_ids": g.get("gold_chunk_ids") or [],
                "gold_doc_names": g.get("gold_doc_names") or [],
                "gold_doc_id": q.get("gold_doc_id"),
                "gold_span": q.get("gold_span"),
                "acceptable_answers": q.get("acceptable_answers") or [],
                "answerable": q.get("answerable"),
                "lang": q.get("lang"),
                "ts_sent": t["ts_sent"],
                "ts_done": t["ts_done"],
            }
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
            n_rows += 1
    print(f"[build_input] rows={n_rows} contexts_source={sources} -> {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

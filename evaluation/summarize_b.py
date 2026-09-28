#!/usr/bin/env python3
"""任务 B 汇总器：上下文体积曲线 + 触发证据 + 约束保持率/末轮质量（裁判评分另跑）。

从埋点 dump（agent_context_pre / agent_reasoning）+ 跑链输出（turns-<arm>.jsonl）
聚合每臂每链每轮的注入字符量，输出体积表与触发统计。

用法：
  python3 summarize_b.py --dump <dump-b-<arm>.jsonl> --turns <turns-<arm>.jsonl> \
      --arm on --out b-volume-<arm>.json
"""
from __future__ import annotations

import argparse
import datetime as dt
import json


def parse_ts(s: str) -> dt.datetime:
    return dt.datetime.fromisoformat(s.replace("Z", "+00:00"))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dump", required=True)
    ap.add_argument("--turns", required=True)
    ap.add_argument("--arm", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    # 埋点：时间序 reasoning 事件（每次模型推理的实际注入量）
    events = []
    for line in open(args.dump, encoding="utf-8"):
        d = json.loads(line)
        if d["kind"] in ("agent_context_pre", "agent_reasoning", "chat_request"):
            events.append((parse_ts(d["ts"]), d["kind"], d["data"]))
    events.sort(key=lambda x: x[0])

    turns = [json.loads(l) for l in open(args.turns, encoding="utf-8") if l.strip()]

    per_turn = []
    for t in turns:
        sent, done = parse_ts(t["ts_sent"]), parse_ts(t["ts_done"])
        pre = [d for ts, k, d in events if k == "agent_context_pre" and sent <= ts <= done]
        post = [d for ts, k, d in events if k == "agent_reasoning" and sent <= ts <= done]
        per_turn.append({
            "chain_id": t["chain_id"],
            "no": t["no"],
            "question_chars": len(t["question"]),
            "answer_chars": len(t["answer"]),
            "reasoning_calls": len(post),
            "pre_context_chars": [d["contextChars"] for d in pre],
            "injected_chars": [d["chars"] for d in post],
            "injected_msgs": [d["msgs"] for d in post],
            "compact_attempted": [d.get("compactAttempted") for d in post],
        })

    # 汇总
    total_injected = sum(sum(p["injected_chars"]) for p in per_turn)
    compact_attempts = sum(1 for p in per_turn for c in p["compact_attempted"] if c)
    max_pre = max((max(p["pre_context_chars"]) for p in per_turn if p["pre_context_chars"]),
                  default=0)
    out = {
        "arm": args.arm,
        "n_turns": len(per_turn),
        "total_injected_chars": total_injected,
        "compact_attempted_turns": compact_attempts,
        "max_pre_context_chars": max_pre,
        "per_turn": per_turn,
    }
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print(f"[summarize_b] arm={args.arm} turns={len(per_turn)} "
          f"total_injected={total_injected} compact_attempts={compact_attempts} "
          f"max_pre={max_pre} -> {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""任务 B 裁判评分：约束保持率 + 末轮质量（固定 prompt、temp=0、盲评）。

- 约束保持率：给定早期约束原文 + 末轮问题 + 末轮答案 → 是否体现模型知晓约束（二值+理由）。
- 末轮质量：0-1 连续分（直接回应/具体可执行/无无依据主张）；链内最后一道 evalset 中间轮
  另按 gold_span/acceptable_answers 对照评分（判据「对照 gold」口径落在有 gold 的轮次）。

裁判模型与版本随输出落盘；调用计数对账。盲评：prompt 不含臂别与压缩信息。

用法：
  BAILIAN_API_KEY=... python3 judge_b.py --chains chains.jsonl --turns <turns-on.jsonl> \
      --evalset <evalset.jsonl> --arm on --out b-judge-on.jsonl
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import sys
import time

from openai import OpenAI

JUDGE_MODEL = "qwen-plus"
BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"

PROMPT_RETENTION = """你是严格的评审。下面是一段多轮对话中的约束设定与最后一轮的问答。

【约束设定（对话早期用户自述）】
{constraint}

【最后一轮用户问题】
{final_question}

【最后一轮助手回答】
{answer}

判断：回答是否体现模型仍知晓上述约束（明确引用、或明显遵循该约束作答）？
只输出 JSON：{{"satisfied": 1 或 0, "reason": "一句话理由"}}。忽视或违背约束=0。"""

PROMPT_QUALITY_FREE = """你是严格的评审。判断下面的助手回答质量。

【用户问题】
{question}

【助手回答】
{answer}

按三维平均打 0 到 1 的分（保留两位小数）：①是否直接回应了问题 ②是否具体可执行
③是否未编造依据。只输出 JSON：{{"score": 0.xx, "reason": "一句话理由"}}。"""

PROMPT_QUALITY_GOLD = """你是严格的评审。根据参考答案判断回答质量。

【用户问题】
{question}

【参考答案要点】
{gold}

【助手回答】
{answer}

对照参考要点，回答覆盖了几个要点、是否准确？打 0 到 1 的分（保留两位小数）：
完全覆盖且准确=1，覆盖主要要点=0.6-0.9，只答对边缘=0.2-0.5，错误/未答=0-0.1。
只输出 JSON：{{"score": 0.xx, "reason": "一句话理由"}}。"""


def ask_json(client, prompt) -> dict:
    for attempt in range(3):
        try:
            resp = client.chat.completions.create(
                model=JUDGE_MODEL, temperature=0, top_p=1,
                messages=[{"role": "user", "content": prompt}])
            text = resp.choices[0].message.content or ""
            start, end = text.find("{"), text.rfind("}")
            if start >= 0 and end > start:
                return json.loads(text[start:end + 1])
        except Exception:  # noqa: BLE001
            time.sleep(2 * (attempt + 1))
    return {"satisfied": None, "score": None, "reason": "judge_failed"}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--chains", required=True)
    ap.add_argument("--turns", required=True)
    ap.add_argument("--evalset", required=True)
    ap.add_argument("--arm", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    api_key = os.environ.get("BAILIAN_API_KEY", "")
    if not api_key:
        print("[judge_b] 缺 BAILIAN_API_KEY", file=sys.stderr)
        return 2
    client = OpenAI(api_key=api_key, base_url=BASE, timeout=120)

    chains = {c["chain_id"]: c for c in
              (json.loads(l) for l in open(args.chains, encoding="utf-8") if l.strip())}
    evalset = {}
    for l in open(args.evalset, encoding="utf-8"):
        r = json.loads(l)
        evalset[r["question"]] = r

    turns = [json.loads(l) for l in open(args.turns, encoding="utf-8") if l.strip()]
    by_chain = {}
    for t in turns:
        by_chain.setdefault(t["chain_id"], []).append(t)

    stamp = dt.datetime.now().astimezone().isoformat()
    n_calls = 0
    with open(args.out, "w", encoding="utf-8") as f:
        for chain_id, c in chains.items():
            tlist = sorted(by_chain.get(chain_id, []), key=lambda t: t["no"])
            if not tlist:
                continue
            final = tlist[-1]
            constraint = c["constraint_t1"] + " / " + c["constraint_t3"]

            r = ask_json(client, PROMPT_RETENTION.format(
                constraint=constraint, final_question=final["question"],
                answer=final["answer"]))
            n_calls += 1

            q_free = ask_json(client, PROMPT_QUALITY_FREE.format(
                question=final["question"], answer=final["answer"]))
            n_calls += 1

            # 链内最后一道可对照 gold 的 evalset 中间轮
            q_gold = None
            for t in reversed(tlist[:-1]):
                ev = evalset.get(t["question"])
                if ev and ev.get("answerable"):
                    q_gold = ask_json(client, PROMPT_QUALITY_GOLD.format(
                        question=t["question"],
                        gold="; ".join(ev["acceptable_answers"]) + "（出处：" + ev["gold_span"] + "）",
                        answer=t["answer"]))
                    q_gold["turn_no"] = t["no"]
                    q_gold["question"] = t["question"]
                    n_calls += 1
                    break

            row = {
                "arm": args.arm, "chain_id": chain_id,
                "constraint_type": c["constraint_type"],
                "n_turns": len(tlist),
                "constraint_satisfied": r.get("satisfied"),
                "constraint_reason": r.get("reason"),
                "final_quality": q_free.get("score"),
                "final_quality_reason": q_free.get("reason"),
                "gold_turn": q_gold,
                "judge_model": JUDGE_MODEL,
                "scored_at": stamp,
            }
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
            f.flush()
            print(f"[judge_b] {chain_id} satisfied={row['constraint_satisfied']} "
                  f"quality={row['final_quality']}", flush=True)
    print(f"[judge_b] judge_calls={n_calls} -> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

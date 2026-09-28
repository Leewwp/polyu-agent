#!/usr/bin/env python3
"""A5 人工抽检生成器：一次生成两个逻辑产物，使盲评成为流程属性。

- calibration-blind.csv：给人工填写——**不含 RAGAS 分数与分层标记**（防止按分对齐），
  行序按 q_id 混排两臂；列 = arm/q_id/lang/question/answer/contexts/人工判定/notes。
- calibration-key.csv：对账表——人工填写完成后才使用；列 = arm/q_id/lang/stratum/
  RAGAS Faithfulness 分，与盲评表按 (arm, q_id) 连接。

抽样：语言 × 臂 均衡（en/zh × on/off 各 n/4，格内低分位多取一条、高分位补足）。
分层理由：RAGAS 原生裁判 prompt 为英文，中文题面跨语言过裁是已知局限——语言维度
分层让抽检能同时暴露「裁判误差」与「跨语言误差」；臂维度均衡保证两臂
（rerank on/off）的裁判行为都被覆盖。

用法：
  python3 make_calibration_sheet.py --dir <raw目录> --n 20 \
      --blind <calibration-blind.csv> --key <calibration-key.csv>
"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib

BLIND_RUBRIC = (
    "填写说明：rubric=Faithfulness 人工判定——逐条检查答案中的事实性主张是否被"
    "「检索上下文」支撑，支撑=1、无支撑/编造=0（可部分支撑取多数）；human_faithful "
    "列填 1/0，notes 列可选填分歧原因（裁判错/人错/题歧义）。本表不含任何模型评分；"
    "填写时请独立判断，勿参考其他材料。"
)

KEY_RUBRIC = (
    "对账表（人工填写完成后才使用）：按 (arm, q_id) 与盲评表连接，"
    "比对 human_faithful 与 RAGAS faithfulness 的一致性。"
)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True)
    ap.add_argument("--n", type=int, default=20)
    ap.add_argument("--blind", required=True, help="盲评表输出（给人工，不含 RAGAS 分）")
    ap.add_argument("--key", required=True, help="对账表输出（含分数与分层，填写完成后用）")
    args = ap.parse_args()

    d = pathlib.Path(args.dir)
    inputs, scores = {}, {}
    for arm in ("on", "off"):
        for r in [json.loads(l) for l in open(d / f"ragas-input-{arm}.jsonl", encoding="utf-8") if l.strip()]:
            inputs[(arm, r["q_id"])] = r
        for s in [json.loads(l) for l in open(d / f"scores-full-{arm}.jsonl", encoding="utf-8") if l.strip()]:
            scores[(arm, s["q_id"])] = s

    pool = []
    for key, s in scores.items():
        f = s.get("faithfulness")
        if f is None:
            continue
        r = inputs.get(key) or {}
        lang = "en" if r.get("lang") == "en" else "zh"  # zh 含 zh_colloquial
        pool.append((f, key, lang))
    pool.sort(key=lambda x: x[0])
    # 语言 × 臂 均衡（各 n/4），格内低分位多取一条（边界样本更有校准价值）
    n_cell = max(args.n // 4, 1)
    picked = []
    for lang in ("en", "zh"):
        for arm in ("on", "off"):
            sub = [p for p in pool if p[2] == lang and p[1][0] == arm]
            half = n_cell // 2
            picked += sub[:n_cell - half] + sub[len(sub) - half:] if half else sub[:n_cell]
    key_rows = sorted(picked, key=lambda x: (x[1][1], x[1][0]))  # 对账表按 q_id 排序便于查找
    blind_rows = sorted(picked, key=lambda x: (x[1][1], x[1][0]))  # 盲评表同序（q_id 混排两臂）

    with open(args.blind, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow([BLIND_RUBRIC])
        w.writerow(["arm", "q_id", "lang", "question", "answer",
                    "contexts(检索上下文)", "human_faithful(1/0)",
                    "notes(分歧原因:裁判错/人错/题歧义)"])
        for f_score, (arm, qid), lang in blind_rows:
            r = inputs[(arm, qid)]
            contexts = "\n---\n".join(f"[{i + 1}] {c[:400]}" for i, c in enumerate(r["contexts"][:5]))
            w.writerow([arm, qid, r.get("lang"), r["question"], r["answer"],
                        contexts or "(无)", "", ""])

    with open(args.key, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow([KEY_RUBRIC])
        w.writerow(["arm", "q_id", "lang", "stratum", "faithfulness(RAGAS)"])
        for f_score, (arm, qid), lang in key_rows:
            r = inputs[(arm, qid)]
            stratum = f"{lang}-{'low' if f_score < 0.5 else 'high'}"
            w.writerow([arm, qid, r.get("lang"), stratum, round(f_score, 4)])

    print(f"[calib] blind={len(blind_rows)} -> {args.blind}")
    print(f"[calib] key={len(key_rows)} -> {args.key}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

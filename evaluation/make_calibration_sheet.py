#!/usr/bin/env python3
"""A5 人工抽检表生成器：语言 × 臂 均衡分层（en/zh × on/off 各 n/4，格内低分位
多取一条、高分位补足），供人工盲评。

分层理由：RAGAS 原生裁判 prompt 为英文，中文题面跨语言过裁是已知局限——
语言维度分层让人工抽检能同时暴露「裁判误差」与「跨语言误差」两类分歧；
臂维度均衡保证两臂（rerank on/off）的裁判行为都被覆盖。

输出 CSV：q_id/arm/question/answer（contexts 另列）/RAGAS 分/分层标记/人工判定列/备注列。
判定 rubric（表头注明）：逐条判断「答案中的每条主张是否被上下文支撑」，二值 1/0 + 备注。

用法：
  python3 make_calibration_sheet.py --dir <raw目录> --n 20 --out <calibration-sheet.csv>
"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib

SHEET_HEADER_COMMENT = (
    "填写说明：rubric=Faithfulness 人工判定——逐条检查答案中的事实性主张是否被"
    "「检索上下文」支撑，支撑=1、无支撑/编造=0（可部分支撑取多数）；human_faithful 列填 1/0，"
    "notes 列可选填分歧原因（裁判错/人错/题歧义）。盲评：请先不看 RAGAS 分列。"
)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True)
    ap.add_argument("--n", type=int, default=20)
    ap.add_argument("--out", required=True)
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
    picked.sort(key=lambda x: (x[1][0], x[1][1]))

    with open(args.out, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow([SHEET_HEADER_COMMENT])
        w.writerow(["arm", "q_id", "lang", "question", "answer",
                    "contexts(检索上下文)", "faithfulness(RAGAS，填完再看)", "stratum",
                    "human_faithful(1/0)", "notes(分歧原因:裁判错/人错/题歧义)"])
        for f_score, (arm, qid), lang in picked:
            r = inputs[(arm, qid)]
            s = scores[(arm, qid)]
            stratum = f"{lang}-{'low' if f_score < 0.5 else 'high'}"
            contexts = "\n---\n".join(f"[{i + 1}] {c[:400]}" for i, c in enumerate(r["contexts"][:5]))
            w.writerow([arm, qid, r.get("lang"), r["question"], r["answer"],
                        contexts or "(无)", round(f_score, 4), stratum, "", ""])
    print(f"[calib] n={len(picked)} -> {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""确定性排序指标：两臂 MRR@10 / NDCG@10 / Hit@10 + 各指标差值的簇级 bootstrap CI。

matched-TopK 排序对照（见 README 同名节）：
- ON 臂 funnel = 精排后 top-10；
- OFF 臂 funnel 无截断、返回序即融合序，取前 k=10 = 无精排排序的 top-10。
同检索、同候选池、同 k —— 两臂差值即排序规则本身的贡献，不依赖 LLM 裁判。

分母 = 可答且 gold_chunk_ids 非空的题（与 summarize.hit_metrics 一致）。
重采样单位 = fact_cluster_id（同簇 EN/ZH 变体共同进样，见 bootstrap.py）。

用法：
  python3 ranking_metrics.py --dir <raw-dir> --evalset <evalset.jsonl> --out <ranking.json>
"""
from __future__ import annotations

import argparse
import json
import math
import pathlib

import bootstrap

K = 10
N_BOOT, LEVEL, SEED = 10_000, 0.95, 20260927
METRICS = ("hit_at_10", "mrr_at_10", "ndcg_at_10")


def load_jsonl(p):
    return [json.loads(l) for l in open(p, encoding="utf-8") if l.strip()]


def per_question(funnel_ids, goldset, k=K):
    """每题确定性排序指标（二值相关）：(hit, rr, ndcg)。"""
    top = funnel_ids[:k]
    rank = next((i + 1 for i, c in enumerate(top) if c in goldset), None)
    hit = 1.0 if rank is not None else 0.0
    rr = (1.0 / rank) if rank is not None else 0.0
    dcg = sum(1.0 / math.log2(i + 2) for i, c in enumerate(top) if c in goldset)
    ideal_n = min(len(goldset), k)
    idcg = sum(1.0 / math.log2(j + 2) for j in range(ideal_n))
    ndcg = dcg / idcg if idcg > 0 else 0.0
    return hit, rr, ndcg


def collect(rows):
    """→ {q_id: (hit, rr, ndcg)}，分母规则与 summarize.hit_metrics 一致。"""
    out = {}
    for r in rows:
        if not r.get("answerable"):
            continue
        gset = set(r.get("gold_chunk_ids") or [])
        if not gset:
            continue
        out[r["q_id"]] = per_question(r.get("funnel_chunk_ids") or [], gset)
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True, help="raw 目录（含 ragas-input-{on,off}.jsonl）")
    ap.add_argument("--evalset", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    d = pathlib.Path(args.dir)
    evalset = {r["q_id"]: r for r in load_jsonl(args.evalset)}

    vals = {}
    for arm in ("on", "off"):
        f = d / f"ragas-input-{arm}.jsonl"
        if f.exists():
            vals[arm] = collect(load_jsonl(f))

    result = {"k": K, "n": 0, "arms": {}, "paired_ci": {},
              "caveats": [
                  "ON 臂证据闸门偶尔将 top-10 滤至 <10 条；精排候选输入可能受 "
                  "rerank-candidate-limit 截断——两者均属精排阶段的部署语义",
                  "OFF 臂取漏斗返回序（融合序）前 k 条，作为「无精排排序」的同池同 k 对照",
              ]}
    if "on" in vals and "off" in vals:
        common = sorted(set(vals["on"]) & set(vals["off"]))
        result["n"] = len(common)
        for arm in ("on", "off"):
            per_m = {m: [vals[arm][q][i] for q in common] for i, m in enumerate(METRICS)}
            result["arms"][arm] = {m: sum(v) / len(v) for m, v in per_m.items()}
        for i, m in enumerate(METRICS):
            obs = [bootstrap.PairedObservation(
                       cluster_id=(evalset.get(q) or {}).get("fact_cluster_id") or q,
                       value_a=vals["on"][q][i], value_b=vals["off"][q][i], tag=q)
                   for q in common]
            r = bootstrap.grouped_bootstrap_paired(obs, n_boot=N_BOOT, seed=SEED, level=LEVEL)
            result["paired_ci"][m] = {
                "point_on": r.point_a, "point_off": r.point_b, "point_diff": r.point_diff,
                "ci_diff_low": r.ci_diff[0], "ci_diff_high": r.ci_diff[1],
                "n_boot": N_BOOT, "seed": SEED, "level": LEVEL,
            }

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=1)
    print(f"[ranking] n={result['n']} -> {args.out}")
    for m in METRICS:
        ci = result["paired_ci"].get(m)
        if ci:
            print(f"  {m}: ON {ci['point_on']:.3f} vs OFF {ci['point_off']:.3f} "
                  f"diff {ci['point_diff']:+.3f} CI [{ci['ci_diff_low']:+.3f}, {ci['ci_diff_high']:+.3f}]")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

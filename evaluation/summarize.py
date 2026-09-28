#!/usr/bin/env python3
"""任务 A 汇总器：两臂×四指标总表 + 抖动 + 簇级 bootstrap CI + hit@10 + A5 自动对照。

复用同目录 bootstrap.py 的簇级重采样
（重采样单位 = fact_cluster_id；预注册参数 n_boot=10000 / 95% / seed=20260927）。

A5 自动对照（预注册口径）：
- RAGAS 判定二值化：Context Recall ≥ 0.5 / Context Precision ≥ 0.5；
- gold 判定 = gold_chunk_ids 与检索 chunk 集合交集非空；
- 两种检索口径各一行：funnel top-10 / chat-link contexts 并集；
- 一致率点估计 + Wilson 95% 区间 + 类分布；分母 = 可答题。

用法：
  python3 summarize.py --dir project-docs/staging/rerank-ragas-ablation-20260927/raw \
      --evalset <evalset.jsonl> --out <summary.json>
"""
from __future__ import annotations

import argparse
import json
import math
import pathlib

import bootstrap

METRICS = ("faithfulness", "answer_relevancy", "context_precision", "context_recall")
N_BOOT, LEVEL, SEED = 10_000, 0.95, 20260927


def load_jsonl(p):
    return [json.loads(l) for l in open(p, encoding="utf-8") if l.strip()]


def wilson(k: int, n: int, z: float = 1.96) -> tuple[float, float, float]:
    if n == 0:
        return (float("nan"),) * 3
    p = k / n
    denom = 1 + z * z / n
    center = (p + z * z / (2 * n)) / denom
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denom
    return p, center - half, center + half


def mean(xs):
    xs = [x for x in xs if x is not None]
    return sum(xs) / len(xs) if xs else None


def arm_metric_map(scores):
    return {s["q_id"]: s for s in scores}


def cluster_of(qid, evalset):
    row = evalset[qid]
    return row.get("fact_cluster_id") or qid


def paired_ci(pairs, evalset):
    """pairs: list[(qid, v_on, v_off)] → ON−OFF 差值簇级 bootstrap CI。"""
    obs = [bootstrap.PairedObservation(cluster_id=cluster_of(q, evalset), value_a=a, value_b=b,
                                       tag=q)
           for q, a, b in pairs if a is not None and b is not None]
    if not obs:
        return None
    r = bootstrap.grouped_bootstrap_paired(obs, n_boot=N_BOOT, seed=SEED, level=LEVEL)
    return {"point_on": r.point_a, "point_off": r.point_b, "point_diff": r.point_diff,
            "ci_diff_low": r.ci_diff[0], "ci_diff_high": r.ci_diff[1],
            "n": len(obs), "n_boot": N_BOOT, "seed": SEED, "level": LEVEL}


def hit_metrics(input_rows, goldmap):
    """funnel top-10 的 gold_chunk / gold_doc 命中（metrics.py 同公式：首名次 ≤ k）。"""
    k = 10
    chunk_hits, doc_hits, covered = [], [], []
    for r in input_rows:
        if not r.get("answerable"):
            continue
        g = goldmap.get(r["q_id"]) or {}
        gset = set(g.get("gold_chunk_ids") or [])
        gdocs = set(g.get("gold_doc_names") or [])
        if not gset:
            continue  # 未定位题不入分母（判据披露）
        rank = next((i + 1 for i, c in enumerate(r["funnel_chunk_ids"][:k]) if c in gset), None)
        chunk_hits.append(1.0 if rank is not None else 0.0)
        fdocs = set(r.get("funnel_doc_ids") or [])
        doc_hits.append(1.0 if (gdocs & fdocs) else 0.0)
        cset = set(r.get("context_chunk_ids") or [])
        covered.append(1.0 if (gset & cset) else 0.0)
    return {
        "n": len(chunk_hits),
        "gold_chunk_hit_at_10": mean(chunk_hits),
        "gold_doc_hit_at_10": mean(doc_hits),
        "gold_chunk_covered_chatlink": mean(covered),
    }


def a5_calibration(input_rows, scores):
    out = {}
    by_q = arm_metric_map(scores)
    for source_key, chunk_key in (("funnel_top10", "funnel_chunk_ids"),
                                  ("chatlink_union", "context_chunk_ids")):
        agree_cr = agree_cp = 0
        n = 0
        k1 = k2 = 0  # 类分布：ragas 判命中/不命中计数（CR）
        for r in input_rows:
            if not r.get("answerable"):
                continue
            s = by_q.get(r["q_id"])
            if not s or s.get("context_recall") is None:
                continue
            gset = set((r.get("gold_chunk_ids") or []))
            ret = set(r.get(chunk_key) or [])
            gold_hit = bool(gset & ret)
            ragas_cr_hit = (s["context_recall"] or 0) >= 0.5
            ragas_cp_hit = (s["context_precision"] or 0) >= 0.5
            agree_cr += int(ragas_cr_hit == gold_hit)
            agree_cp += int(ragas_cp_hit == gold_hit)
            k1 += int(ragas_cr_hit)
            k2 += int(not ragas_cr_hit)
            n += 1
        p_cr, lo_cr, hi_cr = wilson(agree_cr, n)
        p_cp, lo_cp, hi_cp = wilson(agree_cp, n)
        out[source_key] = {
            "n": n,
            "agreement_context_recall": {"rate": p_cr, "wilson95": [lo_cr, hi_cr]},
            "agreement_context_precision": {"rate": p_cp, "wilson95": [lo_cp, hi_cp]},
            "class_distribution_cr_hit": k1,
            "class_distribution_cr_miss": k2,
        }
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True, help="raw 目录")
    ap.add_argument("--evalset", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    d = pathlib.Path(args.dir)
    evalset = {}
    for l in open(args.evalset, encoding="utf-8"):
        r = json.loads(l)
        evalset[r["q_id"]] = r
    goldmap = json.load(open(d / "gold-map-20260927.json", encoding="utf-8"))["map"]

    arms = {}
    inputs = {}
    for arm in ("on", "off"):
        scores_f = d / f"scores-full-{arm}.jsonl"
        input_f = d / f"ragas-input-{arm}.jsonl"
        if scores_f.exists():
            arms[arm] = load_jsonl(scores_f)
            inputs[arm] = load_jsonl(input_f)

    result = {"arms": {}, "paired_ci": {}, "jitter": {}, "hit": {}, "a5": {}}

    for arm, scores in arms.items():
        table = {}
        for m in METRICS:
            vals = [s.get(m) for s in scores]
            vv = [v for v in vals if v is not None]
            table[m] = {
                "mean": mean(vv),
                "median": sorted(vv)[len(vv) // 2] if vv else None,
                "n_defined": len(vv),
                "n_nan": len(vals) - len(vv),
            }
        result["arms"][arm] = {"n_samples": len(scores), "metrics": table}

    # 配对差值 CI（每指标）
    if "on" in arms and "off" in arms:
        on_m, off_m = arm_metric_map(arms["on"]), arm_metric_map(arms["off"])
        for m in METRICS:
            pairs = [(q, (on_m[q] or {}).get(m), (off_m[q] or {}).get(m))
                     for q in set(on_m) & set(off_m)]
            ci = paired_ci(pairs, evalset)
            if ci:
                result["paired_ci"][m] = ci

    # 抖动（jitter 文件 vs full 文件，配对 |Δ|)
    for arm in arms:
        jf = d / f"scores-jitter-{arm}.jsonl"
        if not jf.exists():
            continue
        full_m = arm_metric_map(arms[arm])
        jitters = {s["q_id"]: s for s in load_jsonl(jf)}
        j = {}
        for m in METRICS:
            diffs = [abs(full_m[q][m] - s[m]) for q, s in jitters.items()
                     if full_m.get(q) and full_m[q].get(m) is not None and s.get(m) is not None]
            j[m] = {"n_pairs": len(diffs),
                    "max_abs_diff": max(diffs) if diffs else None,
                    "mean_abs_diff": mean(diffs)}
        result["jitter"][arm] = j

    # hit@10 过程指标
    for arm in inputs:
        result["hit"][arm] = hit_metrics(inputs[arm], goldmap)

    # A5 自动对照
    for arm in arms:
        result["a5"][arm] = a5_calibration(inputs[arm], arms[arm])

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=1)
    print(f"[summarize] -> {args.out}")
    print(json.dumps(result, ensure_ascii=False, indent=1)[:3000])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

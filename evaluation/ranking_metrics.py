#!/usr/bin/env python3
"""确定性排序指标：两臂 MRR@10 / NDCG@10 / Hit@10 + 各指标差值的簇级 bootstrap CI，
附零成本的 ON/OFF 池重叠诊断。

证据等级（A/B 对照，非 frozen-candidate 因果估计）：
- 同题集、同系统配置（rerank 开关除外）、同 k=10，对漏斗输出做确定性的
  rank-sensitive 指标比较——构成「部署精排阶段改善排序质量」的强证据；
- 但两臂为顺序独立采集，且上游查询改写为 LLM 调用、非严格确定性采样，
  逐题 pre-rerank 候选池不保证逐字节相同——不得表述为 identical/frozen
  candidate pool 下的纯 reranker 因果估计。

指标 provenance：
- 原始预注册主判据 = RAGAS Context Precision；原始确定性过程指标 = Hit@10；
- MRR/NDCG 为结果复核阶段新增的 post-hoc rank-sensitive 补充分析，
  今后正式 rerank 实验建议在跑数前纳入预注册指标。

ON 臂 funnel = 精排后 top-10；OFF 臂 funnel 无最终截断、返回序即融合（RRF）序，
取前 k=10 = 无精排排序的 top-10。
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


def pool_overlap_diagnostic(rows_on, rows_off, common, k=K):
    """纯诊断项：ON top-10 与 OFF 融合池的重叠度。

    两臂独立采集且上游查询改写含 LLM 采样——重叠高不等于候选池冻结，
    仅用于提示池漂移的量级；不构成 frozen-candidate 证明。
    """
    avail, jac, sizes = [], [], []
    for q in common:
        on_top = list((rows_on.get(q) or {}).get("funnel_chunk_ids") or [])[:k]
        off_all = list((rows_off.get(q) or {}).get("funnel_chunk_ids") or [])
        if not on_top or not off_all:
            continue
        s_on, s_off_all, s_off_top = set(on_top), set(off_all), set(off_all[:k])
        avail.append(len(s_on & s_off_all) / len(s_on))
        union = s_on | s_off_top
        jac.append(len(s_on & s_off_top) / len(union) if union else 1.0)
        sizes.append(len(off_all))
    n = len(avail)
    return {
        "note": "纯诊断：ON top-10 在 OFF 融合池中的可得率与两臂 top-10 重叠。"
                "两臂独立采集+上游 LLM 改写非确定性，重叠高≠候选池冻结。",
        "n": n,
        "on_top10_available_in_off_pool_mean": sum(avail) / n if n else None,
        "on_top10_available_in_off_pool_min": min(avail) if avail else None,
        "top10_jaccard_mean": sum(jac) / n if n else None,
        "off_pool_size_min": min(sizes) if sizes else None,
        "off_pool_size_mean": sum(sizes) / n if n else None,
        "off_pool_size_max": max(sizes) if sizes else None,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True, help="raw 目录（含 ragas-input-{on,off}.jsonl）")
    ap.add_argument("--evalset", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    d = pathlib.Path(args.dir)
    evalset = {r["q_id"]: r for r in load_jsonl(args.evalset)}

    vals, rows = {}, {}
    for arm in ("on", "off"):
        f = d / f"ragas-input-{arm}.jsonl"
        if f.exists():
            rows[arm] = {r["q_id"]: r for r in load_jsonl(f)}
            vals[arm] = collect(rows[arm].values())

    result = {"k": K, "n": 0, "arms": {}, "paired_ci": {}, "pool_overlap_diagnostic": None,
              "caveats": [
                  "A/B 对照口径：同题集、同系统配置（rerank 开关除外）、同 k——部署级强证据，"
                  "非 frozen-candidate 纯 reranker 因果估计（两臂独立采集，上游查询改写含 LLM 采样）",
                  "ON 臂证据闸门偶尔将 top-10 滤至 <10 条；精排候选输入受 fusion 阶段 "
                  "rerank-candidate-limit 截断（两臂共享该融合管线）——均属精排阶段的部署语义",
                  "指标 provenance：主判据（预注册）= RAGAS Context Precision；确定性过程指标"
                  "（预注册）= Hit@10；MRR/NDCG = post-hoc rank-sensitive 补充分析",
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
        result["pool_overlap_diagnostic"] = pool_overlap_diagnostic(rows["on"], rows["off"], common)

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=1)
    print(f"[ranking] n={result['n']} -> {args.out}")
    for m in METRICS:
        ci = result["paired_ci"].get(m)
        if ci:
            print(f"  {m}: ON {ci['point_on']:.3f} vs OFF {ci['point_off']:.3f} "
                  f"diff {ci['point_diff']:+.3f} CI [{ci['ci_diff_low']:+.3f}, {ci['ci_diff_high']:+.3f}]")
    diag = result.get("pool_overlap_diagnostic")
    if diag and diag["n"]:
        print(f"  [diag] on_top10 in off pool: mean {diag['on_top10_available_in_off_pool_mean']:.3f} "
              f"min {diag['on_top10_available_in_off_pool_min']:.3f}; "
              f"top10 jaccard {diag['top10_jaccard_mean']:.3f}; "
              f"off pool size {diag['off_pool_size_min']}-{diag['off_pool_size_max']} "
              f"(mean {diag['off_pool_size_mean']:.1f})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

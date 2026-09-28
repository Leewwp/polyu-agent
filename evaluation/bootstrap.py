#!/usr/bin/env python3
"""0M 评测不确定性估计：以 fact_cluster_id 为重采样单位的 grouped bootstrap。

对应 project-docs/03 §7.2：paired/grouped bootstrap 必须以 fact_cluster_id 重采样，
每次抽中事实簇时整簇带入（簇内全部 EN/ZH/口语变体共同进样或共同排除），
不能先把每道题当独立样本抽样。同簇 EN/ZH 变体不是两个独立统计样本（03 §6.2）。

预注册统计参数（2026-09-05 拍板，已写回 03 号文档 §7.2）：
n_boot=10000、95% percentile 置信区间、固定随机种子（默认 seed=0）。
默认值可被显式覆盖，但每次正式 run 必须把这三参数记入 run 元数据
（metrics.summarize_run 已按此合同在结果中留痕）。

设计约束：
- 纯 Python3 标准库、函数式、带类型注解；不依赖 metrics.py，可单独复用。
- 统计量默认为均值（HitRate@K 与 MRR 都是查询级值的均值），可传入任意 statistic。
- 固定 seed 完全可复现；簇的抽样顺序确定（unique clusters 排序后逐个抽取）。
- per_question_bootstrap_mean 是 03 §7.2 明确指出的"错误做法"，仅供合成数据
  单元测试做对照，禁止用于正式评测结论。
"""
from __future__ import annotations

import math
import random
from dataclasses import dataclass
from typing import Callable, Iterable, Iterator, Mapping, Sequence

ClusterId = str
Statistic = Callable[[Sequence[float]], float]

DEFAULT_N_BOOT = 10_000
DEFAULT_LEVEL = 0.95
DEFAULT_SEED = 0


# ---------------------------------------------------------------------------
# 基础数据结构
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class Observation:
    """单个查询的指标贡献。

    value 是查询级指标值：HitRate@K 的 0/1 指示、MRR 的 1/rank（未命中为 0）等。
    cluster_id 是该查询所属事实簇；同簇全部变体在重采样中整簇同进同出。
    tag 仅作诊断信息（如 q_id），不参与计算。
    """

    cluster_id: ClusterId
    value: float
    tag: str | None = None


@dataclass(frozen=True)
class PairedObservation:
    """同一查询在两个系统（如两个 Embedding 候选）上的成对指标贡献。

    paired/grouped bootstrap 对同一簇抽取结果同时应用于 value_a 与 value_b，
    保证系统间差值比较在同一重采样集合上进行。
    """

    cluster_id: ClusterId
    value_a: float
    value_b: float
    tag: str | None = None


@dataclass(frozen=True)
class BootstrapResult:
    """单一系统 grouped bootstrap 结果；固定 seed 时完全可复现。"""

    statistic: str
    point_estimate: float
    ci_low: float
    ci_high: float
    level: float
    n_boot: int
    samples: tuple[float, ...]

    def as_dict(self, include_samples: bool = False) -> dict[str, object]:
        out: dict[str, object] = {
            "statistic": self.statistic,
            "point_estimate": self.point_estimate,
            "ci_low": self.ci_low,
            "ci_high": self.ci_high,
            "level": self.level,
            "n_boot": self.n_boot,
        }
        if include_samples:
            out["samples"] = list(self.samples)
        return out


@dataclass(frozen=True)
class PairedBootstrapResult:
    """成对比较的 grouped bootstrap 结果（两系统共享同一批簇抽取）。"""

    point_a: float
    point_b: float
    point_diff: float
    ci_a: tuple[float, float]
    ci_b: tuple[float, float]
    ci_diff: tuple[float, float]
    level: float
    n_boot: int
    samples_diff: tuple[float, ...]

    def as_dict(self, include_samples: bool = False) -> dict[str, object]:
        out: dict[str, object] = {
            "point_a": self.point_a,
            "point_b": self.point_b,
            "point_diff": self.point_diff,
            "ci_a": list(self.ci_a),
            "ci_b": list(self.ci_b),
            "ci_diff": list(self.ci_diff),
            "level": self.level,
            "n_boot": self.n_boot,
        }
        if include_samples:
            out["samples_diff"] = list(self.samples_diff)
        return out


# ---------------------------------------------------------------------------
# 可复用构件：簇索引、簇抽取、整簇展开
# ---------------------------------------------------------------------------


def index_by_cluster(observations: Sequence[Observation]) -> dict[ClusterId, list[Observation]]:
    """按簇分组；簇内保持输入顺序（不做排序，避免改变 tag 语义顺序）。"""
    grouped: dict[ClusterId, list[Observation]] = {}
    for obs in observations:
        grouped.setdefault(obs.cluster_id, []).append(obs)
    return grouped


def index_paired_by_cluster(
    observations: Sequence[PairedObservation],
) -> dict[ClusterId, list[PairedObservation]]:
    grouped: dict[ClusterId, list[PairedObservation]] = {}
    for obs in observations:
        grouped.setdefault(obs.cluster_id, []).append(obs)
    return grouped


def draw_clusters(unique_clusters: Sequence[ClusterId], rng: random.Random) -> list[ClusterId]:
    """有放回地抽取 len(unique_clusters) 个簇；抽中的簇按抽取顺序返回（可重复）。"""
    return [rng.choice(unique_clusters) for _ in unique_clusters]


def expand_clusters(
    by_cluster: Mapping[ClusterId, Sequence[Observation]],
    drawn: Iterable[ClusterId],
) -> list[Observation]:
    """把抽中的簇整簇展开为查询集合；重复抽中同一簇时其全部变体重复出现相同次数。"""
    sample: list[Observation] = []
    for cluster_id in drawn:
        sample.extend(by_cluster[cluster_id])
    return sample


def iter_grouped_resamples(
    observations: Sequence[Observation],
    n_boot: int,
    seed: int,
) -> Iterator[list[Observation]]:
    """逐次产出 grouped 重采样查询集合（生成器），供统计与测试直接观测。"""
    if not observations:
        raise ValueError("observations 不能为空")
    if n_boot <= 0:
        raise ValueError("n_boot 必须为正整数")
    by_cluster = index_by_cluster(observations)
    unique_clusters = sorted(by_cluster)
    rng = random.Random(seed)
    for _ in range(n_boot):
        drawn = draw_clusters(unique_clusters, rng)
        yield expand_clusters(by_cluster, drawn)


# ---------------------------------------------------------------------------
# 统计量与区间
# ---------------------------------------------------------------------------


def mean(values: Sequence[float]) -> float:
    if not values:
        raise ValueError("mean() 需要非空序列")
    return sum(values) / len(values)


def percentile(sorted_values: Sequence[float], q: float) -> float:
    """在升序数组上做线性插值百分位（q ∈ [0, 1]）。"""
    if not sorted_values:
        raise ValueError("percentile() 需要非空序列")
    if not 0.0 <= q <= 1.0:
        raise ValueError("q 必须在 [0, 1] 内")
    n = len(sorted_values)
    if n == 1:
        return sorted_values[0]
    pos = q * (n - 1)
    lo = math.floor(pos)
    hi = math.ceil(pos)
    if lo == hi:
        return sorted_values[int(pos)]
    frac = pos - lo
    return sorted_values[lo] * (1.0 - frac) + sorted_values[hi] * frac


def confidence_interval(samples: Sequence[float], level: float) -> tuple[float, float]:
    """percentile 法置信区间。samples 顺序无关，内部先排序。"""
    ordered = sorted(samples)
    alpha = 1.0 - level
    low = percentile(ordered, alpha / 2.0)
    high = percentile(ordered, 1.0 - alpha / 2.0)
    return low, high


# ---------------------------------------------------------------------------
# 主入口：grouped bootstrap（03 §7.2 合法做法）
# ---------------------------------------------------------------------------


def grouped_bootstrap_mean(
    observations: Sequence[Observation],
    n_boot: int = DEFAULT_N_BOOT,
    seed: int = DEFAULT_SEED,
    level: float = DEFAULT_LEVEL,
    statistic: Statistic = mean,
) -> BootstrapResult:
    """按事实簇整簇重采样并计算统计量的 bootstrap 区间。

    - 重采样单位是 fact_cluster_id：抽中簇时簇内全部变体（EN/ZH/口语）整簇带入；
      同一簇重复抽中时，簇内所有变体按相同重复次数出现。
    - 每次重采样在"展开后的查询多重集合"上重新计算统计量（分母随之变化）。
    - 固定 seed 完全可复现。
    """
    if not observations:
        raise ValueError("observations 不能为空")
    point = statistic([obs.value for obs in observations])
    samples: list[float] = []
    for sample in iter_grouped_resamples(observations, n_boot, seed):
        samples.append(statistic([obs.value for obs in sample]))
    ci_low, ci_high = confidence_interval(samples, level)
    return BootstrapResult(
        statistic=getattr(statistic, "__name__", "statistic"),
        point_estimate=point,
        ci_low=ci_low,
        ci_high=ci_high,
        level=level,
        n_boot=n_boot,
        samples=tuple(samples),
    )


def grouped_bootstrap_paired(
    observations: Sequence[PairedObservation],
    n_boot: int = DEFAULT_N_BOOT,
    seed: int = DEFAULT_SEED,
    level: float = DEFAULT_LEVEL,
    statistic: Statistic = mean,
) -> PairedBootstrapResult:
    """成对比较的 grouped bootstrap：同一批簇抽取同时应用于 A/B 两个系统。

    用于 03 §7.1 预注册规则的差值输入（如简中→英 MRR 差、全体 MRR 差）：
    差值区间必须建立在同一重采样集合上，不能各自独立抽样后再相减。
    """
    if not observations:
        raise ValueError("observations 不能为空")
    if n_boot <= 0:
        raise ValueError("n_boot 必须为正整数")
    point_a = statistic([obs.value_a for obs in observations])
    point_b = statistic([obs.value_b for obs in observations])
    by_cluster = index_paired_by_cluster(observations)
    unique_clusters = sorted(by_cluster)
    rng = random.Random(seed)
    samples_a: list[float] = []
    samples_b: list[float] = []
    samples_diff: list[float] = []
    for _ in range(n_boot):
        drawn = draw_clusters(unique_clusters, rng)
        sample = [obs for cluster_id in drawn for obs in by_cluster[cluster_id]]
        stat_a = statistic([obs.value_a for obs in sample])
        stat_b = statistic([obs.value_b for obs in sample])
        samples_a.append(stat_a)
        samples_b.append(stat_b)
        samples_diff.append(stat_a - stat_b)
    return PairedBootstrapResult(
        point_a=point_a,
        point_b=point_b,
        point_diff=point_a - point_b,
        ci_a=confidence_interval(samples_a, level),
        ci_b=confidence_interval(samples_b, level),
        ci_diff=confidence_interval(samples_diff, level),
        level=level,
        n_boot=n_boot,
        samples_diff=tuple(samples_diff),
    )


# ---------------------------------------------------------------------------
# 对照实现：逐题独立 bootstrap（03 §7.2 指出的错误做法，仅限测试对照）
# ---------------------------------------------------------------------------


def per_question_bootstrap_mean(
    values: Sequence[float],
    n_boot: int = DEFAULT_N_BOOT,
    seed: int = DEFAULT_SEED,
    level: float = DEFAULT_LEVEL,
) -> BootstrapResult:
    """把每道题当独立样本的逐题 bootstrap。

    03 §7.2 明确这不是合法的评测不确定性估计（忽略簇内相关）；
    仅用于合成数据单元测试与 grouped 结果对照，禁止进入正式评测结论。
    """
    if not values:
        raise ValueError("values 不能为空")
    rng = random.Random(seed)
    n = len(values)
    samples = tuple(
        mean([values[rng.randrange(n)] for _ in range(n)]) for _ in range(n_boot)
    )
    ci_low, ci_high = confidence_interval(samples, level)
    return BootstrapResult(
        statistic="mean(per-question)",
        point_estimate=mean(values),
        ci_low=ci_low,
        ci_high=ci_high,
        level=level,
        n_boot=n_boot,
        samples=samples,
    )

#!/usr/bin/env python3
"""RAGAS 四指标评分器（Faithfulness / AnswerRelevancy / ContextPrecision / ContextRecall）。

- 裁判 LLM = DashScope OpenAI 兼容端点 qwen-plus，temp=0；embeddings = text-embedding-v4。
- key 只从环境变量 BAILIAN_API_KEY 读取，输出零明文。
- 调用与 token 计数内嵌（计费对账用），随输出落盘。
- --sample N --seed S：固定种子抽 N 题复评（抖动估计），输出独立文件不覆盖全量。

用法：
  BAILIAN_API_KEY=... python3 score_ragas.py \
      --input <ragas-input-on.jsonl> --out <scores-on.jsonl> [--sample 12 --seed 20260927]
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import random
import sys

from langchain_core.outputs import ChatGeneration, ChatResult
from langchain_openai import ChatOpenAI, OpenAIEmbeddings
from ragas import evaluate
from ragas.evaluation import EvaluationDataset
from ragas.embeddings import LangchainEmbeddingsWrapper
from ragas.llms import LangchainLLMWrapper
from ragas.metrics import AnswerRelevancy, ContextPrecision, ContextRecall, Faithfulness
from ragas.run_config import RunConfig
from ragas import SingleTurnSample

JUDGE_MODEL = "qwen-plus"
EMBED_MODEL = "text-embedding-v4"
DASHSCOPE_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"


class _Counters:
    judge_calls = 0
    judge_in_tokens = 0
    judge_out_tokens = 0
    embed_calls = 0
    embed_chars = 0


class CountingChatOpenAI(ChatOpenAI):
    """计量包装：累计调用次数与 usage token（对账用，不改行为）。

    RAGAS 走异步路径，_generate 与 _agenerate 都要挂，两条路径互斥不会双计。
    """

    def _generate(self, messages, stop=None, run_manager=None, **kwargs):
        result: ChatResult = super()._generate(messages, stop=stop, run_manager=run_manager, **kwargs)
        self._tally(result)
        return result

    async def _agenerate(self, messages, stop=None, run_manager=None, **kwargs):
        result: ChatResult = await super()._agenerate(messages, stop=stop, run_manager=run_manager, **kwargs)
        self._tally(result)
        return result

    @staticmethod
    def _tally(result: ChatResult):
        _Counters.judge_calls += 1
        gen: ChatGeneration = result.generations[0]
        usage = getattr(gen.message, "usage_metadata", None) or {}
        _Counters.judge_in_tokens += int(usage.get("input_tokens") or 0)
        _Counters.judge_out_tokens += int(usage.get("output_tokens") or 0)


class CountingEmbeddings(OpenAIEmbeddings):

    def embed_documents(self, texts):
        _Counters.embed_calls += 1
        _Counters.embed_chars += sum(len(t) for t in texts)
        return super().embed_documents(texts)

    def embed_query(self, text):
        _Counters.embed_calls += 1
        _Counters.embed_chars += len(text)
        return super().embed_query(text)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--sample", type=int, default=0, help="抽 N 题复评（抖动），0=全量")
    ap.add_argument("--seed", type=int, default=20260927)
    ap.add_argument("--max-workers", type=int, default=4)
    ap.add_argument("--tag", default="", help="输出行标注（full/jitter）")
    args = ap.parse_args()

    api_key = os.environ.get("BAILIAN_API_KEY", "")
    if not api_key:
        print("[score] 缺 BAILIAN_API_KEY", file=sys.stderr)
        return 2

    rows = [json.loads(l) for l in open(args.input, encoding="utf-8") if l.strip()]
    if args.sample:
        rng = random.Random(args.seed)
        rows = sorted(rng.sample(rows, min(args.sample, len(rows))), key=lambda r: r["q_id"])

    judge = CountingChatOpenAI(model=JUDGE_MODEL, temperature=0, api_key=api_key,
                               base_url=DASHSCOPE_BASE, timeout=120, max_retries=2)
    embed = CountingEmbeddings(model=EMBED_MODEL, api_key=api_key,
                               base_url=DASHSCOPE_BASE, timeout=120, check_embedding_ctx_length=False)
    judge_w = LangchainLLMWrapper(judge)
    embed_w = LangchainEmbeddingsWrapper(embed)

    samples = []
    meta = []
    for r in rows:
        reference = "; ".join(r.get("acceptable_answers") or [])
        if not reference and r.get("gold_span"):
            reference = r["gold_span"]
        samples.append({
            "user_input": r["question"],
            "retrieved_contexts": r["contexts"],
            "response": r["answer"],
            "reference": reference,
        })
        meta.append(r)

    metrics = [
        Faithfulness(llm=judge_w),
        AnswerRelevancy(llm=judge_w, embeddings=embed_w),
        ContextPrecision(llm=judge_w),
        ContextRecall(llm=judge_w),
    ]
    dataset = evaluate(
        EvaluationDataset([SingleTurnSample(**s) for s in samples]),
        metrics=metrics,
        llm=judge_w,
        embeddings=embed_w,
        run_config=RunConfig(max_workers=args.max_workers, timeout=600, max_retries=2),
        show_progress=True,
    )

    stamp = dt.datetime.now().astimezone().isoformat()
    df = dataset.to_pandas()
    n = 0
    with open(args.out, "w", encoding="utf-8") as f:
        for r, (_, srow) in zip(meta, df.iterrows()):
            row = {
                "q_id": r["q_id"],
                "arm": r["arm"],
                "tag": args.tag or ("jitter" if args.sample else "full"),
                "seed": args.seed if args.sample else None,
                "scored_at": stamp,
                "judge_model": JUDGE_MODEL,
                "ragas_version": __import__("ragas").__version__,
                "faithfulness": None if srow.get("faithfulness") != srow.get("faithfulness") else srow.get("faithfulness"),
                "answer_relevancy": None if srow.get("answer_relevancy") != srow.get("answer_relevancy") else srow.get("answer_relevancy"),
                "context_precision": None if srow.get("context_precision") != srow.get("context_precision") else srow.get("context_precision"),
                "context_recall": None if srow.get("context_recall") != srow.get("context_recall") else srow.get("context_recall"),
                "n_contexts": len(r["contexts"]),
                "contexts_source": r["contexts_source"],
            }
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
            n += 1

    counters = {
        "judge_calls": _Counters.judge_calls,
        "judge_in_tokens": _Counters.judge_in_tokens,
        "judge_out_tokens": _Counters.judge_out_tokens,
        "embed_calls": _Counters.embed_calls,
        "embed_chars": _Counters.embed_chars,
        "samples": n,
        "scored_at": stamp,
        "sample_mode": args.sample or "full",
        "seed": args.seed,
    }
    with open(args.out + ".counters.json", "w", encoding="utf-8") as f:
        json.dump(counters, f, ensure_ascii=False, indent=1)
    print(f"[score] rows={n} counters={json.dumps(counters, ensure_ascii=False)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""任务 B 对话链生成器：12 链 × 20-30 轮，早期埋约束、末轮依赖约束。

冻结规则（REPORT-agent-memory.md §1）：
- 约束类型 6 类 × 2 链；第 1-3 轮自然埋约束；末轮提问依赖约束；
- 中间轮 = evalset-core 抽题 + 固定追问模板交替（驱动 search_knowledge 与长答案）；
- 链长（轮数）由链长测算规则定（--turns 参数传入，两臂同链表）。

用法：
  python3 gen_chains.py --evalset <evalset.jsonl> --turns 24 --out chains.jsonl
"""
from __future__ import annotations

import argparse
import json
import random

# 6 类约束 × 2 变体（chain 内 position 固定：T1 埋主约束，T3 埋补充前提）
CONSTRAINTS = [
    {"type": "identity", "t1": "你好，我是从内地来香港读本科一年级的学生，之前没接触过这里的选课制度。",
     "t3": "对了我只修过基础数学和英语课，专业方向还没定。",
     "final": "结合我刚才跟你说的我的情况，你觉得我这学期选课应该注意什么？"},
    {"type": "budget", "t1": "你好，我下学年总预算只有 8 万港币，超出这个数我承受不了。",
     "t3": "另外我已经自己租好房了，住宿这块不用再花钱。",
     "final": "考虑到我最开始说的预算情况，你建议我怎么安排？还有哪些花费我必须预留？"},
    {"type": "time", "t1": "你好，我只有在 2027 年 6 月之后的周末才有空处理各种申请和手续。",
     "t3": "工作日我全程要实习，完全走不开。",
     "final": "那我这个时间安排（只有 2027 年 6 月后的周末有空）走这些流程可行吗？应该怎么排？"},
    {"type": "materials", "t1": "你好，我已经准备好了本科成绩单和两位老师的推荐信。",
     "t3": "我的英语成绩单还没考，打算下个月考。",
     "final": "以我已经准备好的材料来看，接下来我还缺什么、需要补交什么？"},
    {"type": "intent", "t1": "你好，我打算下学期申请交换项目，现在开始想清楚每一步该做什么。",
     "t3": "我想去英语地区的学校，语言不是障碍。",
     "final": "回到我最开始说的目标——申请交换项目，结合前面聊的内容，第一步我该从哪里开始？"},
    {"type": "preference", "t1": "你好，我平时习惯只用电子邮件沟通，不想接电话。",
     "t3": "我也基本不看学校通知栏，只看邮件。",
     "final": "按我的沟通习惯，这件事我应该用哪种方式联系他们最合适？"},
]

FOLLOWUPS = ["能再展开讲讲吗？", "具体流程是什么样的？", "有没有截止日期要注意？",
             "需要提前准备什么材料？", "一般在哪里办理？", "有什么常见的坑要避开？",
             "处理这件事大概要多久？", "费用大概是多少？"]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--evalset", required=True)
    ap.add_argument("--turns", type=int, required=True, help="每链轮数（20-30）")
    ap.add_argument("--seed", type=int, default=20260927)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    assert 20 <= args.turns <= 30, "冻结规则：链长 20-30 轮"
    rng = random.Random(args.seed)

    pool = [json.loads(l) for l in open(args.evalset, encoding="utf-8") if l.strip()]
    questions = [q["question"] for q in pool if q.get("answerable")]
    rng.shuffle(questions)

    chains = []
    qi = 0
    for ci, cons in enumerate(CONSTRAINTS):
        for variant in (0, 1):
            chain_id = f"b-{cons['type']}-{variant}"
            turns = []
            turns.append({"no": 1, "question": cons["t1"]})
            turns.append({"no": 2, "question": questions[qi % len(questions)]})
            qi += 1
            turns.append({"no": 3, "question": cons["t3"]})
            # 中间轮：evalset 题 + 追问模板交替（留末轮位置）
            for no in range(4, args.turns):
                if no % 2 == 0:
                    q = questions[qi % len(questions)]
                    qi += 1
                else:
                    base = turns[-1]["question"]
                    q = f"{base}——{FOLLOWUPS[(no // 2) % len(FOLLOWUPS)]}" if len(base) < 60 \
                        else FOLLOWUPS[(no // 2) % len(FOLLOWUPS)]
                turns.append({"no": no, "question": q})
            turns.append({"no": args.turns, "question": cons["final"]})
            chains.append({
                "chain_id": chain_id,
                "constraint_type": cons["type"],
                "constraint_t1": cons["t1"],
                "constraint_t3": cons["t3"],
                "final_question": cons["final"],
                "n_turns": args.turns,
                "turns": turns,
            })

    with open(args.out, "w", encoding="utf-8") as f:
        for c in chains:
            f.write(json.dumps(c, ensure_ascii=False) + "\n")
    print(f"[gen_chains] chains={len(chains)} turns_each={args.turns} -> {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

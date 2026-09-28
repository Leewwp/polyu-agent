# evaluation/ — 可复跑的 RAGAS 检索评测工具链

> 2026-09-27 首建并首次应用：以 RAGAS 四指标对 rerank 精排阶段做双臂消融
> （58 题双语金标题集、判据预注册、簇级 bootstrap 显著性）。
> 金标尺题集（gold 标注）、原始实验输出与实验报告属私有评测资产，不入库；
> 本目录只包含可复跑的工具链。复现一次完整双臂消融需要自备：本地起栈、
> 自有题集、DashScope 兼容端点 key（`BAILIAN_API_KEY` 环境变量，零明文输出）。

## 设计要点

- **判据预注册**：指标、判定规则、bootstrap 参数（n_boot=10000 / 95% / 固定 seed）
  在数据采集前冻结；跑数后只可补充分析，不改判据。
- **确定性指标优先**：gold chunk/doc hit@10、MRR@10、NDCG@10 等排序指标不依赖
  LLM 裁判，由 `summarize.py` / `ranking_metrics.py` 直接从 chunk id 对算出；
  RAGAS 四指标作为 LLM-as-judge 层并列披露（含抖动复评与裁判校准）。
- **不接 CI**：裁判是付费 LLM 端点且 LLM 评分存在抖动，不适合做阻塞门；
  本层定位为手动触发的复跑工具链。

## 复跑节奏（触发条件）

以下任一改动落地后复跑一次双臂对比（或多臂）：
- **chunking 参数**（chunk 大小/重叠/解析管线）；
- **embedding 模型或维度**（ai.embedding.candidates / rag.default.dimension）；
- **召回参数**（top-k / recall-budget / rrf-k / rerank-candidate-limit / 证据闸门下限）；
- **rerank 模型或开关**（ai.rerank.candidates / rag.rerank.enabled）。

## 环境准备（一次性）

```bash
# python 独立 venv（RAGAS 需要 3.10+；用 uv 拉独立解释器，不改全局）
uv venv ~/.venvs/polyu-ragas --python 3.11
uv pip install --python ~/.venvs/polyu-ragas/bin/python ragas langchain-openai 'langchain-community<0.4'
```

版本锁定（2026-09-27 首跑实录，升级前先复跑基线对照）：
- python 3.11.16 / ragas **0.4.3** / langchain-openai 1.6.6 / langchain-community 0.3.31
  （0.4.x 移除了 chat_models.vertexai，ragas 0.4.3 的 import 需要它，故锁 <0.4）
- 裁判 LLM = DashScope 兼容端点 `qwen-plus`（temp=0）；embeddings = `text-embedding-v4`（dim 1024）
- key：`BAILIAN_API_KEY` 环境变量传入，任何输出零明文

## 全流程（双臂消融）

```bash
RAW=<raw-dir>   # 原始输出目录（不入库）

# 0. 起栈（ON 臂=默认配置）。chat 链路真实 contexts 的旁路 dump 依赖实验埋点
#    （RAG_EVAL_DUMP_FILE 门控 JSONL 追加器；埋点在实验分支，未合入主线，
#    自有部署可加等价旁路或仅用漏斗口径）
RAG_EVAL_DUMP_FILE=$RAW/dump-on.jsonl <start-backend.sh>

# 1. 当日池导出 + gold 映射（locate 率必须 1.0，<1.0 逐题披露）
python3 export_pool.py --out $RAW/pool-$(date +%Y%m%d).jsonl
python3 map_gold.py --evalset <evalset.jsonl> --pool $RAW/pool-*.jsonl --out $RAW/gold-map.json

# 2. 采集（题间限速 1.2s；断点续采；凭据走 EXP_CREDS）
EXP_CREDS='<user>:<pass>' \
python3 collect_arms.py --arm on --evalset <evalset.jsonl> --out $RAW/triples-on.jsonl

# 3. 切臂重启（OFF 臂：rerank 关 + 证据闸门置 0——闸门启动守卫要求，语义等价见实验报告）
RAG_EVAL_DUMP_FILE=$RAW/dump-off.jsonl RAG_RERANK_ENABLED=false \
RAG_SEARCH_EVIDENCE_MIN_RERANK_SCORE=0 <start-backend.sh>
EXP_CREDS=... python3 collect_arms.py --arm off ...

# 4. 合并 + 评分（三题冒烟先行：--limit 3 / 直接对 smoke 文件跑）
python3 build_ragas_input.py --triples $RAW/triples-on.jsonl --dump $RAW/dump-on.jsonl \
  --evalset <evalset.jsonl> --goldmap $RAW/gold-map.json --out $RAW/ragas-input-on.jsonl
BAILIAN_API_KEY=... ~/.venvs/polyu-ragas/bin/python score_ragas.py \
  --input $RAW/ragas-input-on.jsonl --out $RAW/scores-full-on.jsonl
# 抖动复评（固定 seed 抽 12 题）
... score_ragas.py ... --out $RAW/scores-jitter-on.jsonl --sample 12 --seed 20260927

# 5. 汇总（两臂×四指标 + 配对簇级 bootstrap CI + hit@10 + A5 自动对照）
python3 summarize.py --dir $RAW --evalset <evalset.jsonl> --out $RAW/summary.json
# 确定性排序指标（matched-TopK 对照：两臂 MRR/NDCG/Hit@10 + hit 差值 CI）
python3 ranking_metrics.py --dir $RAW --out $RAW/ranking.json
# A5 人工抽检表（语言×Faithfulness 分位分层，等人工填写）
python3 make_calibration_sheet.py --dir $RAW --n 20 --out $RAW/calibration-sheet.csv
```

## A5 裁判校准：一致率口径与 kappa 扩样边界

- **n=20 不算 kappa**：小样本下 kappa 置信区间宽度 ±0.25–0.35 量级，0.6 与 0.8
  不可区分；且 kappa 对类别偏斜敏感（高一致低 kappa 悖论，Feinstein & Cicchetti 1990）。
  现口径 = 一致率点估计 + Wilson 区间 + 类分布 + 分歧逐条归因。
- **扩样门槛**：n≥50 可算 kappa（CI 收窄到 ±0.15 内）；n≥80 区间才有区分力。
  扩样 = 加大 calibration sheet 行数（`--n 50` / `--n 80`）+ 抽检时间相应增加
  （约 2-4 分钟/条）。扩样前只报一致率口径。

## 脚本清单

| 脚本 | 作用 |
| --- | --- |
| `bootstrap.py` | 簇级（fact_cluster）配对 bootstrap 公共实现：重采样单位、预注册参数（n_boot/level/seed）的单一事实源 |
| `export_pool.py` | 当日可检索 chunk 池（chunk∩vector∩enabled）导出 |
| `map_gold.py` | gold_span → chunk id 集合（locate 率门） |
| `collect_arms.py` | 双臂采集器：/rag/eval 漏斗 + /agent/v1/chat SSE 答案 |
| `build_ragas_input.py` | 三元组 + 埋点时间窗关联 → RAGAS 输入 |
| `score_ragas.py` | RAGAS 四指标评分（judge/embeddings 计数内嵌） |
| `summarize.py` | 两臂总表 + 配对簇级 bootstrap CI + hit@10 + A5 自动对照 |
| `ranking_metrics.py` | 确定性排序指标：两臂 MRR@10/NDCG@10/Hit@10 与 hit@10 差值簇级 bootstrap CI（matched-TopK 排序对照） |
| `make_calibration_sheet.py` | A5 人工抽检表（语言 × Faithfulness 分位分层） |
| `gen_chains.py` / `run_chains.py` / `judge_b.py` / `summarize_b.py` | agent.memory 评测：链生成/跑链/盲评裁判/体积汇总 |

## 采集面口径（重要）

chat 链路（生产 agent）检索面 ≠ /rag/eval 漏斗：前者多 KB-only 意图过滤、
歧义引导门、agent 查询收窄（可多次 search_knowledge）与检索合成 LLM 步骤。
本层 contexts 主口径 = **旁路 dump 的 chat 链路真实 chunk**（build_ragas_input
时间窗关联），漏斗 contexts 仅作回退与过程指标。file:line 证据见实验报告（不入库）。

## matched-TopK 排序对照（口径说明）

`rag.rerank.enabled=false` 关闭的是整个精排后处理阶段（排序 + 最终 top-k 截断 +
依赖精排分的证据闸门），不是单独拿掉一次模型重排。因此：

- **Context Precision 的臂间差**度量的是「完整精排阶段」的联合效应；
- **`ranking_metrics.py` 的漏斗口径**构成 matched-TopK 对照：OFF 臂漏斗无截断、
  返回序即融合序，取前 k=10 即「无精排排序的 top-10」；ON 臂即「精排后 top-10」。
  同检索、同候选池、同 k，两臂 hit@10/MRR/NDCG 差值即**排序规则本身**的贡献
  （caveat：ON 臂证据闸门偶尔将 top-10 滤至 <10 条；精排候选输入可能受
  rerank-candidate-limit 截断——两者均属精排阶段的部署语义）。

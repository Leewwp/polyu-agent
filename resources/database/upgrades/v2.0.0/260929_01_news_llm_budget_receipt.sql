-- 260929 资讯 LLM 预算护栏+最小付费回执（#184，父票 #181 §1 合同+维护者六点修正）
-- 新表 t_news_llm_receipt：一行=（请求指纹, 发生日）周期账本行。
--   周期账本（修正点3）：周期键 stat_date/stat_month 首次计入时落定且永不改写——
--   跨日/跨月重试在新周期行续算，历史归属不搬移；日/月额度（日 ¥1.0/月 ¥10，维护者
--   2026-09-29 指定保守默认；资讯 LLM 专用独立额度，与全项目成本口径分开统计不混算）
--   按 stat_date/stat_month 聚合本表，重启不清零。
--   预算口径：attempts=真实发出次数（含路由 fallback 与网关重试，write-ahead 发出前记账，
--   未知结果保守预留）；超额当日降级=仅入库不富化，次日按配额补偿（DEGRADED 行=降级事件）。
--   成本上界（修正点2）：FAST 链（ai.chat.tiers.fast.candidates，现行 [qwen-flash, qwen-plus]）
--   最贵已配价候选 qwen-plus ×完整请求限额（输入 4000+2000=6000、输出 1024）：
--   (6000×0.8+1024×2)/1e6 ≈ ¥0.006848/次；单价表外置 rag.news.budget-model-prices
--   （百炼北京区列表价非思考档，docs.bailian.console.aliyun.com 2026-09-29 核对）。
--   重试累计（修正点4）：retries 同指纹全行累计 ≤ rag.news.llm-max-retries（默认 2，
--   跨调度/重启从本表续算，耗尽即不再重试）。
--   回执口径：请求指纹=sha256(实际渲染提示词全文+模型+temperature/topP/maxTokens)；
--   成功响应先落库再用（response_text），同指纹重跑复用不重复付费；
--   复用解析无效清除并隔离（POISONED，不无限复读）。
-- 只出 SQL 文件走 upgrades 管道，不直接动库；新环境走 schema_pg.sql 全量初始化已含本表。
-- 本脚本幂等：CREATE TABLE IF NOT EXISTS（重复执行无害）。

CREATE TABLE IF NOT EXISTS t_news_llm_receipt (
  id                  BIGSERIAL PRIMARY KEY,
  request_fingerprint VARCHAR(64)  NOT NULL,            -- sha256（提示词全文+模型+影响输出参数）
  stat_date           DATE         NOT NULL,            -- 本行发出所处日（HKT 日切，行落定后不改写）
  stat_month          VARCHAR(7)   NOT NULL,            -- yyyy-MM（HKT，随 stat_date 落定不改写）
  model_id            VARCHAR(64),                      -- 指纹成分模型（Tier.FAST 主选 id）
  served_model_id     VARCHAR(64),                      -- 实际服务模型（最后一次成功目标）
  attempts            INT          NOT NULL DEFAULT 0,  -- 本周期行内真实发出次数（含 fallback/重试，write-ahead）
  retries             INT          NOT NULL DEFAULT 0,  -- 本周期行内网关重试数（同指纹跨行累计判定 ≤上限）
  cost_estimate       NUMERIC(12,6) NOT NULL DEFAULT 0, -- 本周期行成本（元）= 行内 attempts × 单次上界 ≈0.006848
  response_text       TEXT,                             -- 成功响应原文（先落库再用，复用不重付费）
  status              VARCHAR(16)  NOT NULL DEFAULT 'PENDING',  -- PENDING/SUCCESS/DEGRADED/FAILED/POISONED
  error_brief         VARCHAR(512),                     -- 最近失败摘要（不含提示词与响应内容）
  create_time         TIMESTAMP    NOT NULL DEFAULT now(),
  update_time         TIMESTAMP    NOT NULL DEFAULT now(),
  CONSTRAINT uq_news_llm_receipt UNIQUE (request_fingerprint, stat_date)
);
CREATE INDEX IF NOT EXISTS idx_news_llm_receipt_date ON t_news_llm_receipt(stat_date);
CREATE INDEX IF NOT EXISTS idx_news_llm_receipt_month ON t_news_llm_receipt(stat_month);

COMMENT ON TABLE t_news_llm_receipt IS '资讯 LLM 付费回执（#184）：（指纹,日）周期账本行，attempts 双口径计数+成本上界估算，预算聚合数据源（重启不清零）';
COMMENT ON COLUMN t_news_llm_receipt.request_fingerprint IS 'sha256(实际渲染提示词全文+模型+temperature/topP/maxTokens)；同指纹重跑复用响应不重复付费';
COMMENT ON COLUMN t_news_llm_receipt.stat_date IS '本行发出所处日（HKT 日切）——日预算聚合键；行落定后不改写，跨日重试在新行续算（维护者修正点3）';
COMMENT ON COLUMN t_news_llm_receipt.stat_month IS 'yyyy-MM（HKT）——月预算聚合键；随 stat_date 落定不改写';
COMMENT ON COLUMN t_news_llm_receipt.model_id IS '指纹成分中的模型（Tier.FAST 主选 id，配置期口径）';
COMMENT ON COLUMN t_news_llm_receipt.served_model_id IS '实际服务模型 id（最后一次成功发出的路由目标；未成功为 NULL）';
COMMENT ON COLUMN t_news_llm_receipt.attempts IS '本周期行内真实发出次数：含路由 fallback 与网关重试；write-ahead 发出前记账（未知结果保守预留，不承诺绝对不重复计费）';
COMMENT ON COLUMN t_news_llm_receipt.retries IS '本周期行内网关重试数；同指纹全行累计 ≤ rag.news.llm-max-retries（跨调度/重启续算，维护者修正点4）';
COMMENT ON COLUMN t_news_llm_receipt.cost_estimate IS '本周期行成本（元）= 行内 attempts × 单次上界（FAST 链最贵候选×完整限额，现行 ≈¥0.006848/次，推导见 rag.news.budget-model-prices 注释）';
COMMENT ON COLUMN t_news_llm_receipt.response_text IS '成功响应原文：先落库再用，同指纹重跑复用不重复付费；复用解析无效时清除并置 POISONED（不无限复读）';
COMMENT ON COLUMN t_news_llm_receipt.status IS 'PENDING=发出中；SUCCESS=已回执；DEGRADED=预算耗尽降级（次日补偿后翻转）；FAILED=重试耗尽或解析失败；POISONED=复用响应持续无效已隔离';
COMMENT ON COLUMN t_news_llm_receipt.error_brief IS '最近一次失败原因摘要（诊断用，不含提示词与响应内容）';

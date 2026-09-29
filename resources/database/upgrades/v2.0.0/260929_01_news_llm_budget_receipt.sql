-- 260929 资讯 LLM 预算护栏+最小付费回执（#184，父票 #181 §1 施工合同）
-- 新表 t_news_llm_receipt：一行=一次逻辑请求（请求指纹全局唯一）。
--   预算口径：attempts=真实发出次数（含路由 fallback 与网关重试，write-ahead 发出前记账）；
--   日/月额度（默认 ¥1.0/¥15，rag.news.budget-* 外置）按 stat_date/stat_month 聚合本表，
--   重启不清零；超额当日降级=仅入库不富化，次日按配额补偿（status=DEGRADED 行可查降级事件）。
--   回执口径：请求指纹=sha256(实际渲染提示词全文+模型+temperature/topP/maxTokens)；
--   成功响应先落库再用（response_text），同指纹重跑复用不重复付费；复用解析无效清除并隔离（POISONED）。
-- 只出 SQL 文件走 upgrades 管道，不直接动库；新环境走 schema_pg.sql 全量初始化已含本表。
-- 本脚本对已有环境幂等：CREATE TABLE IF NOT EXISTS（重复执行无害）。

CREATE TABLE IF NOT EXISTS t_news_llm_receipt (
  id                  BIGSERIAL PRIMARY KEY,
  request_fingerprint VARCHAR(64)  NOT NULL,            -- sha256（提示词全文+模型+影响输出参数）
  stat_date           DATE         NOT NULL,            -- 最近发出所处日（HKT 日切，预算按日聚合键）
  stat_month          VARCHAR(7)   NOT NULL,            -- yyyy-MM（HKT，预算按月聚合键）
  model_id            VARCHAR(64),                      -- 指纹成分模型（Tier.FAST 主选 id）
  served_model_id     VARCHAR(64),                      -- 实际服务模型（最后一次成功目标）
  attempts            INT          NOT NULL DEFAULT 0,  -- 真实发出次数（含 fallback/重试，write-ahead）
  retries             INT          NOT NULL DEFAULT 0,  -- 网关层逻辑重试次数（attempts 子集口径）
  cost_estimate       NUMERIC(12,6) NOT NULL DEFAULT 0, -- 估算成本（元）= attempts × 单次封顶（默认 ¥0.005）
  response_text       TEXT,                             -- 成功响应原文（先落库再用，复用不重付费）
  status              VARCHAR(16)  NOT NULL DEFAULT 'PENDING',  -- PENDING/SUCCESS/DEGRADED/FAILED/POISONED
  error_brief         VARCHAR(512),                     -- 最近失败摘要（不含提示词与响应内容）
  create_time         TIMESTAMP    NOT NULL DEFAULT now(),
  update_time         TIMESTAMP    NOT NULL DEFAULT now(),
  CONSTRAINT uq_news_llm_receipt UNIQUE (request_fingerprint)
);
CREATE INDEX IF NOT EXISTS idx_news_llm_receipt_date ON t_news_llm_receipt(stat_date);
CREATE INDEX IF NOT EXISTS idx_news_llm_receipt_month ON t_news_llm_receipt(stat_month);

COMMENT ON TABLE t_news_llm_receipt IS '资讯 LLM 付费回执（#184）：请求指纹唯一，attempts 双口径计数+成本估算，预算聚合数据源（重启不清零）';
COMMENT ON COLUMN t_news_llm_receipt.request_fingerprint IS 'sha256(实际渲染提示词全文+模型+temperature/topP/maxTokens)；同指纹重跑复用响应不重复付费';
COMMENT ON COLUMN t_news_llm_receipt.stat_date IS '最近发出所处日（HKT 日切）——预算按日聚合键；行跨日续发时归属最近发出日';
COMMENT ON COLUMN t_news_llm_receipt.stat_month IS 'yyyy-MM（HKT）——预算按月聚合键';
COMMENT ON COLUMN t_news_llm_receipt.model_id IS '指纹成分中的模型（Tier.FAST 主选 id，配置期口径）';
COMMENT ON COLUMN t_news_llm_receipt.served_model_id IS '实际服务模型 id（最后一次成功发出的路由目标；未成功为 NULL）';
COMMENT ON COLUMN t_news_llm_receipt.attempts IS '真实发出次数：含路由 fallback 与网关重试；write-ahead 发出前记账（未知结果保守预留，不承诺绝对不重复计费）';
COMMENT ON COLUMN t_news_llm_receipt.retries IS '网关层逻辑重试次数（≤rag.news.llm-max-retries，attempts 的子集口径供双口径核对）';
COMMENT ON COLUMN t_news_llm_receipt.cost_estimate IS '估算成本（元）= attempts × rag.news.budget-cost-per-attempt-yuan（5k 入+1k 出 flash 原价 ≈¥0.005 封顶）';
COMMENT ON COLUMN t_news_llm_receipt.response_text IS '成功响应原文：先落库再用，同指纹重跑复用不重复付费；复用解析无效时清除并置 POISONED（不无限复读）';
COMMENT ON COLUMN t_news_llm_receipt.status IS 'PENDING=发出中；SUCCESS=已回执；DEGRADED=预算耗尽降级（次日补偿后翻转）；FAILED=重试耗尽或解析失败；POISONED=复用响应持续无效已隔离';
COMMENT ON COLUMN t_news_llm_receipt.error_brief IS '最近一次失败原因摘要（诊断用，不含提示词与响应内容）';

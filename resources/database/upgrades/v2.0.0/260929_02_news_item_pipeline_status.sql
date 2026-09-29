-- 260929 资讯管线状态合同（#185，父票 #180 §2/§3/§5）
-- t_news_item 加列（设计取舍：status 单列扩展五态 + 三个伴随列，不建独立状态表——
-- 单行原子状态转移、url_hash 幂等键不动、既有公开面已按 status 过滤零回填迁移）：
--   status 五态：pending=待富化（已准入，付费队列成员）/ published=发布资格已就绪
--   （公开可见还须过发布门）/ archived=终态·旧文归档（发现时原文发布超 48h，
--   不进「今天」、跳过付费富化、不计日准入）/ expired=终态·待富化超龄（首次发现
--   起超过 48h 未获资格，退出待办）/ hidden=人工下架（沿用）。
--   存量 published 行零回填：eligible_time 为 NULL=历史行，发布门视同早已开启
--   （历史不重算），公开可见性行为不变。
--   eligible_time：发布资格就绪时刻（合格摘要落库或明示零调用回退）——发布门 180s
--   从本列起算，查询侧统一判据 status='published' AND (eligible_time IS NULL
--   OR eligible_time <= now-180s)；预算延期（DEGRADED）/无效摘要（FAILED/POISONED）
--   /待富化（pending）不落 published，不能靠 180s 超时放行。
--   summary_source：llm=LLM 富化 / fallback=明示零调用回退（标题派生双语摘要，
--   守卫拒绝（复用响应）或回执终态时的可解释回退，不触发无限付费重试）/ NULL=历史行。
--   prompt_version：sha256(提示词模板全文) 前 12 位，随富化/回退落行——改词只影响
--   新资料，历史行不自动重算（可追溯）。
-- 容量合同：全站新准入 ≤60 条/日（含既有源；19 新增源局部合计 200 只是局部上限）、
--   每轮富化 ≤20 条×3 轮/日、按源公平轮转（源 sourceKey 升序+发布时间倒序确定性排序）。
--   日准入计数从 fetch_time+status 现推（status<>'archived'），重启不重置。
-- 索引：待富化 TTL 收尾与选题扫描（status='pending' 偏索引）、日准入计数（fetch_time）。
-- 只出 SQL 走 upgrades 管道（本地栈验证，生产应用由维护者派发）；新环境走
--   schema_pg.sql 全量初始化已含本变更。本脚本幂等：ADD COLUMN IF NOT EXISTS /
--   CREATE INDEX IF NOT EXISTS（重复执行无害）。

ALTER TABLE t_news_item ADD COLUMN IF NOT EXISTS eligible_time TIMESTAMP;
ALTER TABLE t_news_item ADD COLUMN IF NOT EXISTS summary_source VARCHAR(8);
ALTER TABLE t_news_item ADD COLUMN IF NOT EXISTS prompt_version VARCHAR(16);

CREATE INDEX IF NOT EXISTS idx_news_item_pending_ttl ON t_news_item(fetch_time) WHERE status = 'pending';
CREATE INDEX IF NOT EXISTS idx_news_item_fetch_day ON t_news_item(fetch_time);

COMMENT ON COLUMN t_news_item.status IS '处理状态五态（#185）：pending=待富化（已准入）；published=发布资格就绪（公开可见还须过 180s 发布门，见 eligible_time）；archived=终态·旧文归档（发现时原文发布超 48h，不计日准入）；expired=终态·待富化超龄（48h 未获资格，退出待办）；hidden=人工下架';
COMMENT ON COLUMN t_news_item.eligible_time IS '发布资格就绪时刻（#185）：合格摘要落库或明示零调用回退时间；发布门 180s 从本列起算——统一公开资格=status=published AND (本列 IS NULL OR 本列 <= now-180s)；NULL=#185 前历史行（视同早已开启，不重算）';
COMMENT ON COLUMN t_news_item.summary_source IS '摘要产出方式（#185）：llm=LLM 富化；fallback=明示零调用回退（标题派生，守卫/回执终态的可解释回退）；NULL=历史行';
COMMENT ON COLUMN t_news_item.prompt_version IS '产出摘要所用提示词模板版本（#185）：sha256(模板全文) 前 12 位；改词即版本变化只影响新资料，历史不自动重算；fallback 无提示词为 NULL';

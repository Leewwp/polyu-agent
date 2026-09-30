-- 260930 资讯源治理（#186，父票 #181 §2）
-- 设计取舍：停用原因落在 t_news_source 单列 disabled_reason（单行原子读写、admin 面板
-- 单表现推，不建独立状态表）；「停止/复归/探活记录可查」另立 append-only 事件表
-- t_news_source_health_event（审计流水与当前态分离——当前态可 UPDATE，流水只 INSERT）。
--
-- disabled_reason 三分：
--   manual=人工停用（seed 明示停用/维护者 SQL 置停）：不探活、不自动解禁；
--   auto=自动隔离（连续 3 次失败滞回，#186 前 enabled=false 唯一成因）：唯一探活对象
--   ——日级探活连续两次有效完整成功（间隔 ≤48h）自动复归；
--   policy=策略禁止（robots.txt Disallow/出站守卫拒绝）：不因可达自动解禁，复归=人工；
--   NULL=启用中。三类停用互不误复活：探活扫描只取 enabled=false AND disabled_reason='auto'。
--
-- 既有禁用行迁移判据（保守原则：未知原因不得一律当自动故障复活）：
--   enabled=false AND disabled_reason IS NULL 的存量行按 consecutive_failures 归类——
--     consecutive_failures >= 3 → 'auto'（达到滞回阈值的自动禁用行，旧代码禁用后
--       计数不再清零，禁用行计数 ≥3 即自动禁用痕迹；恢复探活资格）；
--     其余（<3）→ 'manual'（无自动禁用痕迹=人工 SQL 置停嫌疑，保守不探活不复活）。
--   isolated_time/recovered_time 不回填（无真实事件时刻可考，宁缺毋假——admin 显示
--   无记录；判据时刻以 update_time 为旁证可人工核对，见 PR 描述逐行清单）。
--   生产 10 禁用行的逐行核对清单（只读 SELECT 现值 → 建议判据）见 PR 描述与 #186 评论。
--
-- 六类结果代码（last_outcome，NewsFetchOutcome）：valid_with_content=有效有内容 /
--   valid_empty=有效空或无新增（allow-empty 源）/ structure_mismatch=结构失配 /
--   network_failure=网络失败 / policy_forbidden=策略禁止 / defer=礼貌等待（零计数豁免）。
--
-- 只出 SQL 走 upgrades 管道（本地栈验证，生产应用由维护者派发）；新环境走
--   schema_pg.sql 全量初始化已含本变更。本脚本幂等：ADD COLUMN IF NOT EXISTS /
--   CREATE TABLE IF NOT EXISTS / UPDATE 带 disabled_reason IS NULL 守卫（重复执行无害）。

ALTER TABLE t_news_source ADD COLUMN IF NOT EXISTS disabled_reason VARCHAR(16);
ALTER TABLE t_news_source ADD COLUMN IF NOT EXISTS isolated_time TIMESTAMP;
ALTER TABLE t_news_source ADD COLUMN IF NOT EXISTS recovered_time TIMESTAMP;
ALTER TABLE t_news_source ADD COLUMN IF NOT EXISTS probe_successes INT;
ALTER TABLE t_news_source ADD COLUMN IF NOT EXISTS probe_time TIMESTAMP;
ALTER TABLE t_news_source ADD COLUMN IF NOT EXISTS last_outcome VARCHAR(32);
ALTER TABLE t_news_source ADD COLUMN IF NOT EXISTS last_outcome_time TIMESTAMP;

-- 既有禁用行判据归类（一次性守卫迁移：只动未分类行）
UPDATE t_news_source
SET disabled_reason = CASE WHEN consecutive_failures >= 3 THEN 'auto' ELSE 'manual' END
WHERE enabled = false AND disabled_reason IS NULL;

CREATE TABLE IF NOT EXISTS t_news_source_health_event (
  id          BIGSERIAL PRIMARY KEY,
  source_id   BIGINT      NOT NULL REFERENCES t_news_source(id),
  event_type  VARCHAR(32) NOT NULL,
  outcome     VARCHAR(32),
  detail      VARCHAR(512),
  event_time  TIMESTAMP   NOT NULL,
  create_time TIMESTAMP   NOT NULL DEFAULT now()
);

-- 探活扫描偏索引（probeSweep 每日取 enabled=false AND disabled_reason='auto'）
CREATE INDEX IF NOT EXISTS idx_news_source_probe_candidates
    ON t_news_source(disabled_reason) WHERE enabled = false;
-- 事件流水按源查（admin/验收按源回看停止/复归历史）
CREATE INDEX IF NOT EXISTS idx_news_source_health_event_source
    ON t_news_source_health_event(source_id);

COMMENT ON COLUMN t_news_source.consecutive_failures IS '连续抓取失败计数（结构失配/网络失败两类，#186 六类分类学）：阈值 3 自动隔离（enabled=false + disabled_reason=auto）；defer 零计数豁免';
COMMENT ON COLUMN t_news_source.enabled IS '源级开关（#186 起与 disabled_reason 联读）：true=启用；false 须看 disabled_reason 三分=manual 人工停用/auto 自动隔离（唯一探活对象）/policy 策略禁止';
COMMENT ON COLUMN t_news_source.disabled_reason IS '停用原因三分（#186）：manual=人工停用（不探活不自动解禁）/ auto=自动隔离（连续 3 败滞回，日级探活两次有效完整成功自动复归）/ policy=策略禁止（robots/出站守卫拒绝，不因可达自动解禁）；NULL=启用中。存量禁用行由 260930 迁移判据归类（consecutive_failures>=3 → auto，其余保守 manual）';
COMMENT ON COLUMN t_news_source.isolated_time IS '最近一次停用时刻（#186）：自动隔离或策略转停发生时间；迁移不回填（历史无事件时刻可考）';
COMMENT ON COLUMN t_news_source.recovered_time IS '最近一次探活自动复归时刻（#186）：连续两次有效完整成功达成';
COMMENT ON COLUMN t_news_source.probe_successes IS '探活连续有效完整成功次数（#186）：复归阈值默认 2（probe-required-successes）；任一探活失败清零；defer 不变（既非失败也非成功）';
COMMENT ON COLUMN t_news_source.probe_time IS '最近一次探活时刻（#186）：HKT 日级节拍每源每日至多探一次；defer 不推进（未探成不计尝试）';
COMMENT ON COLUMN t_news_source.last_outcome IS '最近一轮单源抓取结果六类代码（#186）：valid_with_content=有效有内容 / valid_empty=有效空或无新增（allow-empty 源）/ structure_mismatch=结构失配 / network_failure=网络失败 / policy_forbidden=策略禁止 / defer=礼貌等待（零计数豁免）';
COMMENT ON COLUMN t_news_source.last_outcome_time IS '最近一轮结果落账时刻（#186）';

COMMENT ON TABLE t_news_source_health_event IS '信源健康事件流水（#186，append-only 审计）：停止/复归/探活记录可查——isolated=自动隔离 / policy_disabled=策略转停 / probe_pass=探活通过 / probe_fail=探活失败 / recovered=探活复归；人工停用/启用走维护者 SQL 不落本表（以源行 disabled_reason=manual 为准）';
COMMENT ON COLUMN t_news_source_health_event.source_id IS '关联信源 t_news_source.id（源删除后保留事件行，admin 回退显示 deleted#id）';
COMMENT ON COLUMN t_news_source_health_event.event_type IS '事件类型：isolated / policy_disabled / probe_pass / probe_fail / recovered';
COMMENT ON COLUMN t_news_source_health_event.outcome IS '触发事件的单轮六类结果代码（判定依据留痕，与 t_news_source.last_outcome 同一枚举）';
COMMENT ON COLUMN t_news_source_health_event.detail IS '判定依据摘要（失败原因文本/成功计数，超长截断 500 字符）';
COMMENT ON COLUMN t_news_source_health_event.event_time IS '事件时刻（业务时钟）';

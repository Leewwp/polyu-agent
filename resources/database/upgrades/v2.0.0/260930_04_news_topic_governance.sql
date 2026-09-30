-- 260930 资讯主题提案治理（#202，父票 #181 §3 P1-B）
-- 设计取舍（沿 #186 先例：当前态可 UPDATE，流水只 INSERT，审计与状态分离）：
--   1) 三轨处置的当前态全部落在 t_news_topic 既有列——status 扩展软状态值
--      merged/rejected（active=待审或已转正正常展示）；promote 复用
--      curated/topic_group/slug 列（关联按 topic_id 引用，slug 变更不伤关联）。
--      不新增 t_news_topic 列、不硬删任何主题行/关联行（铁律）。
--   2) 别名账 t_news_topic_alias：merged/rejected 提案 name_zh/name_en 的规范化
--      映射（alias_key=小写+去全部空白）。富化提案消费点（NewsEnrichService#
--      linkTopics，matchVocab 之后 createProposal 之前）命中别名不再落新行：
--      merged 别名直接回链目标 curated 主题，rejected 别名跳过不挂关联——
--      09-30 polyu 再提即本表必要性证据。
--   3) 治理留痕 t_news_topic_governance_event：append-only 流水（沿 #186
--      t_news_source_health_event 形态），三轨处置+关联迁移/摘除计数全落行；
--      admin 面另由 /admin/** ADMIN_AUDIT 自动审计双保险。
--
-- 只出 SQL 走 upgrades 管道（生产应用由维护者派发，合并铁律：迁移先行）；
--   新环境走 schema_pg.sql 全量初始化已含本变更。本脚本幂等可重跑：
--   CREATE TABLE IF NOT EXISTS / COMMENT 天然幂等，无存量数据迁移
--   （首轮 14 条 PROPOSED 行保持 pending，由治理服务/admin 端点处置）。

CREATE TABLE IF NOT EXISTS t_news_topic_alias (
  id              BIGSERIAL PRIMARY KEY,
  alias_key       VARCHAR(128) NOT NULL UNIQUE,
  alias_display   VARCHAR(128),
  action          VARCHAR(16) NOT NULL,
  source_topic_id BIGINT      NOT NULL REFERENCES t_news_topic(id),
  target_topic_id BIGINT      REFERENCES t_news_topic(id),
  operator        VARCHAR(64),
  reason          VARCHAR(512),
  create_time     TIMESTAMP   NOT NULL DEFAULT now(),
  update_time     TIMESTAMP   NOT NULL DEFAULT now()
);
-- 别名账按提案源行回看（处置改判时按源核对入账明细）
CREATE INDEX IF NOT EXISTS idx_news_topic_alias_source ON t_news_topic_alias(source_topic_id);
-- merged 别名按目标回看（目标主题累计吸收了哪些别名）
CREATE INDEX IF NOT EXISTS idx_news_topic_alias_target ON t_news_topic_alias(target_topic_id);

CREATE TABLE IF NOT EXISTS t_news_topic_governance_event (
  id              BIGSERIAL PRIMARY KEY,
  topic_id        BIGINT      NOT NULL REFERENCES t_news_topic(id),
  action          VARCHAR(16) NOT NULL,
  target_topic_id BIGINT,
  detail          VARCHAR(512),
  operator        VARCHAR(64),
  event_time      TIMESTAMP   NOT NULL,
  create_time     TIMESTAMP   NOT NULL DEFAULT now()
);
-- 留痕流水按主题回看（admin 端点第三面：处置历史可复核）
CREATE INDEX IF NOT EXISTS idx_news_topic_gov_event_topic ON t_news_topic_governance_event(topic_id);

COMMENT ON COLUMN t_news_topic.status IS 'active=正常展示（curated=true 进目录；curated=false=AI 提案待审）/ merged=已并入近义 curated 主题（关联已迁移，本行保留审计，curated 保持 false）/ rejected=已弃（泛化无检索价值，残留关联已摘除并留痕，curated 保持 false）——一律软状态不硬删（#202）';
COMMENT ON TABLE t_news_topic_alias IS '主题别名账（#202 防再提）：merged/rejected 提案 name_zh/name_en 的规范化映射（alias_key=lower+去全部空白）；富化提案消费点命中别名不再落新行——merged 别名回链 target_topic_id，rejected 别名跳过不挂关联；ON CONFLICT 语义=人工改判时按 alias_key 覆盖更新';
COMMENT ON COLUMN t_news_topic_alias.alias_key IS '规范化别名键：lower(Locale.ROOT)+去全部空白（与 NewsEnrichService 提案消费点同一 normalize 口径）；同一提案 name_zh/name_en 规范化后同键只入一行';
COMMENT ON COLUMN t_news_topic_alias.alias_display IS '别名原始形态（审计可读；规范化前的原文）';
COMMENT ON COLUMN t_news_topic_alias.action IS '入账动作：merged=并入近义 curated 主题（target_topic_id 必填）/ rejected=弃（target_topic_id 为 NULL）';
COMMENT ON COLUMN t_news_topic_alias.source_topic_id IS '被处置的提案行 t_news_topic.id（软状态行保留，本列供处置明细回溯）';
COMMENT ON COLUMN t_news_topic_alias.target_topic_id IS 'merged 动作的并入目标 curated 主题 id；rejected 动作为 NULL';
COMMENT ON COLUMN t_news_topic_alias.operator IS '处置操作者（admin 账号名；批量端点经 UserContext 落行）';
COMMENT ON TABLE t_news_topic_governance_event IS '主题治理留痕流水（#202，append-only）：merged/promoted/rejected 三轨处置逐行留痕，detail 含关联迁移/摘除计数与 slug 变更明细——当前态在 t_news_topic.status，本表只增不改';
COMMENT ON COLUMN t_news_topic_governance_event.action IS '处置动作：merged=并入 / promoted=转正 / rejected=弃';
COMMENT ON COLUMN t_news_topic_governance_event.detail IS '处置明细（迁移关联数/摘除关联数/旧→新 slug/阈值依据，超长截断 500 字符）';
COMMENT ON COLUMN t_news_topic_governance_event.operator IS '处置操作者（admin 账号名；本地回放为 replay 标记）';

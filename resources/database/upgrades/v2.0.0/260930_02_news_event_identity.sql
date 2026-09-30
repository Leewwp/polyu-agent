-- 260930 事件最小模型+判重三合同（#187，父票 #180 §2/§3/§5/§8）
-- 判重三合同落库面：
--   1) 同 URL 幂等：url_hash 唯一键沿用（本迁移不动）。
--   2) 富化内容哈希复用：t_news_item.content_hash=sha256(规范化标题+正文摘录)——
--      富化阶段同哈希且供体 summary_source='llm' 时零调用复用摘要（保留两条独立行
--      =逐源证据）；与 #184 请求指纹/回执的边界=内容哈希跨源同内容也复用（指纹含
--      source_name/动态词表，同渲染请求才复用）；只承诺确切重复复用，不承诺节省比例。
--   3) 事件关联：归聚簇域，见下方事件表。
-- 事件最小模型（明确不做清单：无综述/事件页/向量/评分）：
--   t_news_event        持久事件身份（status: active/superseded——合并后非存续方转
--                       superseded，存续 ID 语义见 t_news_event_migration）。
--   t_news_event_item   条目→事件参与者证据（一 item 一行；publish_time=证据时刻）。
--   t_news_event_source_vote 独立来源映射（(event_id, independence_group) 唯一）——
--                       同机构多 feed/聚合口不重复加票：t_news_source.independence_group
--                       显式映射（官网各栏目+官方 YouTube 频道=polyu-official 一票、
--                       PRN 双语 wire=prn-wire 一票、GNews 检索面=gnews 一票；NULL=按
--                       source_key 自成一组，未来 AI 源默认独立）。
--   t_news_event_migration 身份迁移账本（kind: merge=旧事件并入存续 / split=分裂迁出
--                       （原 ID 留给确定原组，其余新 ID）/ regroup=条目改组 /
--                       detach=下架/过期摘除证据）。
-- 热度：24h 半衰（沿用），投票证据窗=事件首报起 48h（窗外报道不计热度票，CLU-038
--   口径：超窗追报热度各自记账）；热值同时写 t_news_event.heat 与成员条目 heat。
-- 本脚本幂等：ADD COLUMN IF NOT EXISTS / CREATE TABLE IF NOT EXISTS / UPDATE 带
--   independence_group IS NULL 守卫（重复执行无害）。只出 SQL 走 upgrades 管道
--   （本地栈验证，生产应用由维护者派发）；新环境走 schema_pg.sql 全量初始化已含本变更。

ALTER TABLE t_news_item ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_news_item_content_hash ON t_news_item(content_hash) WHERE content_hash IS NOT NULL;

COMMENT ON COLUMN t_news_item.content_hash IS '富化判重内容哈希（#187）：sha256(规范化标题+正文摘录)；同哈希且供体 summary_source=llm 时零调用复用摘要（保留逐源证据行）；NULL=未富化/无正文（YouTube 跳过正文，不复用）';

ALTER TABLE t_news_source ADD COLUMN IF NOT EXISTS independence_group VARCHAR(64);

-- 同机构多渠道归一组（不重复计票）；守卫 independence_group IS NULL 保证幂等重跑不覆盖人工映射
UPDATE t_news_source SET independence_group = 'polyu-official'
WHERE independence_group IS NULL
  AND source_key IN ('news-sitemap', 'media-releases', 'recent-focus', 'events', 'campus-reports',
                     'sao-news', 'ar-notices', 'feng-news', 'comp-news', 'fce-news', 'shtm-news',
                     'youtube-main', 'youtube-feng', 'youtube-comp', 'youtube-fce');
UPDATE t_news_source SET independence_group = 'prn-wire'
WHERE independence_group IS NULL AND source_key = 'prn';
UPDATE t_news_source SET independence_group = 'gnews'
WHERE independence_group IS NULL AND source_key LIKE 'gnews-%';

COMMENT ON COLUMN t_news_source.independence_group IS '独立来源组（#187 事件投票去重键）：同机构多 feed/聚合口归同组只计一票（官网各栏目+官方 YouTube=polyu-official；PRN 双语 wire=prn-wire；GNews 检索面=gnews）；NULL=按 source_key 自成一组';

CREATE TABLE IF NOT EXISTS t_news_event (
  id                  BIGSERIAL PRIMARY KEY,
  status              VARCHAR(16) NOT NULL DEFAULT 'active',   -- active/superseded（合并非存续方）
  heat                INT         NOT NULL DEFAULT 0,          -- 事件热度（独立票×24h 半衰）
  first_report_time   TIMESTAMP,                               -- 最早成员 publish_time（衰减锚）
  last_activity_time  TIMESTAMP,                               -- 最晚成员 publish_time
  create_time         TIMESTAMP   NOT NULL DEFAULT now(),
  update_time         TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_news_event_status ON t_news_event(status) WHERE status = 'active';
COMMENT ON TABLE t_news_event IS '资讯事件持久身份（#187 最小模型：只做身份/证据/热度，无综述/事件页/向量/评分）；未发生合并/分裂的同事件 ID 稳定；合并选存续 ID（最早首报，平手取小 id）并记旧→存续迁移';
COMMENT ON COLUMN t_news_event.status IS 'active=现行事件；superseded=已并入存续事件（身份迁移见 t_news_event_migration，行保留审计不再持有成员）';
COMMENT ON COLUMN t_news_event.heat IS '事件热度=（48h 证据窗内独立来源组数+Σ组内最大源权重）×24h 半衰（锚=first_report_time，未来封顶 1）；同步写成员条目 heat';
COMMENT ON COLUMN t_news_event.first_report_time IS '事件首报=成员最早 publish_time；24h 半衰与 48h 投票证据窗的共同锚点';

CREATE TABLE IF NOT EXISTS t_news_event_item (
  id                  BIGSERIAL PRIMARY KEY,
  event_id            BIGINT NOT NULL REFERENCES t_news_event(id),
  item_id             BIGINT NOT NULL REFERENCES t_news_item(id) ON DELETE CASCADE,
  source_id           BIGINT,
  independence_group  VARCHAR(64) NOT NULL,                    -- 入组时源独立组快照
  publish_time        TIMESTAMP,                               -- 证据时刻（48h 窗判定输入）
  joined_time         TIMESTAMP NOT NULL DEFAULT now(),        -- 首次入组时刻
  CONSTRAINT uq_news_event_item UNIQUE (item_id)
);
CREATE INDEX IF NOT EXISTS idx_news_event_item_event ON t_news_event_item(event_id);
COMMENT ON TABLE t_news_event_item IS '事件参与者证据（#187）：一条目至多属一事件（item_id 唯一）；重归组改写 event_id 并落 t_news_event_migration(regroup)；下架/过期摘除本行并落 detach 迁移';
COMMENT ON COLUMN t_news_event_item.independence_group IS '入组时 t_news_source.independence_group 快照（源映射变更不回溯历史证据）';
COMMENT ON COLUMN t_news_event_item.publish_time IS '成员条目 publish_time=参与者证据时刻；热度票资格=∈[事件 first_report_time, +48h]';

CREATE TABLE IF NOT EXISTS t_news_event_source_vote (
  id                  BIGSERIAL PRIMARY KEY,
  event_id            BIGINT NOT NULL REFERENCES t_news_event(id),
  independence_group  VARCHAR(64) NOT NULL,
  vote_count          INT NOT NULL DEFAULT 0,                  -- 组内参与条目数（仅计数，票=1）
  first_vote_time     TIMESTAMP,
  last_vote_time      TIMESTAMP,
  update_time         TIMESTAMP NOT NULL DEFAULT now(),
  CONSTRAINT uq_news_event_vote UNIQUE (event_id, independence_group)
);
COMMENT ON TABLE t_news_event_source_vote IS '事件独立来源投票账（#187）：(event_id, independence_group) 一行一票——同机构多 feed/聚合口不重复加票；vote_count 只记录组内条目数不放大票权；每轮重归组后按成员全量重建';

CREATE TABLE IF NOT EXISTS t_news_event_migration (
  id                  BIGSERIAL PRIMARY KEY,
  old_event_id        BIGINT NOT NULL,
  new_event_id        BIGINT,                                  -- detach 无新事件为 NULL
  kind                VARCHAR(16) NOT NULL,                    -- merge/split/regroup/detach
  item_id             BIGINT,                                  -- split/regroup/detach 携带
  reason              VARCHAR(512),
  create_time         TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_news_event_migration_old ON t_news_event_migration(old_event_id);
COMMENT ON TABLE t_news_event_migration IS '事件身份迁移账本（#187，append-only）：merge=旧事件并入存续（旧 superseded）；split=分裂（原 ID 留给含最早成员的确定原组，迁出条目落新事件）；regroup=条目改组（双存活）；detach=条目下架/过期摘除证据';
COMMENT ON COLUMN t_news_event_migration.kind IS 'merge=事件级合并（item_id 空）/ split=分裂迁出（新事件为被迁入方）/ regroup=条目在存活事件间移动 / detach=终态摘除（new_event_id 空）';

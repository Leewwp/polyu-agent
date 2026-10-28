-- 261029 日报「八校/高教」独立栏目快照行表（#412，父票 #406 B 线；#410 三源已注册）
-- 新表 t_news_daily_digest_higher_ed：每期日报的「八校/高教」横向信息栏目
-- ——八校联校课程与交换 / JUPAS 与研究生政策 / UGC 高教政策（#319 裁定三类），
-- 纯数据、零 LLM——与校历关键日期（#316）/校园活动（#330）同为独立栏目快照。
--
-- 设计取舍（沿 t_news_daily_digest_item/activity 快照独立性红线）：item_id/
-- source_* 只作溯源，**不建外键**——t_news_item 行被 90 天保留清理删除不连带，
-- 栏目字段全部冗余快照；读取面（页面）零 LLM，仅按 item_id 回查 t_news_item
-- status 做主动下架复检（与主资讯流同一判据）。
--
-- 选材合同（生成期冻结）：候选=与主资讯流同一窗口与统一公开资格判据
-- （[D-1 08:00, D 08:00) HKT 左闭右开，status=published 且过发布门），按候选
-- 条目解析出的 source_key ∈ rag.news.digest-higher-ed-source-keys（#410 注册
-- 的 B 线官方源：jupas-news / jupas-programme-changes / ugc-whatnews）分流
-- 进本栏目，**不落 t_news_daily_digest_item**（理大主资讯流不含 B 线条目）；
-- source 行缺失/未注册的条目 fail-open 留在主流程（只对可辨识的 B 线源分流）。
-- 栏内序=publish_time DESC, id DESC（与主流程同一确定性排序）；容量=
-- rag.news.digest-higher-ed-max-entries（默认 30=3 源×日准入 10 的理论上界，
-- 防御性护栏非选材过滤器）。横向信息的量级护栏=准入侧每源日上限（#410
-- 各 10/日）+本容量界，双层约束防淹没主流程。窗口零条目=零快照行，前端
-- 整段隐藏不渲染空壳（无内容日期优雅缺席）。
--
-- 幂等：digest_date 唯一保证同日期至多一刊；刊头删除经 ON DELETE CASCADE
-- 带走本表快照行，随 rebuildForDate 重算。
--
-- 只出 SQL 走 upgrades 管道（本地栈验证，生产应用由维护者派发）；新环境走
-- schema_pg.sql 全量初始化已含本变更。本脚本幂等：CREATE TABLE IF NOT EXISTS
-- / CREATE INDEX IF NOT EXISTS / COMMENT 重复执行无害。

CREATE TABLE IF NOT EXISTS t_news_daily_digest_higher_ed (
  id                     BIGSERIAL PRIMARY KEY,
  digest_id              BIGINT       NOT NULL REFERENCES t_news_daily_digest(id) ON DELETE CASCADE,
  item_id                BIGINT       NOT NULL,    -- 溯源 t_news_item.id（无外键：90 天保留清理删除不连带）
  seq                    INT          NOT NULL,    -- 栏内序（1 起，publish_time DESC、id 兜底）
  url                    VARCHAR(1024) NOT NULL,   -- 原文永久外链（JUPAS/UGC 官方原文）
  url_hash               VARCHAR(64),
  title_zh               VARCHAR(512),
  title_en               VARCHAR(512),
  summary_zh             TEXT,
  summary_en             TEXT,
  category               VARCHAR(32)  NOT NULL DEFAULT 'other',
  source_key             VARCHAR(64)  NOT NULL,    -- B 线源 key 快照（分流判据的生成期留证）
  source_platform        VARCHAR(16),
  source_official        BOOLEAN,
  source_display_name    VARCHAR(128),             -- 来源标识（页面徽章直映）
  source_display_name_en VARCHAR(128),
  publish_time           TIMESTAMP,                -- 窗口归属锚（与主流程同窗同判）
  publish_time_precision VARCHAR(8)   NOT NULL DEFAULT 'unknown',  -- date/datetime/unknown（#275）
  CONSTRAINT uq_news_daily_digest_higher_ed UNIQUE (digest_id, item_id)
);
CREATE INDEX IF NOT EXISTS idx_news_daily_digest_higher_ed_digest ON t_news_daily_digest_higher_ed(digest_id);
COMMENT ON TABLE t_news_daily_digest_higher_ed IS '日报八校/高教独立栏目快照（#412 B 线：八校联校课程与交换/JUPAS 与研究生政策/UGC 高教政策三类横向信息；纯数据零 LLM；B 线源条目不落 t_news_daily_digest_item——理大主资讯流不含 B 线条目；(digest_id,item_id) 唯一；刊头删除级联带走）';
COMMENT ON COLUMN t_news_daily_digest_higher_ed.seq IS '栏内序（1 起，确定性：publish_time DESC、id 兜底——与主流程条目同一排序口径；容量=防御性护栏非选材过滤器）';
COMMENT ON COLUMN t_news_daily_digest_higher_ed.source_key IS 'B 线源 key 快照（生成期分流判据留证：∈ rag.news.digest-higher-ed-source-keys，#410 注册源）';
-- item_count 语义随本票收窄：刊头计数只覆盖主资讯流快照（不含本栏目与
-- key_date/activity 栏目行——三者供给与资讯量解耦，空刊仍可带栏目）
COMMENT ON COLUMN t_news_daily_digest.item_count IS '生成时刻的主资讯流快照条数（不含八校/高教与校历/活动栏目行）；读取期主动下架复检后的可见条数以接口实时过滤为准';

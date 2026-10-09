-- 261011 日报「校园活动」版面快照行表（#330，父票 #317；总纲 #315 线一 L2）
-- 新表 t_news_daily_digest_activity：每期日报头部的「进行中/即将来临」校园活动
-- 版面快照（纯数据、零 LLM——#316 校历关键日期栏目同形态，L2 与 L1 视觉区分
-- 由前端承担）。
--
-- 设计取舍（沿 t_news_daily_digest_key_date 快照独立性红线）：item_id 只作溯源，
-- **不建外键**——t_news_item 行被 90 天保留清理删除不连带，版面字段全部冗余
-- 快照；读取面（页面）零 LLM、零回查 t_news_item。
--
-- 选材合同（生成期冻结，as-of=刊日 D）：数据源=NewsActivityQueryService.
-- campusActivities(D, N)（#323 活动实体投影——t_news_item.activity_end_time
-- 非空即活动条目，events 现为唯一活跃源，CPEO/SAO 入库后自动汇入无需改版面）；
-- 窗口=[D, D+N-1] 含端（N=rag.news.digest-activity-window-days，默认 56=8 周，
-- 与 events 抓取扩窗同口径），活动区间与窗口任一历日重叠即入选——过期
-- （结束日早于 D）与远未开始天然落窗外；序=开始日期升序+item_id 兜底
-- （进行中的开始日早自然在前）；容量=rag.news.digest-activity-max-entries
-- （默认 10，超限取最近）。ongoing=开始日 < D 且结束日 >= D（已开始未结束；
-- 当日开始归「即将来临」，与 L1「当日开始不标进行中」同口径），生成期冻结
-- 不随读取时刻漂移。窗口零活动=零快照行，前端整段隐藏不渲染空壳；空刊
-- （资讯零条）仍照常落版面行——供给与资讯量解耦。
--
-- 幂等：digest_date 唯一保证同日期至多一刊；刊头删除经 ON DELETE CASCADE
-- 带走本表快照行，随 rebuildForDate 重算。
--
-- 只出 SQL 走 upgrades 管道（本地栈验证，生产应用由维护者派发）；新环境走
-- schema_pg.sql 全量初始化已含本变更。本脚本幂等：CREATE TABLE IF NOT EXISTS
-- / CREATE INDEX IF NOT EXISTS（重复执行无害）。

CREATE TABLE IF NOT EXISTS t_news_daily_digest_activity (
  id         BIGSERIAL PRIMARY KEY,
  digest_id  BIGINT        NOT NULL REFERENCES t_news_daily_digest(id) ON DELETE CASCADE,
  item_id    BIGINT        NOT NULL,    -- 溯源 t_news_item.id（无外键：90 天保留清理删除不连带）
  seq        INT           NOT NULL,    -- 版面内序（1 起，date_start 升序、item_id 兜底）
  title_zh   VARCHAR(512),
  title_en   VARCHAR(512),
  url        VARCHAR(1024) NOT NULL,    -- 详情页永久外链（卡片外链语义）
  date_start DATE          NOT NULL,    -- 活动开始日（HKT 历日；publish_time 活动开始语义）
  date_end   DATE          NOT NULL,    -- 活动结束日（HKT 历日；activity_end_time 含端代表值）
  ongoing    BOOLEAN       NOT NULL DEFAULT false,  -- 进行中=开始日 < 刊日 且 结束日 >= 刊日
  CONSTRAINT uq_news_daily_digest_activity UNIQUE (digest_id, item_id)
);
CREATE INDEX IF NOT EXISTS idx_news_daily_digest_activity_digest ON t_news_daily_digest_activity(digest_id);
COMMENT ON TABLE t_news_daily_digest_activity IS '日报校园活动版面快照（#330 L2：纯数据零 LLM；item_id 只作溯源无外键，保留清理不连带；(digest_id,item_id) 唯一=同刊内一活动一行；刊头删除级联带走）';
COMMENT ON COLUMN t_news_daily_digest_activity.seq IS '版面内序（1 起，确定性：date_start 升序、item_id 兜底；超容量取最近）';
COMMENT ON COLUMN t_news_daily_digest_activity.date_start IS '活动开始日（#323 模型：publish_time=活动开始，投影换算 HKT 历日；版面按活动实体日期组织，非 publish_time 窗口）';
COMMENT ON COLUMN t_news_daily_digest_activity.date_end IS '活动结束日（activity_end_time 换算 HKT 历日；date-only 结束证据按当日 23:59:59 含端代表值入库，投影为当日历日）';
COMMENT ON COLUMN t_news_daily_digest_activity.ongoing IS '进行中=开始日 < 刊日 且 结束日 >= 刊日（当日开始归「即将来临」，与 L1 关键日期「当日开始不标进行中」同口径；as-of=刊日生成期冻结，不随读取时刻漂移）';

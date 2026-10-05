-- #275 发布时间精度列（date/datetime/unknown）
-- 旧行/旧快照由 NOT NULL DEFAULT 'unknown' 物化为显式 unknown——不猜测、不历史回填。
-- 精度语义见 schema_pg.sql 同名列注释；窗口边界口径（左闭右开）不变：
-- D 07:59:59(.999) 属 D 刊；D 08:00:00(含 .001) 属 D+1 刊；date-only 代表值 23:59:59 属 D+1 刊。
ALTER TABLE t_news_item
    ADD COLUMN IF NOT EXISTS publish_time_precision VARCHAR(8) NOT NULL DEFAULT 'unknown';
COMMENT ON COLUMN t_news_item.publish_time_precision IS '发布时间精度（#275）：date=只有日期证据（publish_time 为该日 23:59:59 HKT 归期代表值，落 [D 08:00,D+1 08:00) 归 D+1 刊；展示层只显日期）/ datetime=真实瞬时（RSS pubDate、lib HKT HH:mm、PRN HH:mm ET 换算）/ unknown=本列前历史行与非精确化路径（sitemap lastmod、events start-date 含义不改）——不猜测、不历史回填';

ALTER TABLE t_news_daily_digest_item
    ADD COLUMN IF NOT EXISTS publish_time_precision VARCHAR(8) NOT NULL DEFAULT 'unknown';
COMMENT ON COLUMN t_news_daily_digest_item.publish_time_precision IS '发布时间精度快照（#275）：新快照冗余源行精度；旧快照 unknown 不回填；date 精度展示层只显日期';

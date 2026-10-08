-- 261009 日报「校历关键日期」栏目快照行表（#316，总纲 #315 线一 L1）
-- 新表 t_news_daily_digest_key_date：每期日报头部的「未来 N 天关键日期与
-- 截止提醒」栏目快照（纯数据、零 LLM——供给与资讯量解耦，任何一天日报保底
-- 有内容）。
--
-- 设计取舍（沿 t_news_daily_digest_item 快照独立性红线）：key_date_id/uid 只作
-- 溯源，**不建外键**——t_key_date 行 withdrawn/archived 不连带，栏目字段全部
-- 冗余快照；读取面（页面/RSS）零 LLM、零回查 t_key_date。
--
-- 选材合同（生成期冻结）：as-of=刊日 D，窗口=[D, D+N-1] 含端共 N 个历日
-- （N=rag.news.digest-key-date-window-days，默认 14）；status=published 且
-- date_start 或 date_end 落窗即可入选（fuzzy 行 date_* 全 NULL 天然不落窗），
-- date_start 升序+uid 兜底，容量=rag.news.digest-key-date-max-entries（默认 8，
-- 超限取最近）；已开始未结束（date_start < D 且有效结束日 >= D）标
-- ongoing=true；days_until=D→date_start 天数（倒计时门：仅 exact-day/
-- exact-range，onwards 不伪造截止语义恒 NULL）。窗口零条目=零快照行，
-- 前端整段隐藏不渲染空壳；空刊（资讯零条）仍照常落栏目行——降级版式保底。
--
-- 幂等：digest_date 唯一保证同日期至多一刊；刊头删除经 ON DELETE CASCADE
-- 带走本表快照行，随 rebuildForDate 重算。
--
-- 只出 SQL 走 upgrades 管道（本地栈验证，生产应用由维护者派发）；新环境走
-- schema_pg.sql 全量初始化已含本变更。本脚本幂等：CREATE TABLE IF NOT EXISTS
-- / CREATE INDEX IF NOT EXISTS（重复执行无害）。

CREATE TABLE IF NOT EXISTS t_news_daily_digest_key_date (
  id            BIGSERIAL PRIMARY KEY,
  digest_id     BIGINT       NOT NULL REFERENCES t_news_daily_digest(id) ON DELETE CASCADE,
  key_date_id   BIGINT       NOT NULL,    -- 溯源 t_key_date.id（无外键：withdrawn/归档不连带）
  seq           INT          NOT NULL,    -- 栏内序（1 起，date_start 升序、uid 兜底）
  uid           VARCHAR(64)  NOT NULL,    -- t_key_date 语义身份（SHA-256 hex）
  title_zh      VARCHAR(256),
  title_en      VARCHAR(256) NOT NULL,
  audience_text VARCHAR(256),             -- 官方人群限制原文（不得省略，随行快照）
  precision     VARCHAR(16)  NOT NULL,    -- exact-day / exact-range / onwards（fuzzy 不落窗）
  date_start    DATE         NOT NULL,    -- 落窗条目必有 date_start（t_key_date 约束：date_end 非空 ⇒ date_start 非空）
  date_end      DATE,
  fuzzy_hint    VARCHAR(64),
  ongoing       BOOLEAN      NOT NULL DEFAULT false,  -- 已开始未结束（as-of=刊日）
  days_until    INT,                      -- 刊日→date_start 天数（倒计时门：仅 exact-day/exact-range）
  CONSTRAINT uq_news_daily_digest_key_date UNIQUE (digest_id, key_date_id)
);
CREATE INDEX IF NOT EXISTS idx_news_daily_digest_key_date_digest ON t_news_daily_digest_key_date(digest_id);
COMMENT ON TABLE t_news_daily_digest_key_date IS '日报校历关键日期栏目快照（#316 L1：纯数据零 LLM；key_date_id/uid 只作溯源无外键，withdrawn/归档不连带；(digest_id,key_date_id) 唯一=同刊内一事件一行；刊头删除级联带走）';
COMMENT ON COLUMN t_news_daily_digest_key_date.seq IS '栏内序（1 起，确定性：date_start 升序、uid 兜底；超容量取最近）';
COMMENT ON COLUMN t_news_daily_digest_key_date.date_start IS 'NOT NULL 有据：选材=date_start 或 date_end 落窗，而 t_key_date 约束保证 date_end 非空 ⇒ date_start 非空（fuzzy 行 date_* 全 NULL 不落窗）';
COMMENT ON COLUMN t_news_daily_digest_key_date.ongoing IS '已开始未结束（date_start < 刊日 且 有效结束日 date_end??date_start >= 刊日；as-of=刊日生成期冻结，不随读取时刻漂移）';
COMMENT ON COLUMN t_news_daily_digest_key_date.days_until IS '刊日→date_start 天数（0=当日开始；负=已开始的区间，展示层以 ongoing 徽章优先）；倒计时门=仅 exact-day/exact-range，onwards 恒 NULL';

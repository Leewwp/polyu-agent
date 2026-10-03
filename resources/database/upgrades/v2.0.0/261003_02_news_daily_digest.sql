-- 261003 资讯日报（#212，父票 #182 r3 §日报——P2-a 出口）
-- 两张新表：刊头（t_news_daily_digest，HKT 日期唯一）+ 条目快照行表
-- （t_news_daily_digest_item）。
--
-- 设计取舍（行表而非 JSON）：读取期须对逐快照条目回查 t_news_item 做
-- 主动下架复检（存在且 status<>'published' → 失格过滤），行表支持 SQL 面
-- 批量 join；JSON 需整包解析后内存过滤且无法索引。快照独立性红线：
-- item_id/source_id 均**不建外键**——t_news_item 有 90 天保留清理
-- （NewsRetentionJob），外键级联会连带删空历史日报；快照字段（标题/摘要/
-- URL/来源/主题/分类）全部冗余留存，源行清理后日报仍完整可读。
--
-- 幂等：digest_date UNIQUE 保证同日期至多一刊；重建=按日期先删后插
-- （刊头删除经 ON DELETE CASCADE 带走快照行），重跑只产一刊。
-- 窗口合同：[D-1 08:00, D 08:00) HKT 左闭右开，digest_date=D（窗口闭端日）；
-- publish_time 恰落 08:00:00.000 的条目归属**下一期**（窗口起点侧）。
--
-- 只出 SQL 走 upgrades 管道（本地栈验证，生产应用由维护者派发）；新环境走
-- schema_pg.sql 全量初始化已含本变更。本脚本幂等：CREATE TABLE IF NOT EXISTS
-- / CREATE INDEX IF NOT EXISTS（重复执行无害）。

CREATE TABLE IF NOT EXISTS t_news_daily_digest (
  id           BIGSERIAL PRIMARY KEY,
  digest_date  DATE        NOT NULL,             -- HKT 日报日期=窗口闭端日（唯一键）
  window_start TIMESTAMP   NOT NULL,             -- 窗口起点（D-1 08:00 HKT）
  window_end   TIMESTAMP   NOT NULL,             -- 窗口闭端（D 08:00 HKT，开区间）
  intro_zh     TEXT,                              -- 导语（中文；LLM 或模板回退）
  intro_en     TEXT,                              -- 导语（英文）
  intro_source VARCHAR(8)  NOT NULL,              -- llm / fallback / empty
  item_count   INT         NOT NULL DEFAULT 0,    -- 快照条数（生成时刻口径）
  status       VARCHAR(16) NOT NULL DEFAULT 'published',  -- published（预留 rebuild_pending）
  build_time   TIMESTAMP   NOT NULL,              -- 本刊生成时刻
  create_time  TIMESTAMP   NOT NULL DEFAULT now(),
  update_time  TIMESTAMP   NOT NULL DEFAULT now(),
  CONSTRAINT uq_news_daily_digest_date UNIQUE (digest_date)
);
COMMENT ON TABLE t_news_daily_digest IS '资讯日报刊头（#212：HKT 日期唯一一刊；快照独立于 t_news_item 90 天清理——条目内容冗余在快照行表，本表只存刊头/导语/统计）';
COMMENT ON COLUMN t_news_daily_digest.digest_date IS 'HKT 日报日期=窗口闭端日：窗口=[前一 08:00, 本日 08:00) 左闭右开；publish_time 恰落 08:00:00.000 归属下一期';
COMMENT ON COLUMN t_news_daily_digest.intro_source IS '导语产出方式：llm=预算护栏内单次调用；fallback=LLM 失败/预算耗尽的固定模板（零新增调用）；empty=空刊模板（零调用）';
COMMENT ON COLUMN t_news_daily_digest.item_count IS '生成时刻的快照条数；读取期主动下架复检后的可见条数以接口实时过滤为准';
COMMENT ON COLUMN t_news_daily_digest.status IS 'published=现行刊；rebuild_pending=预留（主动下架降级态），当前默认回退路径=读取期零调用过滤+模板导语';

CREATE TABLE IF NOT EXISTS t_news_daily_digest_item (
  id                   BIGSERIAL PRIMARY KEY,
  digest_id            BIGINT       NOT NULL REFERENCES t_news_daily_digest(id) ON DELETE CASCADE,
  item_id              BIGINT       NOT NULL,    -- 溯源 ID（无外键：t_news_item 90 天清理不得连带）
  seq                  INT          NOT NULL,    -- 刊内序（1 起，确定性：publish_time DESC, id DESC）
  url                  VARCHAR(1024) NOT NULL,
  url_hash             VARCHAR(64),
  title_zh             VARCHAR(512),
  title_en             VARCHAR(512),
  summary_zh           TEXT,
  summary_en           TEXT,
  category             VARCHAR(32)  NOT NULL DEFAULT 'other',
  topic_slugs          TEXT,                      -- 逗号分隔快照（生成时刻关联）
  source_id            BIGINT,                    -- 无外键（信源注册表删除不连带）
  source_key           VARCHAR(64),
  source_platform      VARCHAR(16),
  source_official      BOOLEAN,
  source_display_name  VARCHAR(128),
  source_display_name_en VARCHAR(128),
  publish_time         TIMESTAMP,
  CONSTRAINT uq_news_daily_digest_item UNIQUE (digest_id, item_id)
);
CREATE INDEX IF NOT EXISTS idx_news_daily_digest_item_digest ON t_news_daily_digest_item(digest_id);
COMMENT ON TABLE t_news_daily_digest_item IS '资讯日报条目快照（#212：ID+全展示字段冗余留存，防 90 天保留清理连带；(digest_id,item_id) 唯一=同刊内一源条目一行）';
COMMENT ON COLUMN t_news_daily_digest_item.item_id IS '溯源 t_news_item.id（快照独立性：无外键，源行被保留清理删除后快照仍完整；读取期仅当源行仍存在且 status 不为 published 时过滤失格）';
COMMENT ON COLUMN t_news_daily_digest_item.seq IS '刊内序（确定性选材冻结口径：publish_time DESC, id DESC 的全部动态序，非评分排序）';

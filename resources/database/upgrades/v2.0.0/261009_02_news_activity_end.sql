-- 261009 events 源扩窗未来 8 周+活动实体模型持久面（#323，父票 #317 校园活动版面）
-- 变更一（数据面）：t_news_item.activity_end_time——活动型来源条目（events，起止成对）
-- 的结束时刻；publish_time=活动开始（#275 语义不改）、本列=活动结束（date-only 结束
-- 证据按当日 23:59:59 HKT 含端代表值入库）。NULL=非活动条目（无明确起止，纯资讯流）；
-- 活动版面投影（NewsActivityQueryService，#330 消费）按「本列非空」识别活动实体，
-- 不新增独立表、不复制存储（父票 #317「以最小改动为准，不强行新表」）。
-- 变更二（抓取面）：events 源抓取窗口从「当前月+下月」扩为未来 8 周滚动（按月取数
-- 拼窗，rag.news.events-window-weeks 默认 8；跨月条目按规范化 URL 去重；fail-closed
-- 口径不随扩窗放大——仅当前月+下月维持零条目守卫，+2 月及以后零排期宽和收空）。
-- 抓取面无 DDL；历史行 activity_end_time 保持 NULL（历史不回填，#275 同纪律）。
--
-- 只出 SQL 走 upgrades 管道（本地栈验证，生产应用由维护者派发）；新环境走
-- schema_pg.sql 全量初始化已含本变更。本脚本幂等：ADD COLUMN IF NOT EXISTS
-- （重复执行无害）。

ALTER TABLE t_news_item ADD COLUMN IF NOT EXISTS activity_end_time TIMESTAMP;

COMMENT ON COLUMN t_news_item.activity_end_time IS '活动结束时刻（#323 活动实体模型）：活动型来源条目（events，起止成对）的结束时间——publish_time=活动开始、本列=活动结束（date-only 结束证据为 D 23:59:59 HKT 含端代表值）；NULL=非活动条目（无明确起止，纯资讯流，活动版面投影按本列非空识别活动实体）';

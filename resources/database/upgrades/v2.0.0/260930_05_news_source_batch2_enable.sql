-- 260930 批 2 信源启用（#188，维护者裁决 2026-09-30 晚提前执行窗）
-- 四源 alumni-news/lib-news/fb-news/fhss-news 由 260930_03 seed（enabled=FALSE、
-- disabled_reason='manual'——#188 铁律：启用须维护者另行授权，不走探活自动复归）。
-- 本脚本=该授权的落库：置 enabled=true、清 disabled_reason；人工启停不落
-- t_news_source_health_event（#186 口径：人工操作以源行 disabled_reason 为准）。
-- 顺序归因前提（doc 59）：批 1 基线快照已先行落档（staging/batch2-enable-20260930/
-- baseline-batch1.md）；本脚本为该执行窗第一笔生产写。
-- 幂等：WHERE enabled=false 守卫，重复执行无害（行数只减不增，目标外零触碰）。
UPDATE t_news_source
SET enabled        = TRUE,
    disabled_reason = NULL,
    update_time     = now()
WHERE source_key IN ('alumni-news', 'lib-news', 'fb-news', 'fhss-news')
  AND enabled = FALSE;

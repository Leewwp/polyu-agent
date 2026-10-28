-- 261029 #409 媒体面收敛先行：生产停用 rthk-local-news（enabled 开关级、可逆）
-- 依据：#318 两周观察窗实测（project-docs/100）——rthk 供给极低（17 天过门仅 3 条）
-- 且贡献全窗唯一误收（id 225：「HKU station」港铁站名触发观塘线交通新闻）；
-- 媒体面收敛为 gia 单源走八校门（#319 2026-10-27 裁定）。
-- 口径与先例（260930_05 批 2 启用）同源反向：停用=置 enabled=FALSE +
-- disabled_reason='manual'（#186 三分：manual=人工停用，不探活、不自动解禁，
-- 复启须维护者另行授权 UPDATE）；人工启停不落 t_news_source_health_event
-- （#186 口径：人工操作以源行 disabled_reason 为准）。
-- 源行与健康历史（t_news_source_health_event）不删除——停用可逆，历史可查。
-- 幂等：WHERE enabled=TRUE 守卫，重复执行无害（行数只减不增，目标外零触碰）。
UPDATE t_news_source
SET enabled        = FALSE,
    disabled_reason = 'manual',
    update_time     = now()
WHERE source_key = 'rthk-local-news'
  AND enabled = TRUE;

-- v2.0.0 260916 关于页英文内容列（doc 32 批复：关于随全局语言切换）
-- 幂等：ADD COLUMN IF NOT EXISTS，重放无害；存量行 content_en 为 NULL，前端回落中文内容。
-- 应用后无需清缓存（关于页无 Redis 缓存，直读单行表）。
-- 英文初始文案仅预填本地库验证渲染；生产由维护者后台自贴（同 260913 附录 A 先例，不写种子）。

ALTER TABLE t_site_about ADD COLUMN IF NOT EXISTS content_en TEXT;

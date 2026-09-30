-- 260930 #188 批 2 信源 seed（维护者裁决 2026-09-30：alumni-news/lib-news/fb-news/fhss-news，
-- fs-awards 备选不默认做；批 1 youtube/gnews 九行走 #186 探活自然复归轨道，本脚本零触碰）
--
-- 四源全部 polyu.edu.hk 域内 HTML_LIST（2026-09-30 生产侧+本地双探测 200+静态锚点充足，
-- 见 research/polyu-relevant-sources/03-生产侧探测记录-20260930.md）：
--   alumni-news 校友新闻     polyu.edu.hk/alumni/news/                    官网列表族（零新增选择器）
--   lib-news    图书馆新闻   www.lib.polyu.edu.hk/news                    Drupal views 族（解析器最小扩展，
--                            见 NewsHtmlListParser 第 4 族；注意 library. 子域不可达必须 www.lib，
--                            robots Crawl-delay 10 与 NewsHttpFetchClient 节拍/defer 机制天然兼容）
--   fb-news     商学院新闻   polyu.edu.hk/fb/news-events/news/            官网列表族（零新增选择器）
--   fhss-news   人文学院新闻 polyu.edu.hk/fhss/news-and-events/news-and-events/ 官网列表族（零新增选择器）
--
-- 铁律（#188 裁决）：seed 一律 enabled=FALSE——启用须维护者另行授权，不走探活自动复归
--   （disabled_reason='manual'：#186 三分之「seed 明示停用」，不探活、不自动解禁；
--   与 campus-reports 同口径）。既有行 enabled 绝不 UPDATE，本脚本只 INSERT 新行。
-- independence_group='polyu-official'（#187 组映射）：四源同属理大官方机构，事件投票不重复加票。
-- 本脚本幂等：ON CONFLICT (source_key) DO NOTHING，重复执行无害。
-- 新环境（空数据卷）走 schema_pg.sql 全量初始化 + init_data_pg.sql 同源种子，不经本脚本。

INSERT INTO t_news_source (source_key, platform, display_name, display_name_en, home_url, fetch_endpoint, fetch_strategy, official, enabled, disabled_reason, independence_group) VALUES
  ('alumni-news', 'official', '校友事务处新闻',     'Alumni News',                'https://www.polyu.edu.hk/alumni/news/', 'https://www.polyu.edu.hk/alumni/news/', 'HTML_LIST', TRUE, FALSE, 'manual', 'polyu-official'),
  ('lib-news',    'official', '包玉刚图书馆新闻',   'Pao Yue-kong Library News',  'https://www.lib.polyu.edu.hk/news',     'https://www.lib.polyu.edu.hk/news',     'HTML_LIST', TRUE, FALSE, 'manual', 'polyu-official'),
  ('fb-news',     'official', '工商管理学院动态',   'FB News',                    'https://www.polyu.edu.hk/fb/news-events/news/', 'https://www.polyu.edu.hk/fb/news-events/news/', 'HTML_LIST', TRUE, FALSE, 'manual', 'polyu-official'),
  ('fhss-news',   'official', '医疗及社会科学院动态', 'FHSS News',                'https://www.polyu.edu.hk/fhss/news-and-events/news-and-events/', 'https://www.polyu.edu.hk/fhss/news-and-events/news-and-events/', 'HTML_LIST', TRUE, FALSE, 'manual', 'polyu-official')
ON CONFLICT (source_key) DO NOTHING;

-- 261005 #276 学院四源 seed（日报验收批 E；维护者 2026-10-05 #271 决议 6/8：七官网候选准入已授权，
-- 免额外第三方资格复审；seed 仍停用，逐源技术检查后启用留获批上线窗）
--
-- 四源（2026-10-05 匿名直连实采 200+锚点充足，fixture 逐源冻结）：
--   speed-news  专上学院新闻   speed-polyu.edu.hk/news                            第 5 解析族（#276 最小新增：
--                               a.news-list-item + d MMM uuuu 无逗号；置顶旧文不取；robots 404=全域允许无 Crawl-delay，
--                               www.polyu.edu.hk 主域 robots 按 host 隔离不适用 speed 子域）
--   sd-news     设计学院新闻   www.polyu.edu.hk/sd/news-and-events/news/          官网列表族（零新增选择器）
--   sft-news    时装及纺织学院新闻 www.polyu.edu.hk/sft/news-and-events/news/     官网列表族（零新增选择器）
--   fs-awards   理学院获奖动态 www.polyu.edu.hk/fs/news-and-events/awards-and-achievements/  官网列表族（零新增选择器）
--
-- sd/sft/fs 同 www.polyu.edu.hk 主域 robots（* 组仅 Disallow search-result 类路径，无 Crawl-delay →
-- 框架实际节拍 max(适用声明,10s)）；四源日期全为 date-only 证据（#275 归期代表值 23:59:59 HKT，精度 date）。
--
-- 铁律（#188/#276 同口径）：seed 一律 enabled=FALSE——启用须获批上线窗内逐源技术检查
--   （robots、匿名访问、真实解析、过滤、预算）通过后启用并留痕，不走探活自动复归
--   （disabled_reason='manual'）。既有行 enabled 绝不 UPDATE，本脚本只 INSERT 新行。
-- independence_group='polyu-official'（#187 组映射）：与批 2 官网子站同组，事件投票不重复加票。
-- 本脚本幂等：ON CONFLICT (source_key) DO NOTHING，重复执行无害、不翻转既有禁用行。
-- 新环境（空数据卷）走 schema_pg.sql 全量初始化 + init_data_pg.sql 同源种子，不经本脚本。

INSERT INTO t_news_source (source_key, platform, display_name, display_name_en, home_url, fetch_endpoint, fetch_strategy, official, enabled, disabled_reason, independence_group) VALUES
  ('speed-news', 'official', '香港专上学院（SPEED）新闻', 'PolyU SPEED News',      'https://speed-polyu.edu.hk/news', 'https://speed-polyu.edu.hk/news', 'HTML_LIST', TRUE, FALSE, 'manual', 'polyu-official'),
  ('sd-news',    'official', '设计学院动态',             'School of Design News',  'https://www.polyu.edu.hk/sd/news-and-events/news/', 'https://www.polyu.edu.hk/sd/news-and-events/news/', 'HTML_LIST', TRUE, FALSE, 'manual', 'polyu-official'),
  ('sft-news',   'official', '时装及纺织学院动态',        'SFT News',               'https://www.polyu.edu.hk/sft/news-and-events/news/', 'https://www.polyu.edu.hk/sft/news-and-events/news/', 'HTML_LIST', TRUE, FALSE, 'manual', 'polyu-official'),
  ('fs-awards',  'official', '理学院获奖动态',           'Faculty of Science Awards', 'https://www.polyu.edu.hk/fs/news-and-events/awards-and-achievements/', 'https://www.polyu.edu.hk/fs/news-and-events/awards-and-achievements/', 'HTML_LIST', TRUE, FALSE, 'manual', 'polyu-official')
ON CONFLICT (source_key) DO NOTHING;

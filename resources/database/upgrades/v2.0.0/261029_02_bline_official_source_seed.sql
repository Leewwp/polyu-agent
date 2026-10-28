-- 261029 #410 B 线八校/高教官方三源 seed（父票 #406 §①；生产侧手工 SQL，与
-- init_data_pg.sql 新行同形态同值——编号沿预分配 261029_02，不撞 261009/261019/261027）
--
-- 源探索留档（2026-10-29 生产腾讯 HK 出口实测，UA=polyuguide-feed/1.0，票面
-- 评论+worktree research/bline-official-sources-20261029/）：
--   jupas-news              JUPAS 新闻公告（EN）
--     端点   https://www.jupas.edu.hk/en/news/json_news_filter/all/（列表页 JS 取数口，
--            无尾斜杠 301→尾斜杠，seed 直落尾斜杠；OkHttp 跟随重定向=cpeo 判例）
--     结构   JSON {"html": filterTable 行}——td.c-pd 日期（d MMM uuuu，SPEED 族既有
--            格式零新增）+ td.c-is 发布机构 + td.c-nt 标题锚；7 行实采
--     门覆盖 4/7（HKU×2/CUHK/HKUST 过；CityUHK=词表无该拼写〔token 边界〕、SSSDP/
--            HKMU=非八校拒）；招生季月级突发、非赛季长空窗→allow-empty（赛季空态）
--   jupas-programme-changes  JUPAS 联招课程变更（EN）
--     端点   https://www.jupas.edu.hk/en/programmes-offered/programme-changes/data/
--     结构   JSON 数组 [{information_updated, jupas_catalog_no, institution,
--            posting_date}]，按 catalogue 拆条，链接沿官方页 JS 构造
--            /en/programme/<inst小写>/<cat>（实采 200）；「All …」行落机构课程列表页
--     门覆盖 10/15 行（按目录 19/35 条八校条目；CityUHK/LingnanU token 边界、HKMU/
--            SSSDP 非八校拒）；招生季周级；非赛季空数组→allow-empty
--   ugc-whatnews            教资会官网最新动态（EN）
--     端点   https://www.ugc.edu.hk/eng/ugc/index.html（首页 What's-new 轮播，年稳定
--            URL——逐年滚动的 /press/<YYYY>/ 年页不用，免年更维护）
--     结构   #carouselnew .item 内 h5 标题 + p.newsDate "(dd.mm.yyyy)" + Read more 锚
--            （dd.mm 日/月序由新闻稿文件名 prYYYYMMDD 锚定；dd.MM.uuuu 解析格式新增）
--     门覆盖 当前窗 0/10；press 标题 2024-26 全量 EN 6/49、CHS 7/49（全为质素核证
--            系列）——稳态 ~2-3 条/年过门、其余零落库零 LLM（门置队列前）
--
-- 接入形态：三源均 HTML_LIST 族扩展（NewsHtmlListParser 第 6/7/8 族，#276 SPEED
--   第 5 族同型扩源先例）；fetcher/八校门/健康/日准入机制零改动。词表冻结不动
--   （#406 裁定：仅 HKU 排除式语境归先行词表票，本票不动词条）。
-- robots：jupas/ugc 两域 robots.txt 均 HTTP 404=REP 允许全抓（NewsHttpFetchClient
--   「robots 4xx 视为允许」既有口径）；全端点匿名 200；无 Crawl-delay。
--
-- 身份口径（#277「媒体/政府自有官网为自身平台」判例）：非 polyu.edu.hk 官网=
--   platform='media'、official=FALSE，不混入官方 RAG 证据；独立来源组（#187 同机构
--   一票）：jupas 两源同组 'jupas'（news 与 programme-changes 互为同一机构供给）、
--   ugc-whatnews='ugc'。
-- 配套（application.yaml 随本票）：gate-source-keys 追加三源；admission cap 各 10/日
--   （#277 新源起步口径，非命中已被门零消耗）；jupas 两源入 allow-empty-sources。
--
-- 铁律（#276 同口径）：seed 一律 enabled=FALSE——启用须获批上线窗内逐源技术检查
--   （robots、匿名访问、真实解析、八校门、预算）通过后启用并留痕。既有行 enabled
--   绝不 UPDATE，本脚本只 INSERT。
-- 幂等：ON CONFLICT (source_key) DO NOTHING，重复执行无害、不翻转既有禁用行。
-- 新环境（空数据卷）走 schema_pg.sql 全量初始化 + init_data_pg.sql 同源种子，不经本脚本。

INSERT INTO t_news_source (source_key, platform, display_name, display_name_en, home_url, fetch_endpoint, fetch_strategy, official, enabled, disabled_reason, independence_group) VALUES
  ('jupas-news',              'media', 'JUPAS 新闻公告',   'JUPAS News & Announcements', 'https://www.jupas.edu.hk/en/news/', 'https://www.jupas.edu.hk/en/news/json_news_filter/all/', 'HTML_LIST', FALSE, FALSE, 'manual', 'jupas'),
  ('jupas-programme-changes', 'media', 'JUPAS 联招课程变更', 'JUPAS Programme Changes',     'https://www.jupas.edu.hk/en/programmes-offered/programme-changes/', 'https://www.jupas.edu.hk/en/programmes-offered/programme-changes/data/', 'HTML_LIST', FALSE, FALSE, 'manual', 'jupas'),
  ('ugc-whatnews',            'media', '教资会最新动态',   'UGC What''s New',             'https://www.ugc.edu.hk/eng/ugc/index.html', 'https://www.ugc.edu.hk/eng/ugc/index.html', 'HTML_LIST', FALSE, FALSE, 'manual', 'ugc')
ON CONFLICT (source_key) DO NOTHING;

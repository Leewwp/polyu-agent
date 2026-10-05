-- 261005 #277 媒体/政府官网三源 seed（日报验收批 F；承接 #217 SCMP/RTHK 移交面）
--
-- 三源（2026-10-05 部署出口核定，生产腾讯 HK 出口实测全 200——本机直连 SCMP/RTHK 超时为出口差异）：
--   scmp-education  SCMP 教育 RSS   www.scmp.com/rss/318207/feed          RSS（1 跳重定向→尾斜杠；教育源实证可用，
--                     lang=en、50 item 跨 ~32.5 天驻留；robots *组 Crawl-delay 10 + 广告参数 Disallow，/rss/ 不在禁列）
--   rthk-local-news RTHK 英文本地   www.rthk.hk/rthk/news/rss/e_expressnews_elocal.xml
--                     RSS（2 跳重定向→rthk9.rthk.hk；lang=en、20 item 跨 ~2 天驻留——
--                     13h 采集间隔可覆盖，高峰日爆发 >20 条会漏采=记录在案的漏采边界，不提频）
--   gia-news        政府新闻公报英文 RSS  www.info.gov.hk/gia/rss/general_en.xml
--                     RSS（0 跳；lang=en-UK、100 item 跨 ~5 天；robots：项目 UA 走 *组仅禁 /isd/40anniversary/，
--                     Slurp=2s 组不适用——框架实际节拍 max(适用声明,10s)）
--
-- 三源走既有 RSS 族（NewsRssParser/NewsHttpFetchClient，含重定向逐跳与 robots 守卫、
-- XML 外部实体防护），不使用 Google 中转专用 RSS_GNEWS；RssNewsFetcher 已随行去 HTML
-- 原始摘要（#277 八校确定性门的准入证据）。
--
-- 身份口径：媒体/政府自有官网为自身平台、非 PolyU 官方——platform='media'、official=FALSE，
-- 不得混入官方 RAG 证据；独立来源组按发布机构（#187 事件投票去重）：scmp/rthk/hksar-gia。
-- 八校门只对三 source_key 生效（rag.news.gate-source-keys）：非命中零落库零 LLM。
--
-- 铁律（#276 同口径）：seed 一律 enabled=FALSE——启用须获批上线窗内逐源技术检查
--   （robots、匿名访问、真实解析、八校门、预算）通过后启用并留痕。既有行 enabled 绝不 UPDATE。
-- 幂等：ON CONFLICT (source_key) DO NOTHING。新环境走 schema_pg.sql + init_data_pg.sql 同源种子。

INSERT INTO t_news_source (source_key, platform, display_name, display_name_en, home_url, fetch_endpoint, fetch_strategy, official, enabled, disabled_reason, independence_group) VALUES
  ('scmp-education',  'media', 'SCMP 教育新闻',    'SCMP Education News',        'https://www.scmp.com/rss/318207/feed', 'https://www.scmp.com/rss/318207/feed', 'RSS', FALSE, FALSE, 'manual', 'scmp'),
  ('rthk-local-news', 'media', 'RTHK 英文本地新闻', 'RTHK Local News (EN)',       'https://www.rthk.hk/rthk/news/rss/e_expressnews_elocal.xml', 'https://www.rthk.hk/rthk/news/rss/e_expressnews_elocal.xml', 'RSS', FALSE, FALSE, 'manual', 'rthk'),
  ('gia-news',        'media', '政府新闻公报（英文）', 'HKSAR Government Info (EN)', 'https://www.info.gov.hk/gia/rss/general_en.xml', 'https://www.info.gov.hk/gia/rss/general_en.xml', 'RSS', FALSE, FALSE, 'manual', 'hksar-gia')
ON CONFLICT (source_key) DO NOTHING;

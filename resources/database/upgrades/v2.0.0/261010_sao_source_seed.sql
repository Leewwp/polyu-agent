-- 261010 #325 SAO 学生发展组活动日历源 seed（父票 #317 校园活动版面）
--
-- 查定结论（2026-10-09，本机 curl --noproxy '*' 直连实测）：
--   票面对象「学生活动页」非列表页——SAO 学生发展组各活动（Campus Life Festival/
--   Cultural Nights/Happy Hour 等）均为独立宣传页，events-and-programme/ 索引与
--   分组落地页均 301 回 /sao/ 首页（无目录页）；CLF 页 300KB 服务端渲染、零 XHR，
--   「Schedule」仅为单一活动的日期/时间/地点文本。
--   结构化入口在 /sao/news-and-events/event-calendar/：页面 JS 装载大学级 events 源
--   同款 Sitecore calendar API，calendar id=6840C445F9424C878A295D68627EBC4D——
--   响应 events[] 含 title/eventStartDate(ISO +08:00)/type/content(内嵌详情页绝对链接)，
--   走既有 EventsApiNewsFetcher/NewsEventsJsonParser 零代码接入。
--
-- smoke（生产 UA polyuguide-feed/1.0，curl -4 直连）：
--   2026/09=200 7 条（CLF 两段日期拆条+TalentShow+CulturalNight 等，全带链接）
--   2026/10=200 3 条（Council Election 系列 consulted）
--   2026/11=200 0 条（32 字节空数组）——日历渐进排期，下月空=正常空态，
--   源列 rag.news.allow-empty-sources（#186 VALID_EMPTY）；缺 events 数组/坏 JSON
--   仍 fail-closed（NewsEventsJsonParser 不因宽和豁免结构失配）。
--   #275 口径：活动 start 时刻非新闻发布时间，精度 UNKNOWN（fetcher 既有行为）。
--
-- robots 实判：www.polyu.edu.hk/robots.txt Disallow 仅各子站 search-results 与
--   /cpa/souvenirs/，API 路径未禁（大学级 events 源同判例）；无 Crawl-delay。
--
-- 身份口径：官网域内官方源 platform='official'、official=TRUE；独立来源组
--   polyu-official（#187 同机构一票，与 events/sao-news 同组）。
--
-- 铁律（#276 同口径）：seed 一律 enabled=FALSE——启用须获批上线窗内逐源技术检查
--   （robots、匿名访问、真实解析、预算）通过后启用并留痕。既有行 enabled 绝不 UPDATE。
-- 幂等：ON CONFLICT (source_key) DO NOTHING。新环境走 schema_pg.sql + init_data_pg.sql 同源种子。

INSERT INTO t_news_source (source_key, platform, display_name, display_name_en, home_url, fetch_endpoint, fetch_strategy, official, enabled, disabled_reason, independence_group) VALUES
  ('sao-events', 'official', '学生事务处学生活动日历', 'SAO Event Calendar', 'https://www.polyu.edu.hk/sao/news-and-events/event-calendar/', 'https://www.polyu.edu.hk/en/api/sitecore/calendar/get?id=6840C445F9424C878A295D68627EBC4D&date=YYYY/MM', 'JSON_API', TRUE, FALSE, 'manual', 'polyu-official')
ON CONFLICT (source_key) DO NOTHING;

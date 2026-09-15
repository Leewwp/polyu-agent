-- 260912 资讯扩源 批次一/二/三（A/C 档，2026-09-12 批准接入）
-- 批次一：学院级 YouTube 3 行（突破「V1 只上校级账号」的初始口径）；
--         SHTM 频道 ID 本机未确认（DNS 污染基线），生产侧解析后另行加行。
-- 批次二：Google News 检索源 5 行（strategy=RSS_GNEWS 新抓取器；媒体原文链接取自 description，
--         Google 中转链不落库；news.google.com 生产可达性挂 deploy 后冒烟）。
--         2026-09-14 收紧：媒体面两行剥裸缩写（PolyU/理大）保留全称并加 Hong Kong 地域
--         限定词；招生/奖学金/就业三行转 site:polyu.edu.hk 官网域内（回归原设计）。
--         生产侧本 SQL 尚未应用（挂上线第④步之后），此时修订零生产风险。
-- 批次三：官网域内 6 源（2026-09-12 JVM 路径形态探测全部命中既有官网列表形态，零新增选择器）；
--         招生子站/就业中心经探测无公开带日期列表，该两类由批次二媒体面补位。
-- 滞回兜底不变：连续抓取失败 ≥3 自动置 enabled=false；坏源/死频道不放大单点故障。
-- 权重键同步：rag.news.source-weights 随批补 14 键（官网源 3、youtube 学院级与 GNews 2）。

INSERT INTO t_news_source (source_key, platform, display_name, display_name_en, home_url, fetch_endpoint, fetch_strategy, official, enabled) VALUES
  -- 批次三：官网域内 6 源
  ('sao-news',       'official', '学生事务处公告',           'SAO News & Achievements',  'https://www.polyu.edu.hk/sao/news-and-events/news-and-achievements/', 'https://www.polyu.edu.hk/sao/news-and-events/news-and-achievements/', 'HTML_LIST', TRUE, TRUE),
  ('ar-notices',     'official', '教务处学生公告',           'AR Notices to Students',   'https://www.polyu.edu.hk/ar/students-in-taught-programmes/notices-to-students/', 'https://www.polyu.edu.hk/ar/students-in-taught-programmes/notices-to-students/', 'HTML_LIST', TRUE, TRUE),
  ('feng-news',      'official', '工学院动态',               'FENG News',                'https://www.polyu.edu.hk/feng/news-and-events/news/', 'https://www.polyu.edu.hk/feng/news-and-events/news/', 'HTML_LIST', TRUE, TRUE),
  ('comp-news',      'official', '计算与人工智能学系动态',   'COMP News',                'https://www.polyu.edu.hk/comp/news-and-events/news/', 'https://www.polyu.edu.hk/comp/news-and-events/news/', 'HTML_LIST', TRUE, TRUE),
  ('fce-news',       'official', '建设及环境学院动态',       'FCE News',                 'https://www.polyu.edu.hk/fce/news-and-events/news/', 'https://www.polyu.edu.hk/fce/news-and-events/news/', 'HTML_LIST', TRUE, TRUE),
  ('shtm-news',      'official', '酒店及旅游业管理学院动态', 'SHTM News',                'https://www.polyu.edu.hk/shtm/news-and-events/news/', 'https://www.polyu.edu.hk/shtm/news-and-events/news/', 'HTML_LIST', TRUE, TRUE),
  -- 批次一：学院级 YouTube
  ('youtube-feng',   'youtube',  '工学院 YouTube',             'PolyU FENG YouTube',   'https://www.youtube.com/channel/UC_j8-EPylkBJJwpczm2yHLw', 'https://www.youtube.com/feeds/videos.xml?channel_id=UC_j8-EPylkBJJwpczm2yHLw', 'RSS', FALSE, TRUE),
  ('youtube-comp',   'youtube',  '计算与人工智能学系 YouTube', 'PolyU COMP YouTube',   'https://www.youtube.com/channel/UCLtUaMQ9K8agJGi9HGYMIXg', 'https://www.youtube.com/feeds/videos.xml?channel_id=UCLtUaMQ9K8agJGi9HGYMIXg', 'RSS', FALSE, TRUE),
  ('youtube-fce',    'youtube',  '建设及环境学院 YouTube',     'PolyU FCE YouTube',    'https://www.youtube.com/channel/UCGhFmSw2a89i4dy8kNrRR_Q', 'https://www.youtube.com/feeds/videos.xml?channel_id=UCGhFmSw2a89i4dy8kNrRR_Q', 'RSS', FALSE, TRUE),
  -- 批次二：Google News 检索源
  ('gnews-polyu-en',          'gnews', 'GNews 英文检索',   'GNews: PolyU (EN)',     'https://news.google.com/search?q=%22Hong+Kong+Polytechnic+University%22+OR+%28%22PolyU%22+%22Hong+Kong%22%29', 'https://news.google.com/rss/search?q=%22Hong+Kong+Polytechnic+University%22+OR+%28%22PolyU%22+%22Hong+Kong%22%29&hl=en-HK&gl=HK&ceid=HK%3Aen', 'RSS_GNEWS', FALSE, TRUE),
  ('gnews-polyu-zh',          'gnews', 'GNews 中文检索',   'GNews: 理大 (ZH-Hant)', 'https://news.google.com/search?q=%E9%A6%99%E6%B8%AF%E7%90%86%E5%B7%A5%E5%A4%A7%E5%AD%B8+OR+%28%E7%90%86%E5%A4%A7+%E9%A6%99%E6%B8%AF%29', 'https://news.google.com/rss/search?q=%E9%A6%99%E6%B8%AF%E7%90%86%E5%B7%A5%E5%A4%A7%E5%AD%B8+OR+%28%E7%90%86%E5%A4%A7+%E9%A6%99%E6%B8%AF%29&hl=zh-HK&gl=HK&ceid=HK%3Azh-Hant', 'RSS_GNEWS', FALSE, TRUE),
  ('gnews-polyu-admission',   'gnews', 'GNews 招生报道',   'GNews: Admissions',     'https://news.google.com/search?q=site:polyu.edu.hk+admission', 'https://news.google.com/rss/search?q=site%3Apolyu.edu.hk+%28admission+OR+admissions+OR+JUPAS%29&hl=en-HK&gl=HK&ceid=HK%3Aen', 'RSS_GNEWS', FALSE, TRUE),
  ('gnews-polyu-scholarship', 'gnews', 'GNews 奖学金报道', 'GNews: Scholarships',   'https://news.google.com/search?q=site:polyu.edu.hk+scholarship', 'https://news.google.com/rss/search?q=site%3Apolyu.edu.hk+%28scholarship+OR+scholarships%29&hl=en-HK&gl=HK&ceid=HK%3Aen', 'RSS_GNEWS', FALSE, TRUE),
  ('gnews-polyu-career',      'gnews', 'GNews 就业报道',   'GNews: Careers',        'https://news.google.com/search?q=site:polyu.edu.hk+career', 'https://news.google.com/rss/search?q=site%3Apolyu.edu.hk+%28career+OR+careers+OR+employment%29&hl=en-HK&gl=HK&ceid=HK%3Aen', 'RSS_GNEWS', FALSE, TRUE)
ON CONFLICT (source_key) DO NOTHING;

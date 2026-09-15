-- 260912 资讯流数据模型 + 种子
-- 资讯流与 RAG 证据面物理隔离：四表独立于知识库管线，检索链路不读；
-- 条目按 url_hash=sha256(url) 幂等去重；90 天保留清理由 NewsRetentionJob 负责（不进通用 DataRetentionProperties）。
-- 来源终表：微博/知乎 robots+反爬双否决不入库；campus-reports 停更（最新 2023-06）种子即 enabled=false。

-- 信源注册表：fetch_strategy 四型（SITEMAP/HTML_LIST/RSS/JSON_API）；
-- consecutive_failures 沿用失败滞回范式（阈值 3 自动禁源）
CREATE TABLE t_news_source (
  id             BIGSERIAL PRIMARY KEY,
  source_key     VARCHAR(64)  NOT NULL UNIQUE,
  platform       VARCHAR(32)  NOT NULL,          -- official / youtube / weibo / zhihu / prn / events
  display_name   VARCHAR(128) NOT NULL,
  display_name_en VARCHAR(128),
  home_url       VARCHAR(512),
  fetch_endpoint VARCHAR(1024) NOT NULL,         -- 列表页 / RSS URL
  fetch_strategy VARCHAR(32)  NOT NULL,          -- SITEMAP / HTML_LIST / RSS / JSON_API
  official       BOOLEAN      NOT NULL DEFAULT TRUE,
  enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
  consecutive_failures INT    NOT NULL DEFAULT 0,
  create_time    TIMESTAMP    NOT NULL DEFAULT now(),
  update_time    TIMESTAMP    NOT NULL DEFAULT now()
);
COMMENT ON TABLE t_news_source IS '资讯信源注册表（V1 只上校级账号；扩源=加行无代码改动）';
COMMENT ON COLUMN t_news_source.source_key IS '信源稳定标识：news-sitemap / media-releases / youtube-main 等';
COMMENT ON COLUMN t_news_source.platform IS 'official=官网；youtube/prn=第三方平台（卡片带平台徽章）';
COMMENT ON COLUMN t_news_source.fetch_endpoint IS '抓取入口；events 型含 date=YYYY/MM 占位，由抓取器按当前月+下月替换';
COMMENT ON COLUMN t_news_source.fetch_strategy IS 'SITEMAP / HTML_LIST / RSS / JSON_API 四型';
COMMENT ON COLUMN t_news_source.official IS '是否官网（polyu.edu.hk）来源；false 的卡片带平台徽章';
COMMENT ON COLUMN t_news_source.consecutive_failures IS '连续抓取失败计数，阈值 3 自动置 enabled=false（失败滞回）';

-- 资讯条目：一 URL 一行；双语标题/摘要由 LLM 管线产出（官网条目原生 EN+繁中，LLM 压缩+繁转简）
CREATE TABLE t_news_item (
  id           BIGSERIAL PRIMARY KEY,
  source_id    BIGINT NOT NULL REFERENCES t_news_source(id),
  url          VARCHAR(1024) NOT NULL,
  url_hash     VARCHAR(64)  NOT NULL,            -- sha256，幂等去重唯一键
  title_zh     VARCHAR(512), title_en VARCHAR(512),
  summary_zh   TEXT, summary_en TEXT,
  category     VARCHAR(32) NOT NULL DEFAULT 'other',
  lang_raw     VARCHAR(8)  NOT NULL DEFAULT 'en',
  publish_time TIMESTAMP,
  fetch_time   TIMESTAMP NOT NULL DEFAULT now(),
  status       VARCHAR(16) NOT NULL DEFAULT 'published',  -- published / hidden
  heat         INT NOT NULL DEFAULT 0,           -- V2 跨源聚类预留
  create_time  TIMESTAMP NOT NULL DEFAULT now(),
  CONSTRAINT uq_news_item_url UNIQUE (url_hash)
);
CREATE INDEX idx_news_item_pub ON t_news_item(publish_time DESC) WHERE status = 'published';
COMMENT ON TABLE t_news_item IS '资讯条目表（AI 双语摘要+永久原文外链；不进 RAG 证据面）';
COMMENT ON COLUMN t_news_item.url_hash IS 'sha256(url) 十六进制，幂等去重唯一键';
COMMENT ON COLUMN t_news_item.category IS '固定 8 类：admission/scholarship/research/campus/event/career/exchange/admin（+other 兜底）';
COMMENT ON COLUMN t_news_item.lang_raw IS '原文语言（en/zh-Hant/zh-Hans）';
COMMENT ON COLUMN t_news_item.status IS 'published=展示；hidden=人工抽检应急下架（admin 最小端点）';

-- 主题注册表：种子词表 20 行（原型 TOPICS 注册表，curated=true）；
-- LLM 新提案以 curated=false 入库不进目录，人工抽检审后转正/合并/丢弃
CREATE TABLE t_news_topic (
  id            BIGSERIAL PRIMARY KEY,
  slug          VARCHAR(64) NOT NULL UNIQUE,
  name_zh       VARCHAR(128) NOT NULL, name_en VARCHAR(128),
  topic_group   VARCHAR(32) NOT NULL,      -- FACULTY / RESEARCH / STUDENT_AFFAIRS
  description_zh VARCHAR(512), description_en VARCHAR(512),
  curated       BOOLEAN NOT NULL DEFAULT TRUE,  -- 种子词表 TRUE；AI 新提案 FALSE 待人工抽检审后转正
  status        VARCHAR(16) NOT NULL DEFAULT 'active',
  create_time   TIMESTAMP NOT NULL DEFAULT now(),
  update_time   TIMESTAMP NOT NULL DEFAULT now()
);
COMMENT ON TABLE t_news_topic IS '资讯主题词表（三维分组：学院与部门/研究领域与话题/学生事务）';
COMMENT ON COLUMN t_news_topic.slug IS '主题稳定标识，与原型 TOPICS 注册表键一致';
COMMENT ON COLUMN t_news_topic.topic_group IS 'FACULTY=学院与部门；RESEARCH=研究领域与话题；STUDENT_AFFAIRS=学生事务';
COMMENT ON COLUMN t_news_topic.curated IS 'TRUE=策展词表进目录；FALSE=AI 提案待审不进目录';

-- 条目-主题关联：LLM 单次调用输出 topics[]（1–4 个）落此表
CREATE TABLE t_news_item_topic (
  item_id  BIGINT NOT NULL REFERENCES t_news_item(id) ON DELETE CASCADE,
  topic_id BIGINT NOT NULL REFERENCES t_news_topic(id),
  PRIMARY KEY (item_id, topic_id)
);
CREATE INDEX idx_news_item_topic ON t_news_item_topic(topic_id);
COMMENT ON TABLE t_news_item_topic IS '条目-主题多对多关联（保留期清理随 t_news_item 级联删除）';

-- ============ 种子：信源 7 行（终表口径） ============
-- campus-reports 停更（最新 2023-06）默认禁用；PRN 月更低频（周更补充源）；
-- YouTube RSS 生产香港服务器可达即接入（已知遗留：本机 DNS 污染未实测端点）
INSERT INTO t_news_source (source_key, platform, display_name, display_name_en, home_url, fetch_endpoint, fetch_strategy, official, enabled) VALUES
  ('news-sitemap',   'official', '官网新闻索引',   'Official News Sitemap',      'https://www.polyu.edu.hk/',       'https://www.polyu.edu.hk/news-sitemap.xml', 'SITEMAP',   TRUE,  TRUE),
  ('media-releases', 'official', '官网媒体发布',   'Media Releases',             'https://www.polyu.edu.hk/media/media-releases/', 'https://www.polyu.edu.hk/media/media-releases/?page=1', 'HTML_LIST', TRUE, TRUE),
  ('recent-focus',   'official', '官网最新动态',   'Recent Focus',               'https://www.polyu.edu.hk/recent-focus/', 'https://www.polyu.edu.hk/recent-focus/?page=1', 'HTML_LIST', TRUE, TRUE),
  ('events',         'official', '官网活动日历',   'Events Calendar',            'https://www.polyu.edu.hk/events/', 'https://www.polyu.edu.hk/en/api/sitecore/calendar/get?id=F45B40DE7F3F4AFA9B2D02B1D824C1E0&date=YYYY/MM', 'JSON_API', TRUE, TRUE),
  ('campus-reports', 'official', '官网校园报道',   'Campus Reports',             'https://www.polyu.edu.hk/media/campus-reports/', 'https://www.polyu.edu.hk/media/campus-reports/?page=1', 'HTML_LIST', TRUE, FALSE),
  ('prn',            'prn',      'PR Newswire 理大频道', 'PR Newswire (PolyU)',  'https://www.prnewswire.com/news/the-hong-kong-polytechnic-university-(polyu)/', 'https://www.prnewswire.com/news/the-hong-kong-polytechnic-university-(polyu)/', 'HTML_LIST', FALSE, TRUE),
  ('youtube-main',   'youtube',  '理大官方 YouTube 频道', 'PolyU Official YouTube Channel', 'https://www.youtube.com/channel/UCkio4asleKcQVRVEM8RnXlQ', 'https://www.youtube.com/feeds/videos.xml?channel_id=UCkio4asleKcQVRVEM8RnXlQ', 'RSS', FALSE, TRUE)
ON CONFLICT (source_key) DO NOTHING;

-- ============ 种子：主题词表 20 行（原型 TOPICS 注册表，project-docs/staging/u12-feed-prototype/index.html） ============
-- 三维分组：学院与部门 6 / 研究领域与话题 6 / 学生事务 8；学生事务组与固定 8 类打通复用
INSERT INTO t_news_topic (slug, name_zh, name_en, topic_group, description_zh, description_en, curated, status) VALUES
  ('eng',        '工学院',             'Faculty of Engineering',             'FACULTY',        '工学院及旗下学系的科研、课程与活动动态',   'Research, programmes and events from FENG and its departments', TRUE, 'active'),
  ('bus',        '工商管理学院',       'Faculty of Business',                 'FACULTY',        '商学院及旗下学系与中心的动态',             'Updates from FB and its schools and centres', TRUE, 'active'),
  ('csm',        '计算及数理科学学院', 'Faculty of Computing & Math Sciences','FACULTY',        '计算机科学、数学与数据科学方向的动态',     'Computing, mathematics and data science updates', TRUE, 'active'),
  ('ce',         '建设及环境学院',     'Faculty of Construction & Environment','FACULTY',       '建筑、土木、环境与测量方向的动态',         'Built environment and civil updates', TRUE, 'active'),
  ('hss',        '医疗及社会科学院',   'Faculty of Health & Social Sciences', 'FACULTY',        '医疗健康与社会科学方向的动态',             'Health and social sciences updates', TRUE, 'active'),
  ('htm',        '酒店及旅游业管理学院', 'School of Hotel & Tourism Mgmt',    'FACULTY',        '酒店与旅游管理教育研究的动态',             'Hospitality and tourism education and research', TRUE, 'active'),
  ('ai',         '人工智能',           'Artificial Intelligence',             'RESEARCH',       'AI 算法、应用与治理方向的科研与活动',      'Research and events on AI algorithms, applications and governance', TRUE, 'active'),
  ('biomed',     '生物医药与健康',     'Biomedicine & Health',                'RESEARCH',       '医学、生物工程与健康科学方向的进展',       'Advances in medicine, bioengineering and health sciences', TRUE, 'active'),
  ('energy',     '新能源与可持续',     'Energy & Sustainability',             'RESEARCH',       '新能源、碳中和与可持续发展动态',           'New energy, carbon neutrality and sustainability', TRUE, 'active'),
  ('city',       '智慧城市',           'Smart City',                          'RESEARCH',       '智慧城市与城市韧性相关研究与实践',         'Smart-city and urban resilience research', TRUE, 'active'),
  ('materials',  '新材料',             'Advanced Materials',                  'RESEARCH',       '新材料研发与应用的进展',                   'Advanced materials research and applications', TRUE, 'active'),
  ('gba',        '大湾区合作',         'Greater Bay Area',                    'RESEARCH',       '理大与大湾区机构的合作与交流',             'Collaborations and exchanges across the GBA', TRUE, 'active'),
  ('admission',  '招生入学',           'Admissions',                          'STUDENT_AFFAIRS','本科与研究生申请、截止日与录取动态',       'Ug and pg applications, deadlines and admissions', TRUE, 'active'),
  ('campus',     '校园生活',           'Campus Life',                         'STUDENT_AFFAIRS','体育、社团、宿舍与校园日常',               'Sports, clubs, halls and everyday campus', TRUE, 'active'),
  ('event',      '活动讲座',           'Events & Lectures',                   'STUDENT_AFFAIRS','公开讲座、工作坊与报名中的活动',           'Public lectures, workshops and open events', TRUE, 'active'),
  ('career',     '就业实习',           'Careers & Internships',               'STUDENT_AFFAIRS','招聘会、岗位信息与职业发展',               'Career fairs, openings and development', TRUE, 'active'),
  ('exchange',   '国际交流',           'Exchange & Study Abroad',             'STUDENT_AFFAIRS','交换计划、游学与海外学习机会',             'Exchange programmes and overseas study', TRUE, 'active'),
  ('housing',    '宿舍与生活',         'Housing & Living',                    'STUDENT_AFFAIRS','宿舍申请、住宿生活与周边租房',             'Hall applications and off-campus housing', TRUE, 'active'),
  ('scholarship','奖学金资助',         'Scholarships',                        'STUDENT_AFFAIRS','入学奖学金、专项资助与申请通道',           'Entrance scholarships, grants and applications', TRUE, 'active'),
  ('admin',      '校务公告',           'Official Notices',                    'STUDENT_AFFAIRS','校历变更、政策与服务调整',                 'Calendar, policy and service updates', TRUE, 'active')
ON CONFLICT (slug) DO NOTHING;

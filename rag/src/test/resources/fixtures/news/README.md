# 资讯抓取 fixture（news/）

既有 6 件（2026-09-10/15 落）服务既有解析器测试：`google-news-rss.xml`（GNews RSS2.0）、`youtube-rss.xml`（Atom+media RSS 合成件）、`news-sitemap.xml`、`events.json`、`media-releases.html`、`prn-list.html`。

## #188 批 2 新增（2026-09-30，分支 `feat/news-source-seed-batch2`）

四源列表页真实响应快照（裁剪件：只保留列表容器与条目结构，图片 srcset 与正文字段裁去——与既有 `media-releases.html` 同实践；结构与选择器原样）。抓取口径与生产 `NewsHttpFetchClient` 同款：UA=`polyuguide-feed/1.0 (+https://polyuguide.com)`，`curl -4` 直连（polyu.edu.hk 本机直连可达，未经代理）。快照为真实第三方内容，仅作本项目测试夹具使用，不额外公开传播。

| fixture | source_key | 抓取 URL | 实采状态 | 解析族 | sha256 |
|---|---|---|---|---|---|
| alumni-news.html | alumni-news | https://www.polyu.edu.hk/alumni/news/ | 200，10 条，留 3 | 官网列表族（既有，零代码） | d174f3fa0fc05218afdb09e64d705cea5f1cd408956687576a8037799443441d |
| fb-news.html | fb-news | https://www.polyu.edu.hk/fb/news-events/news/ | 200，10 条，留 3 | 官网列表族（既有，零代码） | 3d11ea53d24add8013900c6e44df1c9eb76cc57556f6511eaae307af8fbdf2e0 |
| fhss-news.html | fhss-news | https://www.polyu.edu.hk/fhss/news-and-events/news-and-events/ | 200，10 条，留 3 | 官网列表族（既有，零代码） | 33bcd222fd16fbf55fe22260c49d76c276d57128958ecb76a62b47f3c93b97f4 |
| lib-news.html | lib-news | https://www.lib.polyu.edu.hk/news | 200，10 行，留 6 | 图书馆 Drupal views 族（#188 新增第 4 族） | bcab8a36ac0af8111cf256f4009ea8ffa30da19e18017b12870f76d69414e94b |

robots 实判（抓取时同 host 原样快照留存本地研究档案，不入库）：
- `www.polyu.edu.hk/robots.txt`：无 Crawl-delay，Disallow 仅各子站 search-results——alumni/fb/fhss 列表路径未禁。
- `www.lib.polyu.edu.hk/robots.txt`（sha256 278e83bcf567badfebcdea4d5d20ca9898e4449fe4eb2e3b5a08227b4ca9b762）：**Crawl-delay: 10**（`User-agent: *`），Disallow 为 Drupal 管理路径——`/news` 未禁。10s < `NewsHttpFetchClient` 单次等待上限 60s，走正常节拍等待（不触发 defer 豁免）。

lib-news 行结构（Drupal views）：`div.views-row` 行容器 / `h3.views-field-title a` 标题锚 / `.views-news-events-posted` 裸文本节点为日期（"Friday, September 18, 2026 - 08:30"）/ 行内 `.badge` 为类别（News/Event/Notice）。部分行 URL 无日期段（如 `/news/new-ai-workstations-...`），日期一律取自 posted 文本。

## #325 新增（2026-10-09，分支 `daily/sao-source-325`）

SAO 学生发展组活动日历真实响应快照（裁剪件：7 条留 4——Campus Life Festival 两段日期、Annual Talent Show、Cultural Night；字段结构与 `eventTypeList` 原样）。抓取口径与生产同款：UA=`polyuguide-feed/1.0 (+https://polyuguide.com)`，`curl -4` 直连。快照为真实第三方内容，仅作本项目测试夹具使用，不额外公开传播。

| fixture | source_key | 抓取 URL | 实采状态 | 解析族 | sha256 |
|---|---|---|---|---|---|
| sao-events.json | sao-events | https://www.polyu.edu.hk/en/api/sitecore/calendar/get?id=6840C445F9424C878A295D68627EBC4D&date=2026/09 | 200，7 条，留 4 | Sitecore calendar JSON 族（既有 events.json 同款，零代码） | 21b014714e61cc614153a87222c580ae1219c7a477e1fdbae32ed2e0f3e6b20a |

robots 实判：`www.polyu.edu.hk/robots.txt` Disallow 仅各子站 search-results 与 `/cpa/souvenirs/`，API 路径 `/en/api/sitecore/calendar/get` 未禁（大学级 events 源同判例）。

sao-events 特记（与大学级 events 的差异）：calendar id 不同（`6840C445…` vs `F45B40DE…`）；`eventTypeList` 仅 Student Development 一类；**跨段活动按日期段拆条**（CLF 9/3-4 与 9/7-8 两条，详情页 slug 含日期段 `-090304`/`-090708`，URL 去重后仍独立成条）；日历渐进排期——下月空数组属正常空态（2026-11 实采 `{"events":[],"eventTypeList":[]}` 32 字节），源列 `allow-empty-sources`（#186 有效空语义），空月断言钉在 `NewsEventsJsonParserTests#saoEmptyMonthIsValidEmptyOnlyWhenNotFailClosed`。

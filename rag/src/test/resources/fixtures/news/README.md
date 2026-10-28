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

## #324 新增（2026-10-09，分支 `daily/cpeo-source-324`）

`cpeo-events.json`：CPEO 文化活动日历（含 PolyU Cinema 常驻放映系列）真实响应快照（裁剪件：content 只留标题锚结构，与 `events.json` 同惯例；title/eventStartDate/start-date/type/csscode/详情链接全真）。抓取口径同上（`curl --noproxy '*'` 直连）。同 host robots 判例不变（主域 * 组仅 Disallow search-result 类路径，无 Crawl-delay）。快照为真实第三方内容，仅作本项目测试夹具使用。

| fixture | source_key | 抓取 URL | 实采状态 | 解析族 | sha256 |
|---|---|---|---|---|---|
| cpeo-events.json | cpeo-events | https://www.polyu.edu.hk/en/api/sitecore/calendar/get?id=BA1FFC08557D4D82A33C584551D93F99&date=2026/10 | 200，9 条，留 4（伞卡/中文标题/PolyU Cinema 放映场/空 type） | JSON_API（NewsEventsJsonParser 既有，零代码） | b7db058f0fd0f45e419c09fe6b01427d0ddcd04b724fa9f24955e2d0669963ea |

实采备注（空月常态）：同日查 2026/11=1 条、2026/12=1 条（均 "PolyU Cinema" 系列置顶伞卡，eventStartDate=2026-01-27 常驻）、2027/01=0 条、2027/02=0 条——源入 `rag.news.allow-empty-sources`（#186）防静月 fail-closed 连败误触自动隔离。

## #325 新增（2026-10-09，分支 `daily/sao-source-325`）

SAO 学生发展组活动日历真实响应快照（裁剪件：7 条留 4——Campus Life Festival 两段日期、Annual Talent Show、Cultural Night；字段结构与 `eventTypeList` 原样）。抓取口径与生产同款：UA=`polyuguide-feed/1.0 (+https://polyuguide.com)`，`curl -4` 直连。快照为真实第三方内容，仅作本项目测试夹具使用，不额外公开传播。

| fixture | source_key | 抓取 URL | 实采状态 | 解析族 | sha256 |
|---|---|---|---|---|---|
| sao-events.json | sao-events | https://www.polyu.edu.hk/en/api/sitecore/calendar/get?id=6840C445F9424C878A295D68627EBC4D&date=2026/09 | 200，7 条，留 4 | Sitecore calendar JSON 族（既有 events.json 同款，零代码） | 21b014714e61cc614153a87222c580ae1219c7a477e1fdbae32ed2e0f3e6b20a |

robots 实判：`www.polyu.edu.hk/robots.txt` Disallow 仅各子站 search-results 与 `/cpa/souvenirs/`，API 路径 `/en/api/sitecore/calendar/get` 未禁（大学级 events 源同判例）。

sao-events 特记（与大学级 events 的差异）：calendar id 不同（`6840C445…` vs `F45B40DE…`）；`eventTypeList` 仅 Student Development 一类；**跨段活动按日期段拆条**（CLF 9/3-4 与 9/7-8 两条，详情页 slug 含日期段 `-090304`/`-090708`，URL 去重后仍独立成条）；日历渐进排期——下月空数组属正常空态（2026-11 实采 `{"events":[],"eventTypeList":[]}` 32 字节），源列 `allow-empty-sources`（#186 有效空语义），空月断言钉在 `NewsEventsJsonParserTests#saoEmptyMonthIsValidEmptyOnlyWhenNotFailClosed`。

## #410 新增（2026-10-29，分支 `feat/410-bline-official-sources`）

B 线三源真实响应快照：JUPAS 两端点为整件（页面 JS 取数口原样，分别为 JSON `{html: filterTable 行}` 与 JSON 数组）；UGC 为首页 What's-new 轮播区块裁剪件（横幅轮播留纯图片首帧证伪、Recommended 区 h5 随页随带但不在 `#carouselnew .item` 选择器域内）。抓取口径与生产同款：UA=`polyuguide-feed/1.0 (+https://polyuguide.com)`，生产腾讯 HK 出口 `curl -4` 直连（本机直连对 jupas/ugc 出口差异不可复现生产判例）。快照为真实第三方内容，仅作本项目测试夹具使用，不额外公开传播。

| fixture | source_key | 抓取 URL | 实采状态 | 解析族 | sha256 |
|---|---|---|---|---|---|
| jupas-news.json | jupas-news | https://www.jupas.edu.hk/en/news/json_news_filter/all/ | 200，7 行，整件 | JUPAS 新闻 JSON 端点族（#410 第 6 族） | 1eb7ab15d334256edeee0c49d769d7c6aa1aadf1e40dd25bc3ea4dc90fce1c4f |
| jupas-programme-changes.json | jupas-programme-changes | https://www.jupas.edu.hk/en/programmes-offered/programme-changes/data/ | 200，15 行（拆条 35），整件 | JUPAS 课程变更 JSON 数组族（#410 第 7 族） | 4fea82beb7cdb320a38e97f81260dfd743f8b2554efdf46f519e4921f39fe452 |
| ugc-whatnews.html | ugc-whatnews | https://www.ugc.edu.hk/eng/ugc/index.html | 200，轮播 10 条全留 | UGC 首页 What's-new 轮播族（#410 第 8 族，dd.MM.uuuu 日期格式新增） | 4fcd4f1c66b9c135b382e839e1afe0202803d856fc5e83f014025ac343ebb14d |

robots 实判（抓取时生产侧原样复核）：`www.jupas.edu.hk/robots.txt` 与 `www.ugc.edu.hk/robots.txt` 均 HTTP 404——REP 404=允许全抓（`NewsHttpFetchClient`「robots 4xx 视为允许」既有口径）；无 Crawl-delay。日/月序锚点：UGC newsDate `(09.10.2026)` 对应新闻稿文件名 `pr20261009`。

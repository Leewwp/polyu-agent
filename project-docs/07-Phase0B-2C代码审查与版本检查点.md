# 07 · Phase 0B-2C：HTML Parser 代码审查与版本检查点

> 执行日期：2026-09-04。目标：独立审查 0B-2B 的代码质量、实验口径和提交边界，判断当前成果能否进入版本提交与下一阶段。
> 方式：只读审查 + 证据留痕；审查中发现的明确缺陷做了最小修复（见 §2）。人工审查通过后于同日执行提交收口：compose 端口收紧为 127.0.0.1、`CHANGES.md` 解禁入库、按 §13 拆分为 4 个本地提交（未推送远端）。
> 约束遵守：未导入新语料、未做模型 A/B、未改 Prompt/Embedding/Reranker/Chat、未删任何旧库/数据卷/报告、未执行 `docker compose down -v`、未读取或输出任何 API Key 值；收口阶段未重启或销毁运行中的容器，仅修改 compose 源文件。
>
> **总结论：Pass（带 1 项已修复缺陷；原 3 项低风险遗留观察中 compose 端口绑定已在提交前修复，余 2 项移交后续）。已按 §13 提交（本地 4 commits，未推送），建议进入 20–30 页语料构建（Phase 0B-3A）。**

---

## 1. 审查结论：**Pass**

- 代码审查发现 **1 个明确可复现的内容丢失缺陷**（单列表格数据行全丢），本轮已修复并补回归测试（34/34 通过）；
- 实验口径校准发现 **49/68 席位口径混用、MRR 基线轻微漂移、"页面未变"证据不足** 三处问题，已在 06 报告追加 §13 勘误（原始记录未删），结论方向全部维持；
- 凭据与 compose 检查：无真实凭据、无敏感路径；审查时端口绑定为 0.0.0.0 属低风险遗留观察，提交收口时已将源配置全部收紧为 127.0.0.1（§9、§11-1 更新为已解决）；
- 无阻断性问题。

## 2. 实际代码修改清单（本轮 0B-2C 新增改动）

| 文件 | 类型 | 说明 |
| --- | --- | --- |
| `bootstrap/.../core/parser/HtmlDocumentParser.java` | 修改（+5 行） | **修复单列表格缺陷**：`isGroupHeaderRow` 增加 `maxCols <= 1 → false` 前置判断。此前 1 列表格（无论有无 thead）所有数据行都被判为"全宽分组行头"而尽数丢弃（probe 复现：rows=[]）。单列时任何单元格 colspan 都 ≥ 全宽，"全宽组头"概念不成立。修复后 `TableChunker.renderKeyValueRow` 对空表头安全（无列名时只渲染值） |
| `bootstrap/.../core/parser/HtmlDocumentParserTest.java` | 修改（+39 行） | 新增 2 个回归测试：① `keepsSingleColumnTableRows`（单列表格行保留）；② `keepsNestedListTextInsideItem`（li 内嵌套 ul 文本并入该项不丢失——锁定既有拍平行为，0B-2B 未覆盖嵌套列表） |
| `project-docs/06-...md` | 修改 | 追加 §13 勘误与口径校准（7 项，见 §8；原始实验记录未删除） |
| `project-docs/07-...md` | 新增 | 本报告 |

未改动：Tika 让渡、ParserType、pom、.gitignore、README、CHANGES（0B-2B 原状）；核心 RAG 链路零触碰。

**提交收口阶段新增改动（同日，人工审查授权后，§9/§11/§12/§13 同步更新）**：

| 文件 | 类型 | 说明 |
| --- | --- | --- |
| `resources/docker/polyu-local-0b1.compose.yaml` | 修改 | 宿主机发布端口 8 处全部加 `127.0.0.1` 前缀（5434/6381/9000/9001/9876/10909/10911/10912），头部注释同步；broker 通告地址本就是 `brokerIP1=127.0.0.1`，loopback 发布与宿主机客户端路径配对，无协议性障碍。`docker compose config` 验证通过；运行中容器未为此重启或重建 |
| `.gitignore` | 修改 | 移除 `/CHANGES.md` 忽略规则（README 已公开引用该清单）；`.claude/`、`/CLAUDE.md`、`/docs/agents/`、`application-local.yaml` 忽略规则未动，未顺带解禁其他路径 |
| `CHANGES.md` | 新纳入 | 首次入库（正常解禁后 add，非 `git add -f`）；补一行本机 compose 栈记录，使 README"全部改动逐条记录"的引用成立 |
| `README.md` | 修改 | 当前状态段补 0B-2C 检查点结论与 07 链接；目录结构括注更新为"项目规划与路线图、各阶段审计报告" |

## 3. Parser 注册与 MIME 交接结论：**通过**

逐项核对（代码证据 + 启动实测）：

| 检查项 | 结论 | 证据 |
| --- | --- | --- |
| (MIME × 档位) 唯一性 | ✓ | `ParserRegistry.register`（`ParserRegistry.java:102-107`）put 后检查 previous，双认领即 `ServiceException` 启动失败，不静默覆盖 |
| `text/html` 交接 | ✓ | Tika 精确键已移除，Html 精确认领 FAST 档；`lookup` 精确键优先于 Tika 的 `text/*` 通配（`ParserRegistry.java:210-220`） |
| `application/xhtml+xml` 交接 | ✓ | 同上，双键均在 Html 的认领集合（单测 `registryRoutesHtmlUniquely` 断言） |
| Tika 移除后其他 MIME 不受影响 | ✓ | `text/*`（长尾）、`application/json` / `xml` / `rtf` 保留；`text/plain` / `text/x-web-markdown` 仍路由 Markdown（精确键优先）；`text/rtf-latin1-literal` 仍落 Tika 通配（单测断言） |
| FIDENCY 档 HTML 路由 | ✓ | Html 只声明 FAST；四档查找（请求档 → FAST 回落，`ParserRegistry.find`）保证 fidelity 请求落到 Html |
| 启动自检 | ✓ 实测 | 本轮以 local profile 跑 `RagentCoreApplicationTests`（完整 Spring 上下文，连 polyu-pg 5434 / polyu-redis 6381）：`解析器注册表就绪 精确键=30 通配键=1 自检扩展名=21 全部精确命中`，与 06 附录 B 记录一致；测试通过 |

## 4. 正文清洗误删/漏删风险

回退顺序 `main → [role=main] → article → .region-content → #content / #main-content / .main-content / [itemprop=articleBody] / #mw-content-text / .post-content / .entry-content → body`，第一个文本量 ≥80 字符者胜出；候选全不达标取**最长候选**（正文极短页不退回整页）；无候选才 body 兜底（只删 body 直接子级 header/footer/nav/aside，嵌套同名标签不动）。

| 风险点 | 评估 | 依据 |
| --- | --- | --- |
| 短正文阈值误回退整个 body | **低** | 候选全短时取最长候选而非 body（`selectContentRegion`，单测 `fallsBackThroughSelectors` 覆盖 article 优先）；代价是 main 区域外的补充正文（如右侧扩展阅读）不入语料——可接受取舍 |
| `.modal` / `.side-menu` / 导航 / 页脚误删正文 | **低（本站）/ 中（通用）** | PolyU Library 五页实证无误删（06 §6 正文实质保留率 100%）；通用风险点：`[class*=cookie i]`、`[class*=main-menu]` 等 contains 匹配可能误删含这些子串的正文容器（如 `<div class="cookie-policy-content">`），`.modal` 内容视为模板。属已知取舍，扩页遇新子站时按 06 §12-3 抽样验证 |
| 隐藏元素清理误删 | **低** | `[hidden]` / `[aria-hidden=true]` / `.sr-only` 等删的是对读者不可见内容；`details`（视觉折叠）**刻意保留**（FAQ 形态依赖） |
| 找不到预期结构时安全降级 | ✓ | body 兜底 + 顶层 chrome 删除（单测 `fallsBackToBodyAndStripsTopLevelChrome`）；空页面/纯导航页产出空 blocks（单测覆盖） |
| 通用 vs PolyU 专用规则 | `.region-content` / `.book-navigation` / Drupal region 系列（`.region-menu` 等）是 Drupal/PolyU Library 专用；其余（nav/aside/role/面包屑/分享栏/skip/cookie/hidden 系列）为通用规则 | 扩页到 AR/SAO 需重新抽样（05 §11-5 风险仍在，非本轮新增） |

## 5. Block 与表格转换审查

**DOM → Block**（`RegionWalker`）：

- 标题 h1–h6 → HeadingBlock 层级保真；问句段落（≤200 字符且以 `?`/`？` 结尾）升格 Heading，`questionLevel = max(2, lastHeading+1)`，连续问句同级（单测断言）；
- 行内元素与透明容器递归不截断文本缓冲——两个真实回归（行内切碎句子、仅图片段落打散列表）均有专门测试（`doesNotSplitParagraphAtInlineTags` / `keepsSimpleListWhenLiContainsOnlyImageParagraph`）；
- `li > p` 富列表项逐项走常规 dispatch（问答结构保留），简单项聚合 ListBlock；**嵌套 `li > ul` 拍平进 item 文本**（结构丢、文本留）——本轮补测试锁定该行为（`keepsNestedListTextInsideItem`）；
- `details/summary` 问题成标题；figure 只取 figcaption；dl 术语+解释合并单段；blockquote/pre 整段；表单控件与 img 跳过（alt 不产块，06 §2.4 已声明的取舍）；
- 空块：flush / 空 p / 空表格均不产块；顺序：块级 dispatch 前 flush 缓冲，匿名文本与块的相对顺序保持；未发现重复输出路径（caption/summary/figcaption 均先 remove 再 walk）。

**表格**（本轮重点）：

| 检查项 | 结论 |
| --- | --- |
| rowspan / colspan 二维展开 | ✓ 首列落值次列留空、跨行值沿列延续（单测覆盖交叉展开）；colspan=0 / 负值 / 非数字回落 1 |
| 无 thead 表格 | ✓ "数据行出现前的全 th 行"或"非空格皆 h1-h6"判表头（P2/P3 形态，单测覆盖） |
| 全宽组标题行 | ✓ 单格 colspan≥全宽 → 组名并入后续每行首列 + Group 列；**本轮修复：maxCols==1 时不再判定**（此前单列表格数据全丢） |
| 交叉合并单元格 | ✓ 单测 `expandsComplexLoanTable`（rowspan×colspan 交叉） |
| 空单元格 | ✓ 按网格列位留空，TableChunker 按列索引对齐 |
| 多行表头 | ✓ 非空值 `\|` 拼接 + rowspan 延续去重（对齐 ExcelTableNormalizer） |
| 组名逐行重复的量 | 可接受：P3 大表 10 数据行每行带组名前缀，总 chunk 数 19（vs Tika 97），1024 字符预算内无碎片化；代价是行间文本非线性相邻（词集口径才能正确度量保留率，见 §8） |
| KV 格式对语义 | 正面：列名进向量文本使"列名+值"自包含可检索；对顺序敏感的 shingle 度量会系统性低估（06 §6.1 已归因） |

## 6. 稳定性、安全性与可维护性

- **内存**：`parseStructured` 接口即 `byte[]`，Jsoup DOM 在既有下载侧大小限制之内，无新增放大点；
- **递归深度**：`walkChildren`/`dispatch` 无深度上限——万层级恶意嵌套 DOM 理论上可 StackOverflow。真实网页与 PolyU 语料不构成该形态，按"不为理论极端重构"原则**不修**，记为已知限制；
- **malformed HTML**：Jsoup 容错 + 解析异常整体捕获返回空 blocks（参数化测试 3 例）；字符集：charset 传 null 由 meta charset 触发重读（GBK 测试）；HTML 注释为非 Element/TextNode 节点天然跳过，script/style/template 全局先删；
- **安全**：所有 Block 文本来自 `element.text()`（Jsoup 已解码实体、剥离标签），**纯文本下行，无原始 HTML 传给前端/模型**；
- **可维护性**：739 行单类，但结构上已按职责分段（主体选择 / 模板清洗常量 / RegionWalker 遍历 / FAQ 启发式 / 表格展开 ~250 行）。表格展开逻辑边界清晰、可独立提取，但依赖 RegionWalker 内部状态（prov）且现有测试全部经由公开入口覆盖——**本轮不拆**（避免机械拆类与无收益重构），后续若扩第二个子站模板再议。

## 7. 测试覆盖矩阵与实际执行结果

**覆盖矩阵**（0B-2B 原有 32 + 本轮新增 2 = 34 用例）：

| 场景 | 用例 | 断言质量 |
| --- | --- | --- |
| 导航/页头/页脚/模板删除 | stripsChromeOutsideAndInsideMain、stripsBookNavigationPager | 断言具体文本缺席 + contentRegion 元数据 |
| 主体选择优先级 / body 回退 | prefersMain、fallsBackThroughSelectors、fallsBackToBody | 断言选中区文本在、未选区文本不在 |
| 标题层级 | preservesHeadingLevels | containsExactly 逐级 |
| 列表 | preservesOrderedAndUnorderedLists、**嵌套列表（本轮+）** | ordered 标志 + 项序逐项 |
| FAQ | promotesQuestionParagraphs、promotesFaqEntriesInsideRichListItems、handlesDetailsSummary | 标题集合 + 同级 + 答案段紧随 |
| 表格 | parsesPlainTable、expandsRowspan、mergesGroupHeaderRow、expandsComplexLoanTable、doesNotTreatDataColspanRowAsGroup、**单列表格（本轮+）** | headers/rows 逐格 containsExactly |
| 空页/纯导航页 | returnsEmptyBlocks、navigationOnly | 空产物 + 导航词缺席 |
| 实体/字符集 | keepsEntitiesAndChinese、GBK 重读 | 精确子串 |
| 注册表 | registryRoutesHtmlUniquely、claimsExactMimeTypesOnly | require 同一性 + 冲突抛错 |
| 不回归 | tikaStillParses、markdownParserUnaffected | Block 类型断言 |
| 回归缺陷 | 行内不切碎、图片段落不打散富列表 | 产物数 + 文本全等 |
| 正文误删防护 | keepsBusinessLinkTexts、keepsAlertsAndContactInfo、keepsLinkTextInsideTableCells | 业务文本在场 |

断言均验证行为（类型/内容/顺序/级别），非"仅非空"；fixture 全部自建最小 HTML，无迎合实现修改第三方页面的情形。

**实际执行的命令与结果**：

```
./mvnw -pl bootstrap test -Dtest='HtmlDocumentParserTest' -Dsurefire.failIfNoSpecifiedTests=false
  → Tests run: 32, Failures: 0, Errors: 0（修复前复核）
./mvnw -pl bootstrap test -Dtest='HtmlDocumentParserTest' ...（修复+补测后）
  → Tests run: 34, Failures: 0, Errors: 0
./mvnw -pl bootstrap test -Dtest='ChunkingFixtureTest,IngestionSpecCodecTest,HtmlDocumentParserTest' ...
  → Tests run: 40, Failures: 0, Errors: 0, Skipped: 0
./mvnw -q -DskipTests compile → exit 0
./mvnw -q spotless:check → exit 0（无格式违规；项目自带 spotless-maven-plugin 2.22.1）
./mvnw -pl bootstrap test -Dtest='RagentCoreApplicationTests' -Dspring.profiles.active=local
  → Tests run: 1, Failures: 0（完整 Spring 上下文连 polyu 独立栈 5434/6381 启动成功；
    MCP 连接失败为已知非致命项 E6；**未修改任何生产默认配置**，local profile 仅命令行激活）
```

集成测试说明：`contextLoads` 为裸 `@SpringBootTest`，默认配置硬编码 5432/6379（本机被其他项目占用）；本轮以 `-Dspring.profiles.active=local` 走 polyu 独立端口一次通过，**未发生"本轮代码失败"**；任务提示的"固定连 6379 失败"属测试基础设施缺独立 test 配置的**既有历史问题**（02 §3.1 已记录），非本轮引入。

**提交收口复验（2026-09-04，提交前原样重跑）**：`HtmlDocumentParserTest` 34/34、三套件组合 40/40、全项目 compile、`spotless:check`、`git diff --check`、`contextLoads`（local profile，注册表自检日志同上）结果与审查轮完全一致。另补跑 `./mvnw -pl bootstrap test -Dspring.profiles.active=local` 全量套件：**276 run / 0 failures / 12 errors / 2 skipped**——12 个错误全部为既有测试基础设施依赖：`MilvusClientV2` bean 按设计未启用（InvoiceIndexDocumentTests ×3 + MilvusCollectionTests ×1）、上游演示知识库 `1997855927072321537` 种子数据本机不存在（IntentTreeServiceTests ×1 + VectorTreeIntentClassifierTests ×4）、上游 Mockito 严格模式 UnnecessaryStubbing（JdbcConversationMemorySummaryServiceTest ×3）；涉及测试类均不在本轮 diff 内，与 Parser 改动无关，未把无关修复混入本轮提交。

## 8. 05/06 指标口径校准结果（详见 06 §13 勘误）

| # | 问题 | 校准结果 |
| --- | --- | --- |
| 1 | Tika Top10 导航席 49/100（05）vs 68/100（06） | **两套判定规则**：05 = "整块全为导航"+人工复核（严格）；06 `layerA_compare.py` = 前 2000 字符命中 ≥3 个模板词即判（宽松，词表含 Borrowing/Opening Hours 等业务词，会误判正文块）。**统一口径**：严格口径 Tika 49 → Html 0；宽松口径 68 → 4（Html 4 席已逐块核验全为正文误判）。两口径结论方向一致，验收项在两口径下均过线 |
| 2 | 两次统计规则/正则/候选集 | 候选集相同（同 10 问同 Top10 席位），差异全在导航判定规则（上述）；rank 波动另有来源（#3） |
| 3 | 唯一可复现口径 | **以严格口径为准**（"整块全为导航"），宽松正则只作快速筛选需人工复核兜底；引用数字时必须标注口径 |
| 4 | "100 查询 Top10" | 表述错误，已勘误为"10 个查询、100 个结果席位"（06 §13-3） |
| 5 | 勘误方式 | 06 追加 §13（7 项），原始记录未删（05/06 正文与 /tmp 取证 JSON 原样） |
| 6 | 保留率度量 | 06 已用词集召回（避免 KV 重排破坏顺序 shingle）✓；勘误补注：06 表内 Tika 98.95–100% 是词集口径重算值，与 05 的 shingle 99.4–100% 口径不同不可混用 |
| 7 | "页面没有变化" | **本轮升级证据**：剥离 theme_token / form_build_id / data-drupal-selector / asset 版本串四类动态成分后，五页规范化 DOM 逐字符一致（规范化 SHA-256 前 16：76d3f1a9…/c16f5c27…/c6a0f302…/dca8f337…/5027f83f…），region-content 正文纯文本亦一致。原 06 "SHA 差异归因于动态时间戳位"归因不准（实为 CSRF/表单令牌），结论维持 |
| 8 | B 层性质 | 已在 06 §13-5 明确：B 层全局多库 + 改写/Rerank，是**端到端非退化验证，非 Parser 单变量 A/B**；单变量结论以 A 层为准 |
| 补 | MRR 基线漂移 | 06 的 Tika ZH MRR 0.729 是本轮重跑值（Q3ZH rank 7），05 是 0.733（rank 6）——embedding/pgvector 非确定性所致，非矛盾；增益按自对照口径 +0.171、按 05 原基线 +0.167 |

**统一后关键指标（严格口径 / 自对照）**：纯导航占比中位 83.3%→0%；跨页重复模板 43/97→0/19；Top10 导航席（100 席位）49→0；EN MRR 0.850→1.000；ZH MRR 0.729→0.900（自对照）或 0.733→0.900（05 基线）；EN/ZH HitRate@10 均 5/5→5/5；正文实质保留率 ≥99%（词集口径中位 100%）；总 chunk 97→19。

## 9. 凭据与 compose 安全检查

| 检查项 | 结果 |
| --- | --- |
| project-docs 01–06 凭据赋值模式扫描（api-key/secret/password/bearer/authorization + `[:=]`） | 仅 compose 两处命中（见下），**文档零命中** |
| 常见密钥格式（sk-/AKIA/ghp_/xox/PRIVATE KEY） | 全部待提交文件 **0 命中** |
| 本地绝对敏感路径（/Users/…） | 01–06/README/CHANGES/compose **0 命中** |
| `polyu-local-0b1.compose.yaml` 真实凭据 | **无**：PG `postgres/postgres`、Redis `123456`、MinIO `rustfsadmin/rustfsadmin`——均为上游底座公开默认值/本地占位（上游 application.yaml 本就含这些值） |
| 端口绑定 | 审查时全部 `0.0.0.0`（无 127.0.0.1 前缀）：5434/6381/9000/9001/9876/109xx。**提交收口时源配置已全部收紧为 `127.0.0.1` 前缀**（8 处，含 broker 三端口——通告地址 `brokerIP1=127.0.0.1` 与 loopback 发布配对，容器间通信走内部网络不受影响）。`docker compose config` 验证通过；运行中既有容器未为此强制重建，端口差异在下次 `docker compose up` 重建时自然生效 |
| compose 适合作开发文件提交 | ✓：带差异注释、healthcheck、幂等 up 说明；不含生产凭据 |
| .gitignore 解禁后实际新增 | project-docs/ 01–06 六个 md（无其他文件混入；取证 JSON/HTML/日志均留 /tmp 不入库） |
| 01–06 是否应入库 | ✓ 全部（阶段技术产物，扫描干净；`.claude/`、`/CLAUDE.md`、`/CHANGES.md`、`/docs/agents/` 忽略规则未动） |
| README / CHANGES / git diff --stat 一致性 | ✓：CHANGES 已实施表含 HtmlDocumentParser 行（数字与 06 一致，引用 MRR 0.729 属自对照口径，见 §8）；README 当前状态段与 06 结论一致；diff 范围 = 解析器层 + pom + .gitignore + README，与声明吻合 |

## 10. 当前 git 状态与 diff --stat

```
分支 main，HEAD a2c0c2ad（未提交未推送）

git status --short
 M .gitignore
 M README.md
 M bootstrap/pom.xml
 M bootstrap/src/main/java/com/nageoffer/ai/ragent/core/parser/ParserType.java
 M bootstrap/src/main/java/com/nageoffer/ai/ragent/core/parser/TikaDocumentParser.java
?? bootstrap/src/main/java/com/nageoffer/ai/ragent/core/parser/HtmlDocumentParser.java   (739 行)
?? bootstrap/src/test/java/com/nageoffer/ai/ragent/core/parser/HtmlDocumentParserTest.java (741 行)
?? project-docs/  (01–07 共 7 个 md)
?? resources/docker/polyu-local-0b1.compose.yaml (124 行)

git diff --stat（已跟踪文件）
 .gitignore    | 5 +++--     README.md | 2 +-
 bootstrap/pom.xml | 7 +++++++
 ParserType.java | 5 +++++    TikaDocumentParser.java | 10 ++++-----
 5 files changed, 22 insertions(+), 7 deletions(-)
git diff --check → 干净（无空白错误）
```

> 上表为审查时快照。提交收口后的增量差异：compose 端口收紧 + 注释、`.gitignore` 解禁 `/CHANGES.md`、`CHANGES.md` 入库并补一行、README 状态段更新——明细见 §2 收口清单，落地见 §13 实际提交。

## 11. 尚未解决的问题（移交后续阶段）

1. ~~**compose 端口 0.0.0.0 绑定**~~ **已解决（提交收口，随 Commit 1 入库）**：源配置 8 处端口全部收紧为 `127.0.0.1`（§9）；运行中容器仍为旧绑定，下次 `docker compose up` 重建时自然收敛，无需为此中断开发栈。
2. **NAV_STRIP contains 类选择器的通用误删面**（`[class*=cookie i]`、`[class*=main-menu]` 等）——PolyU 五页无误删实证，扩页到 AR/SAO 等新模板时抽样验证（06 §12-3 既有建议，非本轮新增）。
3. **深嵌套 DOM 无递归深度保护**——恶意构造 HTML 理论上可 StackOverflow；语料来自官方站点 + 下载侧限制，风险可忽略，记为已知限制。
4. 0B-2B 遗留仍在：MQ 半消息偶发不消费（未定位）、SiliconFlow embedding 延迟尖峰（E3，0M 纳入 P95）、B 层全局检索含旧库同页多版本（扩页前清理或建意图树，决策项）、英文回答语言不稳定（Prompt 策略决策）、富 `ol` 编号取舍。
5. `contextLoads` 无独立测试配置（硬编码默认端口）——上游测试基础设施现状，需要时以 test profile 解决，本轮未动。

## 12. 是否建议提交：**建议提交——已执行**

人工审查授权后于 2026-09-04 按 §13 拆分完成 4 个本地提交；未推送远端，未创建 tag。

## 13. 提交拆分方案（审查建议）与实际执行（2026-09-04）

按"可独立回退 + 审查焦点分离"拆 4 个提交，人工审查授权后已全部落地：

1. ✅ `bdfc3921` **`chore(docker): 新增 PolyU 本地独立中间件栈`** —— `resources/docker/polyu-local-0b1.compose.yaml`（0B-1 产物，纯新增；已按原 §11-1 建议先收紧为 127.0.0.1 再提交，即提交的即最终版本）。
2. ✅ `52331c06` **`feat(parser): 新增结构化 HTML 解析器`** —— `HtmlDocumentParser.java` + `HtmlDocumentParserTest.java` + `ParserType.java` + `TikaDocumentParser.java` + `bootstrap/pom.xml`（含 0B-2C 单列表格修复与 34 个测试；产品功能最小完整单元）。
3. ✅ `caed0966` **`docs: 纳入 Phase 0B 阶段文档与变更记录`** —— `.gitignore` + `project-docs/01–06`（含 06 §13 勘误）+ `README.md` + `CHANGES.md`（已采纳解禁建议：移除 `/CHANGES.md` 忽略规则后正常 add，未用 `git add -f`）。
4. ✅ 本提交 **`docs: 增加 Phase 0B-2C 版本检查点报告`** —— 仅 `project-docs/07`（本报告；自身提交哈希不写入文中）。

与审查建议方案的差异：无拆分调整；提交 3 依建议将 README/CHANGES 并入并执行了 CHANGES.md 解禁决策。

## 14. 是否允许进入 Phase 0B-3A（20–30 页语料构建）：**允许**

- Parser 侧无阻塞项（缺陷已修，34/34 测试过，注册链路实测自检通过）；
- 前置提醒（非阻塞，0B-2B/2C 一致建议）：先决策旧库清理或意图树限定检索范围，再扩页；扩页在 Html 解析器上入库并建立脚本级 URL→docId 幂等清单；按子站抽样验证选择器（本轮仅验证 lib.polyu.edu.hk）。

---

### 附录：本轮证据锚点

```
# 单列表格缺陷复现（修复前）：/tmp/probe 探针输出 rows=[]（两形态均丢数据）
# 修复后回归：keepsSingleColumnTableRows / keepsNestedListTextInsideItem 加入，34/34 通过
# 集成启动自检：contextLoads（local profile）日志「解析器注册表就绪 精确键=30 通配键=1 自检扩展名=21」
# 口径根因：/tmp/polyu-0b2b/layerA_compare.py is_nav_chunk()（hits>=3 宽松正则） vs 05 §4 严格口径
# rank 波动：layerA_results.json（Q3ZH rank=6） vs layerA_compare.json（tika rank=7）
# 规范化对照：/tmp/polyu-0b2a/html vs /tmp/polyu-0b2b/html，剥离 theme_token/form_build_id/
#   data-drupal-selector/?v= 后五页逐字符一致；region-content 正文纯文本逐字符一致
# 凭据扫描：grep 赋值模式 + 密钥前缀，待提交文件 0 命中（compose 命中为上游公开默认值）
# 提交收口验证：docker compose -f resources/docker/polyu-local-0b1.compose.yaml config --quiet → OK（8 端口 host_ip=127.0.0.1）
# 提交收口复跑：34/34、40/40、compile、spotless、contextLoads(local) 与审查轮一致；全量套件 276 run / 12 errors 均为既有基础设施依赖（§7）
```

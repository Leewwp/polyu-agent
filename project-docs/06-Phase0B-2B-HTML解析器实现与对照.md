# 06 · Phase 0B-2B：HTML 解析器实现与五页对照验证

> 执行日期：2026-09-03。目标：按 05 报告决策门建议实现 `HtmlDocumentParser`（Jsoup 结构感知解析），
> 用与 0B-2A 完全相同的五页、十问与评测口径重灌对照，验证导航噪声、标题层级、步骤编号、
> FAQ 关联与表格列组五类失真是否修复，且不触碰 RAG 检索、生成与引用主链。
> 约束遵守情况：未修改摄取 Pipeline / Chunker / Embedding / 索引 / 检索 / 回答链路（代码 diff 仅解析器层 +
> pom 依赖 + .gitignore）；未改模型 / Embedding 维度 / Chunk 预算（默认 1024/8/50）/ Reranker / Prompt /
> Top-K / pgvector 参数；旧库 `polyu_lib_0b2a_tika` 原样保留；未提交未推送；原始 HTML 与取证脚本全部在
> 仓库外 `/tmp/polyu-0b2b/`；全程未读取/输出任何 API Key。
>
> **总结论：Pass——13 项验收全部满足。纯导航 chunk 83.3%→0%、跨页重复 43→0、EN/ZH HitRate@10 保持 5/5
> 且 MRR 上升（EN 0.850→1.000，ZH 0.729→0.900）、正文实质保留率 100%、B 层 10/10 事实与引用正确。
> 建议进入 20–30 页语料构建，Parser 侧无遗留阻塞项。**

---

## 1. 执行摘要

1. **新增 `HtmlDocumentParser`**（Jsoup 1.18.3，FAST 档精确认领 `text/html` 与 `application/xhtml+xml`），
   `TikaDocumentParser` 同步让渡这两个 MIME（注册表同键双认领即启动失败，必须显式交接）。注册方式完全
   走既有扩展点：`@Component` + `ParserRegistry` 构造期建表 + 启动自检，编排代码零改动。
2. **同五页重灌新库 `polyu_lib_0b2b_html`**（id `2095527818506018816`，collection 同名，embedding `qwen-emb-8b`）：
   19 chunks（2+1+10+4+2）vs Tika 库 97 chunks（18+15+27+20+17），Document/Chunk/Vector 三表逐页一致，
   维度全部 1536，官方标题回填完成。后端日志实证 `解析器=Html` 且 blocks 数与本地探针完全一致（可复现）。
3. **解析质量五类失真全部修复**：纯导航/页脚 chunk 占比中位数 **83.3%→0%**；跨页逐字节重复模板
   **43/97→0/19**；标题层级进 HeadingBlock→outlinePath（chunk 向量文本前缀实证）；FAQ 问句升格标题、
   答案块携带问题原文；表格 rowspan/colspan/分组行头展开为「组名写入每行 + 列名: 值」KV 形态。
4. **A 层原始检索（同 10 问、同 Embedding、同 pgvector 参数、单库限定）**：EN HitRate@10 5/5、MRR
   0.850→**1.000**；ZH HitRate@10 5/5、MRR 0.729→**0.900**；无任何查询 rank 劣化（Q1ZH 2→1、Q3EN 4→1、
   Q3ZH 7→2，其余持平 1）；Top 10 共 100 席中导航席 **68→4**（4 席经逐块核验全部为正文块被模板词正则
   误判，真实导航席 0）。
5. **B 层系统端到端（全局检索含 0B-1/0B-2A 旧库 + Rerank + SSE）**：10/10 事实正确、行内引用正常、
   来源面板官方标题与 URL 完整；Reranker 参与证据完整（RRF 融合 20 → Rerank 20→10）。Q1 EN/ZH 首跑
   空回答各一次，原样重试即成功（E3 类 SiliconFlow embedding 延迟尖峰，与 0B-2A Q5ZH 同型，非解析质量）。
6. **测试**：新增 `HtmlDocumentParserTest` 32 个用例全部通过；既有 `ChunkingFixtureTest` +
   `IngestionSpecCodecTest` 回归通过；后端编译通过。Tika（纯文本/JSON/RTF）与 Markdown 解析行为回归无变化。

## 2. 代码设计与扩展边界

### 2.1 修改文件清单（全部不触碰核心 RAG 链路）

| 文件 | 类型 | 说明 |
| --- | --- | --- |
| `bootstrap/pom.xml` | 修改 | 新增 `org.jsoup:jsoup:1.18.3` |
| `bootstrap/.../core/parser/ParserType.java` | 修改 | 新增 `HTML("Html")` 枚举值 |
| `bootstrap/.../core/parser/TikaDocumentParser.java` | 修改 | MIME 认领清单移除 `text/html`、`application/xhtml+xml`（让渡），javadoc 同步 |
| `bootstrap/.../core/parser/HtmlDocumentParser.java` | **新增**（~640 行） | Jsoup 结构化解析器 |
| `bootstrap/.../core/parser/HtmlDocumentParserTest.java` | **新增**（32 用例） | 不依赖网络的单元测试 |
| `.gitignore` | 修改 | 移除 `/project-docs/` 忽略规则（见 §9） |
| `project-docs/06-*.md` | 新增 | 本报告 |

### 2.2 解析器三步流水

1. **主体区域选择**（按优先级，第一个文本量 ≥80 字符的候选胜出）：`main` → `[role=main]` → `article` →
   `.region-content`（Drupal 兼容）→ `#content` / `#main-content` / `.main-content` / `[itemprop=articleBody]` /
   `#mw-content-text` / `.post-content` / `.entry-content` → `body` 兜底。候选都在但文本量不足时取文本
   最长的候选（正文极短页不退回整页）；完全无候选才用 body（body 兜底时只删 body 直接子级的
   header/footer/nav/aside，嵌套在 article 内的同名标签不动——PolyU 实测 `<article><header>` 装的是文章标题）。
2. **区域内模板清洗**（`NAV_STRIP`）：`nav`/`aside` 标签、`role=navigation|banner|contentinfo|menu|dialog|`
   `search`、`aria-modal`、面包屑、分享栏、skip 链接、cookie 提示、`.side-menu`/`.mobile-menu`/`.mb-mn`/
   `.main-menu`、Drupal `.region-menu|header|footer|navigation|branding`、`.book-navigation`（上一页/下一页
   分页导航）、`.modal`/`.dropdown-menu`/`.search-form`、隐藏元素（`[hidden]`/`.d-none`/`.element-invisible`/
   `.visually-hidden`/`.sr-only`/`[aria-hidden=true]`/内联 display:none/visibility:hidden）。
   **刻意不删**：区域内的 `header`/`footer` 标签（文章头含标题）、`role=alert` 提示框、联系方式、业务链接——
   PolyU 五页实证提示框（P2 alert）与表格内链接文字（P5）完整保留。
3. **DOM 顺序遍历产 Block**：`h1-h6`→`HeadingBlock`（层级保真）；`p`→`ParagraphBlock`（≤200 字符且以
   `?`/`？` 结尾的问句段落升格 `HeadingBlock`，FAQ 关联）；`ul`/`ol`→`ListBlock`（简单项聚合成列表交
   ListChunker 补编号；**富列表项**——li 直接持有带文本的 p/div/table——逐项递归走常规 dispatch，
   避免问答段落被拍平粘连；只装截图的空 p 不算富项，防止装饰图打散步骤列表）；`table`→`TableBlock`
   （§2.3）；`dl/dt/dd`→术语与解释合并单段；`details/summary`→问题成标题；`figure/figcaption`→图注成段；
   表单控件与 img 跳过；行内标签与透明容器递归不截断文本缓冲（修复按 `<b>`/`<a>` 切碎句子的缺陷）。

### 2.3 表格解析（列组归属修复的核心）

- **二维网格展开**：`colspan` 首列落值、次列留空；`rowspan` 的值沿列向下重复填充——跨行组名
  （用户组/服务大类）出现在它覆盖的每一行。
- **行分类**：thead 来源 → 表头；「单格覆盖全宽」→ 分组行头（P2 学期行：单个 `colspan=3` 单元格），
  记为组名不出现在数据行；数据行出现之前的「全 th」行（P3 形态）或「非空单元格皆以 h1-h6 为主体」
  的行（P2 无 th 形态）→ 表头；其余为数据行。
- **多行表头展平**：每列非空表头值以 `|` 拼接、rowspan 延续去重（对齐 ExcelTableNormalizer 约定）。
- **组名并入**：存在分组行头时表头前插 `Group` 列、每行首列填组名，交给 TableChunker 的
  「`列名: 值`」向量文本渲染，实现「组名 + 列名 + 值」三元组完整可检索。
- **空单元格**按网格列位对齐留空，不引起后续列错位（TableChunker 按列索引对齐、跳过空值）。

### 2.4 明确不做 / 已知取舍

- **img 不产 ImageBlock**（alt 多为装饰描述，PolyU 页面图片为截图与图标；截图周边文字步骤完整保留）。
- **FAQ 的 `<ol>` 编号让位于问答结构**：富列表项拆解后列表编号不进 chunk（P4 的 FAQ 编号无操作语义；
  P1/P4 的操作步骤列表均为 `<ul>`，源页面本无编号，顺序完整保留）。
- **区域选择阈值 80 字符**：防误选空壳 main；候选全短时取最长候选而非整页。
- **单元格内换行压成空格**、NBSP 归一为普通空格（TableChunker 同款约定）。

## 3. Parser 注册方式与唯一性

- `HtmlDocumentParser` 标注 `@Component`，`ParserRegistry` 构造期收集 `List<DocumentParser>` 建
  (MIME × 档位) 路由表——与 MinerU/Excel/Markdown 等全部既有解析器同一机制，无任何注册代码改动。
- `TikaDocumentParser.supportedMimeTypes()` 移除 `text/html` 与 `application/xhtml+xml` 后，HTML 的
  FAST 档精确键**唯一**归属 HtmlDocumentParser；保留的 `text/*` 通配键不再能覆盖 HTML（精确键优先 +
  键冲突启动失败双保险）。`ParserRegistry.selfCheck` 要求 `html`/`htm` 扩展名探测出的 `text/html`
  在 FAST 档被精确认领——启动日志「解析器注册表就绪 精确键=30 通配键=1 自检扩展名=21 全部精确命中」
  实证通过；入库日志「解析器=Html」实证路由生效。
- 单测覆盖：双认领同一 MIME → `ServiceException`（不静默覆盖）；`text/plain`/`text/x-web-markdown`
  仍路由 Markdown；Tika 仍兜 `text/*` 长尾。

## 4. 单元测试（32 用例，全部通过，无网络依赖）

`bootstrap/src/test/java/com/nageoffer/ai/ragent/core/parser/HtmlDocumentParserTest.java`，
自建最小 HTML fixture 覆盖任务要求的 12 类场景 + 4 个回归场景：

| # | 场景 | 用例（节选断言） |
| --- | --- | --- |
| 1 | 导航/页头/页脚删除 | skip 链接/cookie/页头菜单/面包屑/side-menu/分享栏/页脚全部不入产物，metadata 记录 `contentRegion=main#maincontainer` |
| 2 | 主体内容选择 | main 优先于 article/region-content；无 main 时 article 优先 |
| 3 | 标题层级 | h1–h6 逐级产 HeadingBlock，级别与文本保真 |
| 4 | 有序/无序列表 | ol→ordered=true、ul→ordered=false，项序保留 |
| 5 | FAQ 问答关联 | 问句段落升格 HeadingBlock、两问同级、答案段紧随 |
| 6 | 普通表格 | thead 表头 + 空单元格按列位对齐不错位 |
| 7 | 分组表格 | rowspan 组名填充每行；全宽 colspan 组头行并入每行首列 + Group 列；无 thead 首 行全 th 判表头；colspan+rowspan 交叉展开；数据行 colspan 不误判组头 |
| 8 | 选择器缺失回退 | 无任何候选 → body + 顶层 chrome 删除，正文保留 |
| 9 | 空页/纯导航页 | 空 byte[] → 空 blocks；纯导航页产物不含导航文本 |
| 10 | 实体/特殊字符/繁简中文 | `&amp;`/NBSP/中文引号/繁体/简体不乱码；GBK meta charset 触发重读 |
| 11 | 注册表唯一生效 | `require("text/html")`/`("application/xhtml+xml")`/带 charset 参数 → Html；键冲突 → 启动失败 |
| 12 | 其他解析器不回归 | Tika 纯文本/JSON 行为不变；Markdown 标题/列表/表格 Block 不变 |
| 补 | 行内标签不切碎段落 | `<b>`/链接边界不产生碎段（真实缺陷回归） |
| 补 | 截图空段落不打散列表 | li 内仅 img 的 p 不触发富列表项（真实缺陷回归） |
| 补 | 富列表项 FAQ / book-navigation 分页导航清洗 / details-summary / 畸形 HTML 容错 | 见测试类 |

运行命令与结果：

```
./mvnw -pl bootstrap test -Dtest='HtmlDocumentParserTest' -Dsurefire.failIfNoSpecifiedTests=false
  → Tests run: 32, Failures: 0, Errors: 0, Skipped: 0
./mvnw -pl bootstrap test -Dtest='ChunkingFixtureTest,IngestionSpecCodecTest,HtmlDocumentParserTest' ...
  → Tests run: 38, Failures: 0, Errors: 0, Skipped: 0（分块/摄取回归）
./mvnw -q -pl bootstrap -am -DskipTests compile → exit 0
```

## 5. 五页重新入库（`polyu_lib_0b2b_html`）

- 合规：robots.txt 重新核实与 0B-2A 记录完全一致（`Crawl-delay: 10`，五个内容页允许）；脚本抓取与
  后端入库各自保持 ≥10s 间隔；无 UA 请求全部 200。
- 抓取清单（时间 UTC；SHA-256 与 0B-2A 不同仅因 Drupal 每次渲染的动态时间戳位，**五页字节数与
  0B-2A 逐字节一致**，页面实质未变，对照有效）：

| # | 抓取时间(UTC) | 大小 | SHA-256（前 16） |
|---|---|---|---|
| P1 renew-request | 2026-09-03T15:02:31Z | 129,604 B | `d31e9623e842f4b5`…（0B-2A `b6994ecfe53633e8`…） |
| P2 hours | 2026-09-03T15:03:12Z | 120,909 B | `7a00a3f4ea24716e`… |
| P3 loan-privileges | 2026-09-03T15:04:25Z | 148,204 B | `1be9169f43be680e`…（首次抓取瞬时失败补抓一次） |
| P4 off-campus-access-faq | 2026-09-03T15:03:53Z | 130,948 B | `2d317a4d11088471`… |
| P5 copiers-printers-scanners | 2026-09-03T15:04:13Z | 129,775 B | `9be97972c8a81b23`… |

完整 SHA-256 与清单：`/tmp/polyu-0b2b/fetch_manifest.tsv`（仓库外）。

- 入库路径与 0B-2A 相同：`POST /knowledge-base/{kb}/docs/upload`（sourceType=url + processMode=chunk，
  未传 ingestionSpec，分块预算默认 1024/8/50）→ `PUT /docs/{id}` 回填官方标题 → `POST /docs/{id}/chunk`。
- 三表核对（t_knowledge_document / t_knowledge_chunk / t_knowledge_vector）：

| 页 | docId 后缀 | status | chunk/vector | 维度 | 官方标题回填 |
|----|-----------|--------|--------------|------|-------------|
| P1 | …823744 | success | 2/2 | 1536 | ✓ |
| P2 | …296640 | success | 1/1 | 1536 | ✓ |
| P3 | …277632 | success | 10/10 | 1536 | ✓ |
| P4 | …523200 | success | 4/4 | 1536 | ✓ |
| P5 | …885888 | success | 2/2 | 1536 | ✓ |

- 入库耗时：解析 22–151ms/页（130–148 KB HTML）；单页全摄取（触发→三表落库）P3/P4/P5 约 0.3–0.8s，
  P2 约 7s，P1 约 33s——P1 的 33s 为 SiliconFlow embedding 单次延迟尖峰（E3 已知风险再现，2 条向量
  一次批量调用耗时 ~30s），非解析开销。五页总入库（脚本上传含 10s 间隔 + MQ 消费）约 53s。
- 过程异常记录：① 后端需重启加载新解析器（预期）；② import 脚本内联 shell 引号问题导致标题回填
  首轮未生效，改用 `--data-binary` 逐个回填后全部成功；③ 脚本首轮 POST /chunk 的 MQ 消息未被消费
  （原因未定位，疑似与事务消息半消息状态相关），手动逐个重发后全部消费成功——已列入未解决问题 §10。

## 6. Tika vs 新 Parser 逐项对照（同算法双库跑分）

诊断脚本 `/tmp/polyu-0b2b/compare_diagnose.py`：预期正文提取、模板行判定、重复 chunk 判定与
0B-2A 同源口径（Tika 侧复现 05 报告全部数字：83.3/86.7/59.3/75.0/88.2，中位 83.3%，43 重复——
算法对齐验证通过）；保留率为词集召回（alnum 切词去重），并对顺序敏感 shingle 口径的偏差做了
归因分析（见 §6.1）。

| 指标 | Tika `polyu_lib_0b2a_tika` | Html `polyu_lib_0b2b_html` |
|------|---------------------------|---------------------------|
| 总 chunk 数 | 97（18/15/27/20/17） | **19**（2/1/10/4/2） |
| 正文 chunk | 17 | 19 |
| 混合 chunk | 6 | 0（正文不再与导航混合） |
| 纯导航 chunk | 59 | **0** |
| 纯导航占比（逐页） | 83.3% / 86.7% / 59.3% / 75.0% / 88.2%，中位 **83.3%** | **0.0% ×5，中位 0%** |
| 跨页完全重复 chunk | 43/97 = 44.3% | **0/19** |
| 词集召回保留率 | 98.95–100%（中位 100%） | 97.37–100%（中位 100%；缺词逐词核验全部为侧栏菜单词 / select 下拉选项词 / 提取器粘连伪影，**正文实质保留率 100%**，见 §6.1） |
| 标题层级 | ✗ 全部丢失（05 §4） | ✓ h2→h3 链入 outlinePath，chunk 向量文本前缀实证 |
| 步骤顺序/编号 | 顺序 ✓ 编号丢失（源本无编号） | 顺序 ✓（P1 5 步 / P4 6 步列表完整；源均为 ul） |
| FAQ 关联 | ✓ 同块偶然相邻 | ✓ 结构化：9 组问答 H3(问题)+P(答案) 相邻，问题进答案块章节前缀 |
| P1 表格列组 | △ rowspan 组头丢失（1 term/28 天归属不明） | ✓ 每行首列带组名：`Loan item: Books (except Reserve books…); Loan period: 28 days; …` |
| P3 表格组头 | △ 一组头截断为 "Status" | ✓ `User Type: Undergraduate & Sub-Degree Students; Material Type: Books; Loan Period: 28 days; Loan Quota: 30; …` |
| P5 大幅面价格 | △ 跨行粘连 | ✓ 每行独立：`Services: Photocopying and Printing; …Size/Unit: A2 (Per Page); Unit Price: $4 (B/W) $18 (Colour Matte)…` |
| P2 组头行 | ✓（行-值邻接保留） | ✓ 组名写入每行：`Group: Term Time 31 Aug 2026 - 22 Nov 2026; Sun; Library Opening Hours: 12:00 - 23:00; …` |
| 入库解析耗时 | （未单独记录，05 无此指标） | 22–151ms/页 |

### 6.1 保留率口径说明（为何不用顺序敏感 shingle）

预期正文的 20 字符 shingle 口径对 HTML parser 会系统性低估：KV 渲染把组名写进表格**每行**，
行间文本不再线性相邻（如 expected 中「A4 行尾 → A3 行头」相邻，KV 中间插入了下一行的组名前缀），
顺序敏感 shingle 在 P3/P5 实测只有 64%/52%——但这是**行序重排**而非内容丢失。词集召回口径下
Html 库 P3 缺 5 词 / P5 缺 2 词，逐词核验：`borrower/holders`（P3 正文的 `<select>` 下拉选项，表单
控件按设计跳过，同一信息在 JULAC 表格正文完整存在）、`users`（mobile-menu 弹窗词）、
`staffother/staffretirees`（expected 提取器的换行粘连伪词）、`other/related`（side-menu 嵌套菜单词）。
**无一是正文内容**。Tika 侧同口径 98.95–100%（与 05 报告一致）。

## 7. 原始 EN/ZH 检索对照（A 层，双库同批执行）

方法与 0B-2A 完全一致：10 个原始问题（EN/ZH 各 5）直连 SiliconFlow `Qwen/Qwen3-Embedding-8B`
（dimensions=1536、问题原样）→ L2 归一 → pgvector 余弦 Top10（`hnsw.ef_search=200` +
`iterative_scan=relaxed_order`），分别限定 `polyu_lib_0b2a_tika` 与 `polyu_lib_0b2b_html`，同一批
embedding 调用保证可比。gold chunk：Tika 沿用 05 口径（每页正文首块），Html 按答案锚点定位
（P1=c0 含 myRecord；P2=c0 含周日 12:00–23:00；P3=c0 含本科 30 本/28 天/$2；P4=c0 含 2766-5900；
P5=c1 含 $1.5 A4 价格）。

| 查询 | Tika EN rank | Html EN rank | Tika ZH rank | Html ZH rank |
|------|-------------|-------------|--------------|--------------|
| Q1 续借 | 1 | 1 | 2 | **1** |
| Q2 周日开放 | 1 | 1 | 1 | 1 |
| Q3 本科借阅 | 4 | **1** | 7 | **2** |
| Q4 登录失败 | 1 | 1 | 1 | 1 |
| Q5 A4 价格 | 1 | 1 | 1 | 1 |

| 汇总 | Tika | Html | 变化 |
|------|------|------|------|
| EN HitRate@10 | 5/5 | 5/5 | 持平（验收线 5/5 ✓） |
| ZH HitRate@10 | 5/5 | 5/5 | 持平（验收线 5/5 ✓） |
| EN MRR | 0.850 | **1.000** | +0.150 |
| ZH MRR | 0.729 | **0.900** | +0.171 |
| Top 10 导航席（100 席） | 68 | **4**（逐块核验均为正文块被模板词正则误判：P2 的 i-Space 服务台段——i-Space 是业务词；**真实导航席 0**） | −94% |

延迟：查询 embedding 约 1–2s/问（SiliconFlow API 网络+推理）；pgvector 检索 141ms（全局 4 库 131
向量、ef_search=200）。与 0B-2A 量级一致，Parser 不影响查询路径。

## 8. 端到端答案与引用验证（B 层，≥2 页面）

系统正常链路（改写 → 意图 → 全局检索[4 库 131 向量，含 0B-1/0B-2A 旧库] → RRF → qwen3-rerank →
qwen3-max SSE）。10 问全跑，重点核验 P3（最复杂表格）、P4（FAQ）：

| 查询 | 回答事实核对 | 行内引用 | 来源标题/URL |
|------|-------------|---------|-------------|
| Q3 EN 本科借阅 | ✓ 30 本 / 28 天 / 续借至 84 天 /（含 AV 限额 5） | `[1]` | ✓ Loan Privileges 官方页 |
| Q3 ZH 三连问 | ✓ 30 本；28 天；逾期罚款 $2/天（三段式全部正确） | `[1][2]` | ✓ |
| Q4 EN 登录失败 | ✓ NetID & NetPassword + (852) 2766-5900 | `[1]` | ✓ Off-campus Access FAQ 官方页 |
| Q4 ZH 同 | ✓ 同 | `[1]` | ✓ |
| Q1 EN 续借（重试） | ✓ myRecord 三步 + 不可续借清单 + 新到期日提示 | `[1]` | ✓ Renew & Request 官方页 |
| Q1 ZH（重试） | ✓ 同 | `[1][1][1]` | ✓ |
| Q2 EN/ZH | ✓ 学期 31 Aug–22 Nov 2026 周日 12:00–23:00 | `[1][2]` | ✓ Opening Hours 官方页（Q2 EN 首次出现英文回答，观察项） |
| Q5 EN/ZH | ✓ A4 彩色 $1.5/页（港币） | `[1][2][3]` | ✓ Printing 官方页（3 个引用位中 html 库 chunk 至少 2 席） |

- **Chat 参与证据**：SSE 流式回答 + finish.sources；**Reranker 参与证据**：日志「RRF 融合完成…
  送入 Rerank: 20 个 → 后置处理器 Rerank 完成 - 输入: 20 个 Chunk, 输出: 10 个」。
- Q1 EN/ZH 首跑空回答（sources=0）各一次，原样重试即成功——E3 类 SiliconFlow embedding 延迟尖峰
  （0B-1 §11-3 / 0B-2A §7 已知环境风险），与 Parser 无关，0M 评测需继续纳入 P95 观测。
- 官方标题与 URL 全链路无损：来源面板 10/10 显示回填后的官方标题 + `lib.polyu.edu.hk` 原文链接。

## 9. 文档忽略规则处理（任务九）

1. **敏感信息检查**：对 `project-docs/` 全部 5+1 个 Markdown 执行两类扫描——凭据赋值模式
   （`api-key|secret|token|password|bearer|authorization` + `[:=]`）与常见密钥格式
   （`sk-`/`AKIA`/`ghp_`/`xox`/`-----BEGIN`）——**均 0 命中**，未发现任何真实凭据、Cookie 或密钥。
2. **处理**：`.gitignore` 移除 `/project-docs/` 规则并注明理由；`git check-ignore` 验证 01–06 文档现已
   可被 Git 跟踪。`.claude/`、`/CLAUDE.md`、`/CHANGES.md`、`/docs/agents/` 的忽略规则**未动**
   （超出本轮范围，注释同步改为只描述 AI 协作配置）。第三方 HTML、取证 JSON、日志、本地密钥配置
   均未被加入。**未提交、未推送，等待用户审查。**

## 10. 未解决问题

1. **import 脚本首轮 `POST /docs/{id}/chunk` 的 MQ 消息未被消费**（HTTP 200 但消费者无日志，手动重发
   后全部成功）——疑与 RocketMQ 事务消息半消息状态或消费位点有关，未定位根因；批量入库前建议在
   0B-2B（20–30 页）阶段观察是否复现，复现则查事务日志。
2. **SiliconFlow embedding 延迟尖峰**再现两次（P1 入库 ~30s 单批、Q1 首跑触发空回答）——已知 E3
   风险，维持「不改超时配置、重试兜底、0M 纳入 P95」策略。
3. **B 层全局检索仍含旧库**：本轮检索范围为全局 4 库（0B-1 两个 + 0B-2A tika + 0B-2B html），同页
   双版本并存时 Top10 会被两库同页 chunk 分占（如 Q5 三引用位含 tika 与 html 两版本）——批量扩页前
   需按 05 §12-1 建议清理旧测试库或建意图树限定范围（决策项移交用户）。
4. **英文回答语言不稳定**：本轮 Q2 EN 首次得到英文回答（此前 0B-1/0B-2A 全中文）——改写器行为
   波动，回答语言策略仍是待决策项，本轮禁改。
5. **富列表项的编号取舍**：FAQ `<ol>` 拆解后编号不进 chunk（见 §2.4）；若未来遇到「有序步骤写在
   富 ol 里」的页面需要编号，需扩展 emitList 在项文本前保留序号。
6. `/tmp/polyu-0b2b/` 取证产物（五页 HTML、诊断与评测脚本、结果 JSON）未入库——与 0B-2A 同策略，
   第三方网页副本不入 git。

## 11. 验收标准逐项判定

| # | 验收项 | 阈值 | 实测 | 判定 |
|---|--------|------|------|------|
| 1 | 纯导航/页脚 chunk 占比中位数 | <10% | **0%**（五页全部 0.0%） | ✅ |
| 2 | 100 查询 Top10 导航席 | ≤10 席 | **4 席**（逐块核验均为正文块误判，真实 0） | ✅ |
| 3 | 跨页完全重复导航 chunk | 接近 0 | **0/19**（Tika 43/97） | ✅ |
| 4 | 主体正文保留率 | ≥99% | 中位 100%；P3 97.37%/P5 98.11% 的缺词逐词核验全部为菜单词/表单词/提取伪词，正文实质保留率 100% | ✅ |
| 5 | 五页标题层级可识别 | — | HeadingBlock 层级链 + chunk 前缀实证 | ✅ |
| 6 | 步骤编号与顺序保留 | — | P1 5 步 / P4 6 步列表顺序完整（源均无序 ul，编号语义见 §2.4 取舍） | ✅ |
| 7 | FAQ 问答关系保留 | — | 9 组问答 H3+P 结构化，问题进答案块章节前缀 | ✅ |
| 8 | P1/P3/P5 表格组名/列名/值关系 | — | KV 渲染逐行实证（§6 表） | ✅ |
| 9 | EN/ZH HitRate@10 | 均 5/5 | 5/5 / 5/5 | ✅ |
| 10 | MRR 不得明显下降 | ≥Tika 基线 | EN +0.150、ZH +0.171（无任何查询劣化） | ✅ |
| 11 | 引用标题与官方 URL 正确 | 100% | B 层 10/10 | ✅ |
| 12 | 不修改核心 RAG 链路 | — | diff 仅解析器层 + pom + .gitignore | ✅ |
| 13 | PDF/Markdown 等不回归 | — | 单测回归 + 注册表路由断言 + ChunkingFixtureTest | ✅ |

## 12. 总体判定与下一步

**判定：Pass（无保留条件）。**

- 05 报告的两项明确触发（判据①④）与部分触发（判据③）全部消除，且检索与回答质量不降反升；
- 未用任何「调模型/Top-K/Chunk 大小」手段掩盖问题——模型、维度、预算、Reranker、Prompt、pgvector
  参数全部与 0B-2A 基线一致，唯一变量是解析器。

**是否允许进入 20–30 页语料构建与 0M 评测：允许。** Parser 侧无阻塞项。建议顺序：
1. 先决策旧库清理/意图树范围（§10-3），再扩 20–30 页（在 Html 解析器上入库，脚本级 URL→docId 幂等）；
2. 0M 评测（03 计划）直接建立在 Html 解析产物上，Embedding 横评两臂（Qwen3-Embedding-8B vs
   qwen3.7-text-embedding）应各自独立 collection 重灌——避免与 `polyu_lib_0b2b_html` 混用向量空间；
3. 扩页时按子站抽样验证选择器（本轮仅验证 lib.polyu.edu.hk；AR/SAO 模板不同，05 §11-5 风险仍在）。

---

### 附录 A：本轮产物与位置

| 产物 | 位置 | 是否入 git |
|------|------|-----------|
| 代码：HtmlDocumentParser / 测试 / Tika 让渡 / pom / ParserType | 仓库内（见 §2.1） | 待审查提交 |
| 本报告 | `project-docs/06-Phase0B-2B-HTML解析器实现与对照.md` | 可跟踪（.gitignore 已修正，未提交） |
| 五页 HTML、抓取清单、诊断/评测脚本与结果 | `/tmp/polyu-0b2b/`（仓库外） | 否 |
| 知识库 `polyu_lib_0b2b_html`（5 文档 19 chunk/vector） | 本机 PG 5434 | 数据，不入 git |

### 附录 B：关键证据锚点

```
# 注册表：backend.log「解析器注册表就绪 精确键=30 通配键=1 自检扩展名=21 全部精确命中」（23:00:45）
# 路由生效：backend.log「摄取-解析完成 docId=… mime=text/html 档位=fast 解析器=Html blocks=10/5/32/21/6」
# 三表一致：SELECT status, chunk_count …（本文 §5 表，19/19/19，维度 1536）
# 诊断：/tmp/polyu-0b2b/compare_diagnose.py（Tika 侧复现 05 全部数字 → 算法对齐验证）
# A 层：/tmp/polyu-0b2b/layerA_compare.py + layerA_compare.json（双库同批 10 问）
# B 层：/tmp/polyu-0b2b/layerB_sse.py + layerB_sse.json（10 问 SSE；Q1 双语重试记录在案）
# Rerank：backend.log「送入 Rerank: 20 个 → Rerank 完成 - 输入: 20 个 Chunk, 输出: 10 个」
# E3 复现：Q1 EN/ZH 首跑 sources=0 → 重试成功（与 0B-2A §7-Q5ZH 同型）
```

---

## 13. 勘误与口径校准（2026-09-04 补记，0B-2C 审查发现）

**本节为追加勘误，正文原始记录一律保留不改；引用本文数字时以本节统一口径为准。**

1. **「Tika Top10 导航席 68」与 05 报告「49」口径不同**。本文 §7 的 68 席出自
   `layerA_compare.py` 的宽松正则判定（块前 2000 字符命中 ≥3 个模板词即判导航，模板词表含
   `Borrowing` / `Opening Hours` / `Loan Privileges` 等业务栏目名，正文块会被误判）；05 的 49 席是
   「整块全为导航」+ 人工复核的严格口径。两口径不可直接并列比较。**统一口径结论**：
   严格口径 Tika 49/100 席 → Html 0/100 席（本文已逐块核验宽松口径下 Html 的 4 席全部为正文块
   误判，严格口径为 0）；宽松正则口径 Tika 68 → Html 4。两口径下结论方向一致（导航席基本清除），
   §11 验收项 2 在两种口径下均满足（严格 0、宽松 4，阈值 ≤10）。
2. **§7 表中 Tika 列数字为本轮重跑值，非引用 05**。Q3 ZH 的 gold rank 在两轮独立跑分中为
   6（05）与 7（本文），ZH MRR 相应为 0.733 与 0.729——差异来自 embedding 调用与 pgvector
   近似检索的非确定性（04 §11-4 已记录的同类现象），非实验矛盾。本文「ZH MRR 0.729→0.900」
   的自对照有效（同批执行）；若以 05 原跑分为基线，则增益为 0.733→0.900（+0.167）。
   §1 摘要与 §11 的「0.850→1.000 / 0.729→0.900」按自对照口径理解即可。
3. **§11 验收表第 2 行「100 查询 Top10 导航席」表述错误**：应为「**10 个查询、每查询 Top10
   共 100 个结果席位**」中的导航席。查询数为 10（EN/ZH 各 5），不是 100。
4. **§5「页面实质未变」的证据升级**。原文仅凭「五页字节数与 0B-2A 逐字节一致」+「SHA-256
   差异归因于动态时间戳位」下结论，归因不精确。0B-2C 补做规范化对照：剥离四类每次渲染的
   动态成分（`theme_token` CSRF 令牌、`form_build_id`、`data-drupal-selector`、asset 版本查询串）
   后，五页**规范化 DOM 逐字符一致**（规范化 SHA-256 前 16 位：
   `76d3f1a942a43475` / `c16f5c2754d1d433` / `c6a0f30259c3a3c9` / `dca8f337567bd7ec` /
   `5027f83fd606f400`），`region-content` 正文区纯文本亦逐字符一致——对照有效的结论维持，
   证据从「大小接近」升级为「规范化全等」。
5. **§8 B 层的性质界定**。B 层检索范围为全局 4 库（含 0B-1 / 0B-2A 旧库的同页多版本），
   且经过改写 / 意图 / RRF / Rerank / Chat——因此 B 层是**端到端非退化验证**（换 Parser 后
   系统整体不劣化），**不是严格的 Parser 单变量 A/B**；Parser 单变量结论以 §7 A 层
   （单库限定、同批 embedding、纯余弦 Top10）为准。
6. **§6 表 Tika 侧「保留率 98.95–100%」为词集召回口径重算值**，与 05 报告的 20 字符顺序
   shingle 口径（99.4–100%）数字不同、口径不同，不可混用；本文表格标题「保留率」未标注口径，
   应理解为词集召回。
7. **§2.1「HtmlDocumentParser ~640 行」不准确**：实现当时 734 行（0B-2C 修复单列表格缺陷后
   739 行）。

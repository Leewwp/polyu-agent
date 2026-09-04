# 05 · Phase 0B-2A：五页语料抽样与 HTML 解析质量门

> 执行日期：2026-09-03。目标：判断 Apache Tika 对 PolyU Library 不同类型页面的解析质量是否足以支撑后续 20–50 页语料和 0M 模型评测。
> 方法：五类结构互异页面入库（原始 Tika 基线，零代码改动）→ 逐 chunk 解析诊断 → 两层检索评测
> （A 层原始问题直连 embedding+pgvector 绕过改写器；B 层 ragent 正常链路含改写/意图/Rerank）→ 决策门逐项判断。
> 约束遵守：未实现新 Parser、未改分块参数/模型/ES/翻译/OpenCC/术语映射、未改 Prompt 与核心 RAG 链路、未提交未推送；
> 原始 HTML 与取证脚本全部在仓库外 `/tmp/polyu-0b2a/`，第三方网页副本不入库；全程未读取/输出任何 API Key。
>
> **总结论：Conditional Pass——检索与回答质量达标，但解析质量门 5 项判据中 2 项明确触发、1 项部分触发，建议按决策门规则进入 `HtmlDocumentParser` 开发（本轮仅设计建议）。**

---

## 1. 执行摘要

1. **五页入库全部成功**（知识库 `polyu_lib_0b2a_tika`，URL 上传路径 + 默认 chunk 模式），Document/Chunk/Vector 三表
   完全一致：18+15+27+20+17 = **97/97/97**，维度全部 1536。期间遇到一次用户轮换 API Key 后旧后端进程持旧 Key 的
   401 失败（E1），重启后端重新加载 `application-local.yaml` 后全部成功。
2. **正文不丢，但被导航噪声淹没**：五页主体正文 20 字符 shingle 保留率 **99.4%–100%**（正文文字全部保留，
   含 HTML 注释中的过期内容被正确跳过）；但**纯导航/页脚 chunk 占比的五页中位数为 83.3%**（59.3%–88.2%），
   远超 30% 决策门阈值。全库 97 个 chunk 中 **43 个（44.3%）是跨页逐字节重复的导航模板**。
3. **A 层原始检索（绕过改写器）：10/10 全部命中**。5 对语义相同的英文/简体中文原始问题，黄金证据全部进 Top 10
   （EN rank 1/1/4/1/1，ZH rank 2/1/6/1/1），EN HitRate@10 = 5/5、MRR = 0.850；ZH HitRate@10 = 5/5、MRR = 0.733。
   **真正的跨语言召回损失（HitRate@10 口径）= 0**；损失体现在排序与分数（MRR −0.117，余弦 −0.06～−0.13），
   无塌方——这是对 `04` §13 勘误的正式回应，此前"跨语言损失 5–7%"的口径不成立。
4. **B 层系统链路（含改写）：事实与引用 10/10 正确**。改写器把 4/5 个英文问题改写成中文（Q4 例外，保持英文），
   简中问题保持中文并可能拆分为多个子问题；Rerank 后黄金证据 rank 1–4；全部回答以中文输出（英文提问亦然，
   与 0B-1 E2 一致）；引用全部指向正确官方页面。一次 Q5 简中问答因 SiliconFlow embedding 延迟尖峰触发 15s
   通道级超时而空回答（0B-1 E3 已知问题，非解析质量），重试即成功。
5. **导航噪声实测**：A 层 Top 10 共 100 席中 49 席为纯导航 chunk，其中 18 席为跨页重复模板；**6/10 个查询的
   Top 10 含重复导航模板 chunk**（阈值 ≥3/10）。B 层（Rerank 后、范围含 0B-1 遗留旧库共 115 向量）Top 10
   纯导航占 55%。在 5 页规模下 Reranker 仍能把黄金证据顶到 #1–#4，回答未受影响；按每页 13–16 个导航 chunk
   的线性累积推算，20–50 页与 0C 的 500–1500 页规模下该噪声将继续放大。
6. **决策门**：判据①（导航比例中位数 >30%，实测 83.3%）❌ 触发；判据②（正文保留率 <90%，实测 ≥99.4%）✅ 未触发；
   判据③（步骤顺序或表格字段关系损坏）⚠️ 部分触发（步骤顺序保留、编号丢失；表格行内字段关系保留、跨行列组
   归属部分损坏）；判据④（重复导航 chunk ≥3/10 查询进 Top 10，实测 6/10）❌ 触发；判据⑤（英文原始问题找不到
   证据，实测 5/5 命中）✅ 未触发。**按"任一触发即建议开发 Parser"的规则，建议进入 `HtmlDocumentParser` 开发。**

## 2. 五页选择清单

合规前置：`lib.polyu.edu.hk/robots.txt`（2026-09-03 核实）`Crawl-delay: 10`，仅禁 admin/search/user 等路径，
五个内容页全部允许；无需登录；抓取与后端入库均保持 ≥10s 间隔（入库时后端再抓一次，同样间隔）；
无 UA 的 curl 请求全部 200（R3 持续不成立）。五页类型互异（步骤 / 时间表 / 规则表格 / FAQ / 价格表格），
其中第 5 页与 0B-1 单页验证同页，用于跨阶段可比性（该页 HTML 129,775 字节与 0B-1 记录逐字节一致，页面未变）。

| # | URL | 官方标题 | 类型 | 抓取时间(UTC) | 大小 | HTML SHA-256 |
|---|-----|---------|------|--------------|------|--------------|
| P1 | `https://www.lib.polyu.edu.hk/services/borrowing/renew-request` | Renew & Request \| Pao Yue-kong Library, The Hong Kong Polytechnic University | 操作步骤页 | 2026-09-03T14:02:02Z | 129,604 B | `b6994ecfe53633e8e2da5a87fe074b44df2d38a4111eed63baf66cb42d491cc5` |
| P2 | `https://www.lib.polyu.edu.hk/about-us/hours` | Opening Hours \| Pao Yue-kong Library, The Hong Kong Polytechnic University | 开放时间页 | 2026-09-03T14:02:21Z | 120,909 B | `6098bf9ec5279fe7b716f6305931733b82a78bffd6e0679ebb5653257024e737` |
| P3 | `https://www.lib.polyu.edu.hk/services/borrowing/loan-privileges` | Loan Privileges \| Pao Yue-kong Library, The Hong Kong Polytechnic University | 服务规则页 | 2026-09-03T14:02:41Z | 148,204 B | `d14785f19590db00f69545d4d744d306708c711cd5d1a1d908329a79ff0aef7a` |
| P4 | `https://www.lib.polyu.edu.hk/services/it-support/off-campus-access-faq` | Off-campus Access to Library e-Resources FAQ \| Pao Yue-kong Library, The Hong Kong Polytechnic University | FAQ 页 | 2026-09-03T14:03:12Z | 130,948 B | `d1a09b853de45a458d82af838ee924e2feb990539a6970e89edb8c93fddd4253` |
| P5 | `https://www.lib.polyu.edu.hk/facilities/copiers-printers-scanners` | Printing, Scanning & Copying \| Pao Yue-kong Library, The Hong Kong Polytechnic University | 价格表格页 | 2026-09-03T14:03:32Z | 129,775 B | `710fecaa2904a71cc1c6d072991f8fc38b8d97c8790f369ec7f1e1d7ca426878` |

预期主体内容与标准答案事实（gold chunk 均为该页正文进入的第一个 chunk，见 §4）：

| # | 预期主体内容 | 标准答案事实（可核验） | 英文问题 | 简体中文问题 |
|---|-------------|----------------------|---------|-------------|
| P1 | 续借规则要点 + 借期/续借上限表 + 预约借出书 5 步操作 | 在线续借：图书馆主页点击 "myRecord" 并登录图书馆账户 | How can I renew my borrowed books online at the PolyU Library? | 香港理工大学图书馆的书怎么在线续借？ |
| P2 | 学期时段开放时间表（日 × 开馆时间 × 柜台时间）+ 24 小时学习中心说明 | 学期期间（31 Aug–22 Nov 2026）周日开馆 12:00–23:00，P/F 柜台 12:00–19:00 | What are the opening hours of the PolyU Library on Sundays during term time? | 学期期间香港理工大学图书馆周日几点开门？ |
| P3 | 按用户组（本科/研究生/教职员/退休/校友…）的借期、限额、预约、续借、罚款大表 | 本科生：图书限额 30 本、借期 28 天、逾期罚款 $2/天 | How many books can an undergraduate student borrow from the PolyU Library, for how long, and what is the overdue fine? | 香港理工大学本科生最多能借多少本书？借多久？逾期罚款多少？ |
| P4 | 8 组问答（EZproxy 设置 / 登录失败 / Cookie / JS / Hostname / 证书警告…） | 登录失败：检查 NetID & NetPassword，或致电 ITS 服务台 (852) 2766-5900 | I cannot log in to the Library's off-campus e-resources access with my username and password. What should I do? | 校外访问香港理工大学图书馆电子资源时账号密码登录不了，该怎么办？ |
| P5 | 服务与价格表（复印/打印/大幅面/扫描/装订/过塑/3D 打印）+ 支付方式说明 | A4 彩色打印 $1.5/页；全部机器收 Octopus 与 AliPay HK | How much does it cost to print an A4 colour page in the PolyU Library? | 在香港理工大学图书馆打印一张A4彩色纸多少钱？ |

## 3. 入库一致性

- 知识库：`polyu_lib_0b2a_tika`（id `2095513353941155840`，collection 同名，embedding `qwen-emb-8b`）。
- 路径：`POST /knowledge-base/{kb}/docs/upload`（`sourceType=url` + `processMode=chunk`，未传 ingestionSpec，
  分块预算默认 1024 字符/8 重叠）→ `PUT /docs/{id}` 回填官方标题 → `POST /docs/{id}/chunk` 触发。
  五页上传间隔 ≥10s，无重复 URL（脚本按清单执行并记录 docId）。
- 三表核对（t_knowledge_document / t_knowledge_chunk / t_knowledge_vector）：

| 页 | docId 后缀 | status | chunk_count | chunk 行 | vector 行 | 维度 |
|----|-----------|--------|-------------|---------|-----------|------|
| P1 renew-request | …182784 | success | 18 | 18 | 18 | 1536 |
| P2 hours | …8737024 | success | 15 | 15 | 15 | 1536 |
| P3 loan-privileges | …5332608 | success | 27 | 27 | 27 | 1536 |
| P4 off-campus-access-faq | …4202880 | success | 20 | 20 | 20 | 1536 |
| P5 copiers-printers-scanners | …5461248 | success | 17 | 17 | 17 | 1536 |

P5 的 17 chunks 与 0B-1 记录一致（同页同结果，可复现）。

## 4. 逐页解析质量表

分类口径（程序化 + 人工复核）：**nav** = 内容全为菜单/页脚/面包屑（导航词汇 = 出现在 ≥2 页的重复行 + 分享栏等样板）；
**main** = 以本页独有正文为主；**mixed** = 正文与导航混合（本页正文区的头一个 chunk 几乎都是这种——左侧菜单尾部
+ 页面 h2 + 首段正文）；重复模板 = 规范化全文与 ≥2 页的 chunk 逐字节相同。保留率 = 预期正文（HTML 去注释、
去脚本样式后 h2 起至分页导航止）的 20 字符 shingle 在本页全部 chunk 拼接文本中的覆盖率。

| 页 | 总 chunk | 正文 | 混合 | 纯导航 | 纯导航占比 | 重复模板 chunk | 正文保留率 | 标题层级 | 步骤/列表顺序 | 表格字段关系 | 答案自包含 |
|----|---------|------|------|--------|-----------|---------------|-----------|---------|-------------|-------------|-----------|
| P1 renew-request | 18 | 2 | 1 | 15 | 83.3% | 13 | 100% | ✗（标记丢失，标题文字保留） | ✓ 顺序保留、**编号丢失** | △ 行内保留、**列组归属丢失**（1 term/28 天分属哪类用户无法从 chunk 判读） | ✓ chunk7（混合块尾部完整含 myRecord 步骤 + 全部续借规则） |
| P2 hours | 15 | 1 | 1 | 13 | 86.7% | 5 | 100% | ✗ | — | ✓ 表头两行 + 每行「日 → 开馆 → 柜台」顺序完整（复习考试周表在源码注释中，Tika 正确跳过） | ✓ chunk6（含学期日期 + 三行时间 + 24 小时中心说明） |
| P3 loan-privileges | 27 | 10 | 1 | 16 | 59.3% | 7 | 99.4% | ✗ | — | △ 各用户组数据行完整且多数组名保留（Postgraduate Students / Academic Staff），但**一组组头被截断为 "Status"**（chunk12），列组语义靠相邻 chunk 推断 | ✓ chunk7（本科组头 + Books 行 28 天/30/$2/day 同块） |
| P4 off-campus-access-faq | 20 | 4 | 1 | 15 | 75.0% | 6 | 100% | ✗ | ✓ 问答对顺序保留 | —（无表格） | ✓ chunk7（登录失败答案 + 2766-5900 完整）、chunk8–11（Cookie/证书/EZproxy 各答案自包含） |
| P5 copiers-printers-scanners | 17 | 1 | 1 | 15 | 88.2% | 12 | 100% | ✗ | — | △ A4/A3 行完整；**A0–A2 大幅面价格跨行粘连**（"$30 (Colour Semi-Gloss Photo A1 (Per Page)"） | ✓ chunk7（引言 + 支付方式 + A4/A3 价格，与 0B-1 一致） |

要点：

1. **正文文字零丢失**（99.4%–100%）：Tika 拍平不丢内容，只丢结构。标题层级（h1–h6 标记）全部丢失，
   页面标题仅以普通文本行存活（幸运地落在正文 chunk 内）。
2. **gold chunk 都是"混合块"**：五页的标准答案有 4 页位于 chunk7（正文区第一个 chunk，前半是左侧菜单尾部
   导航词），P2 在 chunk6。embedding 信号被导航词稀释，但 5 页规模下仍以断层/领先优势命中。
3. **步骤页**：5 个可见步骤顺序完整（编号丢失）；源码注释里的废弃步骤被正确排除。
4. **表格页两极分化**：窄表（hours）行-值邻接关系完整；宽表（loan-privileges 多用户组、P1 借期列组、
   P5 大幅面价格）列组归属/组头部分损坏。

## 5. 导航噪声和重复内容统计

| 指标 | 数值 |
|------|------|
| 五页纯导航 chunk 占比 | 83.3% / 86.7% / 59.3% / 75.0% / 88.2%，**中位数 83.3%** |
| 全库跨页逐字节重复 chunk | 43/97 = **44.3%**（每页 5–13 个） |
| 五页头部 5 个 chunk（share 栏/Quick Access/Find 主菜单等） | **全部 5 页逐字节相同**（25 个 chunk 完全重复） |
| 跨页重复导航行（去重后） | 258 行模板词汇 |
| 每页模板字符占全页字符比例 | 60.6% / 60.8% / 41.4% / 55.1% / 64.0% |
| A 层 Top 10（100 席）纯导航 | 49 席（49%），其中重复模板 18 席 |
| B 层 Top 10（Rerank 后 107 席）纯导航 | 59 席（55%），另有 13 席来自 0B-1 遗留旧库（全局检索范围） |

重复模板的直接后果：同一导航文本在 5 页各有一份**完全相同的向量**，任何与之相近的查询会一次召回 5 个
并列副本；Q4/Q5 的原始 Top 10 中 8–9 席是导航位即此效应。

## 6. A 层：原始英文/简中检索结果（绕过改写器）

方法：取证脚本（仓库外 `/tmp/polyu-0b2a/raw_retrieval.py`）直连 SiliconFlow
`Qwen/Qwen3-Embedding-8B`（`dimensions=1536`，问题原样、无 instruction 前缀）→ L2 归一 → 复刻后端
pgvector 查询（`hnsw.ef_search=200` + `iterative_scan=relaxed_order`，余弦，collection 限
`polyu_lib_0b2a_tika`）取 Top 10。Key 从 `application-local.yaml` 进程内读取，未打印未落盘。

| 查询 | EN rank / 余弦 | ZH rank / 余弦 | Top 10 导航席（EN/ZH） | 备注 |
|------|---------------|----------------|----------------------|------|
| Q1 续借 | **1** / 0.8359 | **2** / 0.7017 | 3 / 5 | ZH 时 loan 页页脚 chunk (#1) 压过 gold |
| Q2 周日开放 | **1** / 0.7315 | **1** / 0.6070 | 5 / 4 | |
| Q3 本科借阅 | **4** / 0.7654 | **6** / 0.6419 | 0 / 1 | gold 是混合块；正文大表的其余行包揽前排（正确行为） |
| Q4 登录失败 | **1** / 0.7005 | **1** / 0.6268 | **9 / 9** | gold 断层第一，其余 9 席全导航 |
| Q5 A4 价格 | **1** / 0.7926 | **1** / 0.6999 | **8 / 5** | 同上 |

汇总：**EN HitRate@10 = 5/5，MRR = 0.850；ZH HitRate@10 = 5/5，MRR = 0.733**。
真正的跨语言召回损失：**HitRate@10 损失 = 0**；MRR 下降 0.117（rank 退化仅 Q1 1→2、Q3 4→6）；
余弦分数下降 0.059–0.135。**简体中文问题检索英文文档在该 5 对样本上无塌方**——这是 0B-2A 对
`04` §13 勘误的正式补测（n=5 对，非单样本）。

## 7. B 层：系统改写后检索结果（ragent 正常链路）

方法：`GET /rag/eval?question=…`（内部依次执行 改写 `rewriteWithSplit` → 意图解析 → 多通道检索 → RRF →
Rerank，返回改写后子问题与 Rerank 后最终列表）；SSE 问答 `GET /rag/v3/chat` 记录最终回答与来源。
意图树当前为空 → 按设计降级为全局检索，范围含 0B-1 遗留两库共 115 向量（`polyu_0b1_md_test` 1 +
`polyu_lib` 17 + 本轮 97），本轮未配置意图树（遵守"不改意图树"约束）。

| 查询 | 原始语言 | 改写后问题（语言） | gold Rerank 后 rank | 回答语言 | 回答事实 | 引用 |
|------|---------|-------------------|---------------------|---------|---------|------|
| Q1 EN | EN | 「PolyU Library 借阅书籍在线续借方法」（**中**） | 1 | 中文 | ✓ myRecord 三步 + 不可续借清单 | ✓ P1 官方页 |
| Q1 ZH | ZH | 「香港理工大学图书馆的书在线续借方法」（中） | 1 | 中文 | ✓ | ✓ |
| Q2 EN | EN | 「PolyU Library 开放时间 周日 学期期间」（**中**） | 1 | 中文 | ✓ 周日 12:00–23:00 | ✓ P2 官方页 |
| Q2 ZH | ZH | 「香港理工大学图书馆周日开门时间」（中） | 1 | 中文 | ✓ | ✓ |
| Q3 EN | EN | 「PolyU Library 本科生借书数量、借阅期限和逾期罚款」（**中**） | 2 | 中文 | ✓ 30 本 / 28 天 / $2 每天 | ✓ P3 官方页 |
| Q3 ZH | ZH | 拆 3 子题：「…借多少本书 / …借多久 / …逾期罚款多少」（中） | 4 | 中文 | ✓ 三段式全部正确 | ✓ |
| Q4 EN | EN | 「Library off-campus e-resources access login failure…」（**英**，例外未译） | 1 | 中文 | ✓ NetID + (852) 2766-5900 | ✓ P4 官方页 |
| Q4 ZH | ZH | 「校外访问…登录不了怎么办」（中） | 1 | 中文 | ✓ | ✓ |
| Q5 EN | EN | 「PolyU Library 打印 A4 彩色页面的费用」（**中**） | 1 | 中文 | ✓ $1.5/页 | ✓ P5 官方页（另同引 0B-1 旧副本） |
| Q5 ZH | ZH | 「香港理工大学图书馆打印A4彩色纸的价格」（中） | 1（eval） | 中文 | ✓（首次 SSE 因通道超时空回答，重试 ✓） | ✓ |

B 层补充观察：

1. **改写器并非恒定翻译**：Q4 英文改写后仍是英文（关键词重组），其余 4 个英文问题均改写为中文。
   "英文提问→中文回答"由改写与 Prompt 共同导致（回答语言 10/10 全中文），本轮禁改，仅记录。
2. **全局检索的重复页面问题**：0B-1 的 `polyu_lib` 库仍存有 P5 同页副本（17 向量），本轮 5 个 Q5/Q1 类查询中
   13 席来自旧库，P5 的引用面板会同时列出两份相同官方页。批量入库前需要脚本级 URL 去重 + 清理旧测试库，
   或配置意图树限定 collection 范围。
3. **一次 E3 类超时**：Q5 ZH 首次 SSE 问答时向量通道 15s 超时被放弃 → 空回答（SiliconFlow embedding 延迟
   尖峰 >15s，`04` §11-3 已知风险再现），原样重试成功。0M 评测需纳入 P95 观测。

## 8. 两层评测口径差异

| 维度 | A 层（原始检索） | B 层（系统端到端） |
|------|----------------|-------------------|
| 查询文本 | 用户原始问题（EN/ZH 原文） | 改写器输出（多为中文；可能拆分为多个子问题） |
| 检索范围 | 单库 `polyu_lib_0b2a_tika`（97 向量） | 全局 3 库 115 向量（无意图树降级全库） |
| 后处理 | 无（纯余弦 Top 10） | RRF + qwen3-rerank（20 候选 → 10） |
| 结论 | 跨语言损失 HitRate=0、MRR −0.117 | Rerank 后 gold rank 1–4；回答 10/10 正确 |
| 不可混用点 | A 层数字 ≠ 系统指标；A 层 ZH 与 04 §4 旧数字也不可混（旧"EN"实为改写后中文） | B 层好坏同时受改写质量、全局范围、Reranker 影响，不能反推 Embedding 好坏 |

`04` §4 的 0.7847 vs 0.7448/0.7279 对照即属"改写后中文 vs 原始中文"，本轮两层拆分后该口径错误已彻底消除。

## 9. HTML Parser 决策门逐项判断

| # | 判据 | 阈值 | 实测 | 判定 |
|---|------|------|------|------|
| ① | 五页纯导航/页脚 chunk 比例中位数 | >30% 触发 | **83.3%**（59.3–88.2%） | **触发** |
| ② | 关键正文保留率 | <90% 触发 | 99.4%–100% | 未触发 |
| ③ | 高价值页面步骤顺序或表格字段关系损坏 | 任一损坏触发 | 步骤顺序保留（编号丢失）；hours 行-值关系保留；P1 借期列组归属丢失、P3 一组头截断为 "Status"、P5 大幅面价格跨行粘连 | **部分触发**（表格列组关系三处受损） |
| ④ | 重复导航 chunk 进入 Top 10 的查询数 | ≥3/10 触发 | **6/10**（Top 100 席中重复模板 18 席） | **触发** |
| ⑤ | 英文原始问题因解析质量无法进 Top 10 | 任一失败触发 | 5/5 命中（rank 1/1/4/1/1） | 未触发 |

## 10. 总体判定：**Conditional Pass**

- **通过面**：五页入库链路与三表一致；正文零丢失；A 层 10/10 命中且跨语言无 HitRate 损失；B 层 10/10
  事实与引用正确。Tika 基线**足以支撑 0M 模型评测**（20 题小集、Embedding/Reranker 横评不依赖更好的 HTML 结构）。
- **条件面**：决策门判据①④明确触发、③部分触发。继续沿用 Tika 的前提是：语料规模停留在 0M 的 20–50 页
  评测集，且评测指标以"gold rank"而非"Top 10 信噪比"为主。
- **不通过面（对更大规模）**：44% chunk 为跨页重复模板、每页 13–16 个导航 chunk 线性累积、Top 10 一半席位
  是导航——该形态在 0C（500–1500 页）不可持续。

## 11. 是否建议立即实现 `HtmlDocumentParser`

**建议开发，排期在 0M 评测完成之后、20–50 页种子集构建之时（或并行设计），不在本轮实施。**
理由：判据①④已触发，且触发原因（导航/重复模板）会随页数线性放大；但当前 5–50 页规模下检索与回答质量
未受损，先跑 0M 可拿到"Tika 基线 vs 未来 Parser 基线"的对照数字，使 Parser 的收益可量化。

设计建议（仅设计，供后续实施参考）：

1. **实现形态**：`HtmlDocumentParser implements DocumentParser` + `@Component`，认领 `text/html`，
   经 `ParserRegistry` 自动接管（`02` §4.2 已验证注册表机制），不动编排代码——维持"新增适配器即可"边界。
2. **解析器**：引入 Jsoup；`document.body()` 上做**结构化清洗**而非全文拍平：
   - 删除 `nav`、`header`、`footer`、`.region-menu`、分享栏、Skip 链接、面包屑、`<!--…-->`；
   - 选 Drupal 主区（本站为 `div.region-content` 内 h2 之后、分页导航之前的段落）；
   - 保留 h1–h6 到段落的**标题链**（写入 chunk 前缀，弥补层级丢失）；
   - `<ol>/<ul>` 步骤保留编号（`1. ` 前缀）；`<table>` 按行序列化为 `列名: 值` 对（或 Markdown 表格），
     多列组表格重复行组时把**组名并入每行**（解决 P1/P3 的列组归属与组头截断问题）。
3. **跨页模板抑制**：导航清洗后，五页重复模板 chunk（44%）天然消失；不需要内容去重组件。
4. **验收**：同五页重灌后复测本报告 §5/§6 指标——导航占比中位数降到 <10%、A 层 Top 10 导航席 <10、
   gold rank 不劣化、正文保留率保持 ≥99%。
5. **风险**：PolyU 各子站模板不一（Library 为 Drupal），选择器需按子站适配；先只做 lib.polyu.edu.hk，
   AR/SAO 站点在 0C 前抽样验证。

## 12. 下一阶段建议

1. **先清场**：删除或归档 0B-1 遗留测试库（`polyu_lib`、`polyu_0b1_md_test`），或建意图树限定检索范围，
   避免全局检索重复页与旧数据混入（本轮已实测 13/107 席位被旧库占据、P5 被双份引用）。
2. **进入 0M 模型评测门**（`03` 计划）：20 题成对集 + Embedding 横评（Qwen3-Embedding-8B vs
   qwen3.7-text-embedding）→ 锁定 → Reranker 三臂。0M 语料可复用本报告五页 + 扩至 20–50 页
   （扩页在 Tika 基线上进行即可，Parser 落地后重灌）。
3. **把本轮两个已知环境风险带入 0M 口径**：SiliconFlow embedding P95 延迟（本轮再次触发 15s 通道超时）、
   qwen3-rerank 尾部非确定性（`04` §11-4）。
4. **决策项移交用户**：a) `HtmlDocumentParser` 开发排期（建议 0M 后、0C 前）；b) 回答语言策略
   （英文提问是否要求英文回答——本轮 10/10 中文回答，改 Prompt 属后续轮）；c) 旧测试库清理方式。
5. 0B-2B（20–50 页批量种子页）在上述决策后执行；届时同步建立脚本级 URL→docId 幂等清单（系统无 URL 去重）。

---

### 附录 A：本轮产物与位置

| 产物 | 位置 | 是否入库 |
|------|------|---------|
| 本报告 | `project-docs/05-Phase0B-2A五页解析质量门.md` | 否（`/project-docs/` 整目录被 .gitignore 忽略） |
| 04 勘误 | `project-docs/04-Phase0B-1运行闭环验证.md` §13 | 否（同上） |
| 五页原始 HTML、抓取清单、chunk 分类表、A/B 层取证脚本与结果 JSON | `/tmp/polyu-0b2a/`（仓库外） | 否 |
| 知识库 `polyu_lib_0b2a_tika`（5 文档 97 chunk/vector） | 本机 PG 5434 | 数据，不入 git |

### 附录 B：关键证据锚点

```
# robots：Crawl-delay: 10，内容页允许（/tmp/polyu-0b2a/robots.txt）
# 入库：POST /knowledge-base/2095513353941155840/docs/upload (sourceType=url, processMode=chunk) ×5 → PUT docName → POST chunk
# 一致性：SELECT status, chunk_count … 三表 97/97/97（本文 §3）
# A 层：raw_retrieval.py = SiliconFlow embed(dim=1536) → L2 → pgvector cosine Top10（ef_search=200, iterative_scan）
# B 层：GET /rag/eval（subIntents=改写后子题；retrievedChunkIds=Rerank 后序）；GET /rag/v3/chat SSE（finish.sources）
# E3 复现：backend.log「检索通道 VectorSearch 超过通道级超时 15000ms，放弃其结果」→ 空回答 → 重试成功
# 改写证据：/rag/eval subIntents——Q1EN→「PolyU Library 借阅书籍在线续借方法」(中)，Q4EN→英文关键词重组(未译)
```

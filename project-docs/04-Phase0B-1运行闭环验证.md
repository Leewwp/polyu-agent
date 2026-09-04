# 04 · Phase 0B-1：单页面端到端运行闭环验证

> 执行日期：2026-09-03。验证目标：证明 ragent 底座能把已接通的模型 API 串成完整业务链路
> `PolyU 网页 → 下载 → 解析 → 分块 → Embedding → pgvector 检索 → Reranker → Chat 回答 → 引用来源展示`。
> 约束遵守情况：未导入 20–50 页、未写爬虫/批量采集器、未实现 `HtmlDocumentParser`、未动意图树/术语/Prompt、
> 未做模型 A/B、未修改核心 RAG 链路（后端/前端产品代码 **0 改动**）、未提交未推送。
>
> **结论：Pass**（Markdown 闭环、PolyU 单页入库、英文与简体中文问答全部通过；简体中文跨语言为「初步通过」，n=1 待 0M 量化）。

---

## 1. 环境与模型基线

### 1.1 中间件（全部独立新起，未触碰 `omnicraft-*` 容器）

| 组件 | 镜像 / 版本 | 宿主端口 | 容器名 | 状态 |
| --- | --- | --- | --- | --- |
| PostgreSQL + pgvector | `pgvector/pgvector:pg16` | **5434**（5432 被 omnicraft-postgres 占用） | polyu-pg | healthy |
| Redis | `redis:7-alpine`（requirepass 123456） | **6381**（6379 被占） | polyu-redis | healthy |
| 对象存储 | `minio/minio:RELEASE.2024-06-13`（凭据沿用 rustfsadmin/rustfsadmin，应用零改动） | 9000 / 9001 | polyu-minio | healthy |
| RocketMQ NameServer | `apache/rocketmq:5.2.0` | 9876 | polyu-rmqnamesrv | healthy |
| RocketMQ Broker | 同上（remoting 协议） | 10909/10911/10912 | polyu-rmqbroker | 已注册到 NameServer |

- 编排文件：`resources/docker/polyu-local-0b1.compose.yaml`（**本轮新增**，未跟踪）。与上游 compose 差异：
  PG/Redis 换端口；不暴露 broker proxy 8080–8082（本机 8080/8081 被占，且 ragent 客户端为
  rocketmq-spring-boot-starter 2.3.5 remoting 协议，只需 9876+109xx）；MinIO 替代 rustfs（S3 兼容）。
- 数据库初始化：`schema_pg.sql` + `init_data_pg.sql` 顺序执行，exit 0；pgvector 扩展 `vector` 已装；
  种子含 admin 用户（admin/admin，`init_data_pg.sql:130`）、6 条 Prompt、0 知识库、0 意图树。
- 端口覆盖方式：`bootstrap/src/main/resources/application-local.yaml`（gitignore）追加
  `spring.datasource.url`（5434）与 `spring.data.redis.port`（6381），密钥区未动。
- 后端：`./mvnw -DskipTests install` 后 `./mvnw -pl bootstrap spring-boot:run -Dspring-boot.run.profiles=local`，
  8.1s 启动成功（MCP Server 连接失败为已知非致命项，`mcp-server` 未启动）。
- 前端：Vite 5.4.21 dev server @ http://localhost:5173（代理 → 9090）。

### 1.2 模型基线（本轮不更换、不横评；不记录密钥值）

| 角色 | 供应商 / 端点 / 地域 | Model ID | 关键参数 | 实际参与证据 |
| --- | --- | --- | --- | --- |
| 主 Chat | 阿里云百炼 DashScope（dashscope.aliyuncs.com，cn） | `qwen3-max`（standard tier 首选，`application.yaml:247`） | OpenAI 兼容 chat，SSE | trace `LLM_PROVIDER=bailian-stream-chat`（2952ms） |
| 辅助 Chat（改写/标题/意图/推荐） | 同上 | 路由 fast/standard（qwen-flash/qwen-plus 系） | — | trace `bailian-chat` 多次（448–886ms） |
| Embedding | SiliconFlow（api.siliconflow.cn，cn） | `Qwen/Qwen3-Embedding-8B`（候选 id `qwen-emb-8b`） | `dimensions=1536` 显式传入；L2 归一化；余弦 | 文档 18 条向量全部 1536 维；查询侧直连复算一致 |
| Reranker | 百炼 DashScope 原生 rerank 端点 | `qwen3-rerank` | top_n、return_documents | 归因日志「送入 Rerank: 18 个→输出 10」+ 直连 API 复算一致 |

## 2. Markdown 闭环结果 —— ✅ 通过

1. 建库 `polyu-0b1-md-test`（collection `polyu_0b1_md_test`，embedding qwen-emb-8b）。
2. 上传本地 1172 字节中文 Markdown（内容为环境自述，含可核验事实「四个中间件组件」）。
   ⚠️ 首次上传 400「处理模式不能为空」→ 补 `processMode=chunk`（知识库路径默认分块模式，符合约束 9）。
3. 三表一致性（doc 2095503466473455616）：`t_knowledge_document` status=success / `t_knowledge_chunk` 1 行 /
   `t_knowledge_vector` 1 行（1536 维，metadata.doc_id 一致）。
4. SSE 问答「测试文档里中间件栈包含哪四个组件？」：回答正确列出 PostgreSQL 16(5434)/Redis 7(6381)/MinIO(9000,9001)/
   RocketMQ 5.2.0(9876)，带行内引用 `[1](#cite-1)`；`finish.sources` 含 docId/docName/excerpt；
   `t_message.sources` JSONB 落库非空。
5. 来源面板（file 类型无 url）走本地预览路径，符合底座设计。

## 3. PolyU 单页面入库结果 —— ✅ 通过

- 选页：**Printing, Scanning & Copying**（包玉刚图书馆）
  `https://www.lib.polyu.edu.hk/facilities/copiers-printers-scanners`（英文、无需登录、含明确价格与支付操作信息）。
- 合规前置：`lib.polyu.edu.hk/robots.txt` 仅禁 admin/search/user 等路径，内容页允许抓取（Crawl-delay 10，
  本轮单页单次抓取）；**无 UA 的 curl 请求 200**（R3 风险对该站不成立，未做任何 UA 修复）。
- 入库：`sourceType=url` + `processMode=chunk`（默认 chunk 模式，未用 Ingestion Pipeline，符合约束 9）。
  下载 129,775 字节（与手工 curl 逐字节一致）；`source_location` 原样保存。
- docName 回填：默认取远端文件名 `copiers-printers-scanners` → `PUT /knowledge-base/docs/{id}` 回填为官方
  页面标题 `Printing, Scanning & Copying | Pao Yue-kong Library, The Hong Kong Polytechnic University`。
- 三表一致性（doc 2095504090762051584）：17 chunks / 17 vectors（全部 1536 维）/ status=success。

```
doc_id               | doc_name                          | status | chunk_count | chunk_rows | vector_rows | dims
2095503466473455616  | polyu-agent-0b1-测试文档.md        | success| 1           | 1          | 1           | 1536
2095504090762051584  | Printing, Scanning & Copying | ... | success| 17          | 17         | 17          | 1536
```

## 4. 英文与简体中文召回对比（同语义问题对）

问题对（同一事实：A4 彩色打印价格 + 支付方式）：
- **EN**: `How much does it cost to print an A4 colour page in the PolyU Library, and what payment methods are accepted at the printers?`
- **ZH（简体）**: `在香港理工大学图书馆打印一张 A4 彩色页需要多少钱？打印机支持哪些支付方式？`

黄金证据：**chunk 7**（vector_id `2095504129643249671`，尾部含 `$0.2 (B/W) $1.5 (Colour)`、
`Octopus and AliPay HK are accepted in all copiers and printers. WeChat Pay ... designated`）。

取证方法：原始向量召回 = 复刻后端逻辑（同模型 embed → L2 归一化 → `hnsw.ef_search=200` + `iterative_scan` →
余弦 SQL Top10，范围含两个 collection 共 18 条向量）；Rerank 排序 = 系统内 `/rag/eval`（走完整 RRF+Rerank 链）
+ 直连百炼 `qwen3-rerank` API 全量 18 条复算双重验证。

### 4.1 原始向量召回（pgvector 余弦，Top10 摘要）

| 查询 | 黄金证据排名 | 余弦 | 第 2 名（余弦） | 评注 |
| --- | --- | --- | --- | --- |
| EN（经改写，见 §4.3） | **#1** | **0.7847** | chunk16 页脚 0.5866 | 断层式领先（+0.20） |
| ZH 子问题①「打印A4彩色页需要多少钱」 | **#1** | **0.7448** | chunk16 页脚 ~0.55 | 同样断层领先 |
| ZH 子问题②「打印机支持哪些支付方式」 | **#1** | **0.7279** | 导航块 | 同上 |

**跨语言召回损失（n=1 样例）：EN 0.7847 → ZH 0.7448/0.7279，相对下降约 5%–7%，无塌方。**

### 4.2 Rerank 后排序（系统 `/rag/eval` + 直连 API 双重证据）

| 查询 | 黄金证据排名（系统 eval） | 直连 qwen3-rerank 复算 | 结论 |
| --- | --- | --- | --- |
| EN | **#1** | **#1**（相关性 0.8973，第 2 名 0.6147） | 一致 |
| ZH | **#1** | 子问题① #1（0.7803）/ ② #1（0.8542） | 一致 |

## 5. Rerank 前后排序变化（证明 Reranker 真实参与并生效）

以 EN 改写后查询为例，18 条候选全量对比（chunk_index 标注；7=黄金证据，8=价格表后半，其余多為导航）：

| 阶段 | 排序（Top10） |
| --- | --- |
| 原始向量 | 7, 16, 9, 2, 6, **8**, 12, 4, 5, 14 |
| 直连 qwen3-rerank 全量 | **7**, 5, 4, 11, 16, 14, 2, 10, 3, 9, 0, 13, 15, 12, 6, 1, **8**(第17), md |
| 系统 eval（Rerank 后 Top10） | **7**, 16, 4, 5, 10, 11, 9, 14, 0, 3 |

- 系统内证据链完整：`RRF 融合完成 - 通道数:1, k:20, 融合后 18 个, 截断上限 40, 送入 Rerank: 18 个` →
  `后置处理器 Rerank 完成 - 输入: 18 个 Chunk, 输出: 10 个 Chunk, 变化: -8`（耗时约 362ms）。
- 观察两点：① 2–10 名的 Rerank 排序与原始向量序显著不同（如 chunk5 从 #9 → #2），且两次独立调用尾部顺序存在
  小幅非确定性（Top1 恒定）——0M 评测需多次采样；② chunk8（价格表 A0/A1 大幅面部分）从原始 #6 被 Rerank 压到
  #17，Reranker 正确聚焦于自包含的 chunk7。

## 6. 最终答案与引用核对 —— ✅ 通过

| 项 | EN 问题 | ZH 问题 |
| --- | --- | --- |
| 回答内容 | A4 彩色 **$1.5/页**；Octopus、AliPay HK（全部机器）；WeChat Pay（指定机器） | 同左（并标注「港币」） |
| 与官方页面核对 | ✅ 逐项一致（页面价格表 + 首段支付说明） | ✅ 同 |
| 回答语言 | **中文**（英文提问，见 §9-E2 观察） | 中文 |
| 行内引用 | `[1](#cite-1)` ×2 | `[1](#cite-1)` ×2 |
| 来源面板 docName | 官方标题（回填后） | 同 |
| 来源面板 URL | `https://www.lib.polyu.edu.hk/facilities/copiers-printers-scanners`（官方原文，非对象存储地址） | 同 |
| 引用是否真正支撑答案 | ✅ chunk7 尾部含全部答案要素（价格+支付原文） | ✅ |

**前端浏览器实测**（登录 admin → 历史会话 → 来源面板）：
- 行内角标可点击，悬浮标题即官方页面标题；
- 「参考来源 (1)」面板显示官方标题 + `lib.polyu.edu.hk` + 摘录；
- **点击来源项新开标签页 → `https://www.lib.polyu.edu.hk/facilities/copiers-printers-scanners`，
  标题 `Printing, Scanning & Copying | Pao Yue-kong Library, ...` 与回填 docName 完全一致**（验收项 12 ✅）；
- 管理后台 `/admin/dashboard` 正常加载（导航、Dashboard 概览接口 200）。

## 7. HTML 解析观察（Tika 拍平，不实现新 Parser）

1. **噪声占比**：17 chunks 中 16 个为导航/菜单/页脚/侧栏噪声，正文仅分布于 **chunk7 尾部（约后 1/3，含引言+
   支付方式+A4/A3/A2 价格）** 与 **chunk8 头部（A1/A0/装订/过塑/3D 打印）**。页面 `<title>` 与所有标题层级均丢失。
2. **表格结构被打散**：价格表行内列对应关系丢失，出现 `$30 (Colour Semi-Gloss Photo A1 (Per Page)` 这类跨行粘连；
   所幸 A4/A3 行恰好完整落在 chunk7，未影响本轮问答。
3. **对检索的影响**：chunk7 头部约 2/3 为导航词（Video Conference Camera / Wi-Fi Services / Spaces…），
   稀释了 embedding 信号（余弦绝对值不高，0.73–0.78），但在**单页语料**下仍以 0.20+ 的断层优势排 #1，
   Rerank 相关性 0.85–0.90 同样 #1——**本轮检索质量达标，无需新 Parser**。
4. **风险预告**：多页语料下（每页 ~16 个导航 chunk）导航噪声会成倍累积，且各页面导航文本高度相似（LibCafe、
   i-Space、e-Forms 反复出现），可能造成跨文档导航 chunk 挤占 Top10——留待 0B 批量导入（20–50 页）后实测，
   不达标再启动 `HtmlDocumentParser`（02 §6.4 条件项）。

## 8. 延迟与 Token 数据

| 指标 | Markdown 问 | EN 问 | ZH 问 |
| --- | --- | --- | --- |
| 首 Token 延迟（TTFT，SSE 端到端） | 3,425 ms | 2,674 ms | 2,483 ms |
| 总延迟 | 5,834 ms | 4,121 ms | 4,107 ms |
| `/rag/eval` 检索链（改写+意图+检索+Rerank） | — | 1,438 ms | 1,162 ms |
| 回答长度 | 265 字符 | 188 字符 | 187 字符 |

分段时间（trace，ZH 问）：改写 886ms → 意图 3ms → 检索引擎 1,476ms（含向量通道 180ms/18 条 + Rerank 362ms）→
LLM 流式 2,952ms。

- **Token 用量：系统不可获取**——SSE `finish` 事件无 usage 字段、`t_message` 无 token 列、日志无 usage 输出。
  输出规模仅能以字符数记录（见上表）。已列入未解决问题（§11-1）。
- **SiliconFlow Embedding 延迟抖动**（同机直连 3 次实测）：2.37s / 0.25s / 9.10s——远大于检索通道内 embed
  占用，是 §9-E3 超时根因，0M 评测需纳入 P95 观测。

## 9. 遇到的错误和处理

| # | 现象 | 处理 | 性质 |
| --- | --- | --- | --- |
| E1 | `mvnw -pl bootstrap spring-boot:run` 解析不到 framework/infra-ai 构件 | 先 `./mvnw -DskipTests install` 再运行 | 工具链使用方式 |
| E2 | 英文问题得到**中文回答**；日志显示改写步骤把英文问题翻译成中文（「PolyU Library 打印 A4 彩色页面的费用…」）后进入检索与生成 | 如实记录，不修（Prompt/改写调整属本轮禁止事项） | 行为观察，留待 0B 后续/0C 决策（如需英文回答英文，属 Prompt 槽或改写策略调整） |
| E3 | 首次 SSE 问答：向量通道超 15s 被放弃（`检索通道 VectorSearch 超过通道级超时 15000ms`），实际 21.2s 完成但结果弃用 → 空回答 | 原样重试即成功（SiliconFlow 延迟尖峰，见 §8）；**未调整 timeout 配置**（不改配置基线），记录为环境风险 | 外部 API 延迟抖动 |
| E4 | 首次上传 400「处理模式不能为空」 | 请求补 `processMode=chunk` | API 必填字段 |
| E5 | `GET /api/ragent/dashboard/overview` 404（NoResourceFound） | 正确路径为 `/admin/dashboard/overview`（`@RequestMapping("/admin/dashboard")`），复测 200 | 我方调用路径错误 |
| E6 | MCP Server 连接失败 `log.error` 后跳过工具注册 | 预期行为（mcp-server 未启动，02 §3.3 已知） | 非致命 |
| E7 | 过程失误：一次检查配置的 grep 模式过宽，将 `application-local.yaml` 中两个 api-key 值打印到了终端输出 | 未写入任何文件/报告/仓库；密钥文件本身在 .gitignore 内；后续检查一律掩码 | 流程失误，声明留痕 |

## 10. 修改文件（本轮全部不提交）

| 文件 | 类型 | 说明 |
| --- | --- | --- |
| `resources/docker/polyu-local-0b1.compose.yaml` | **新增**（未跟踪） | 独立中间件栈编排（PG5434/Redis6381/MinIO9000/RocketMQ9876） |
| `bootstrap/src/main/resources/application-local.yaml` | 追加（gitignore 内，不入库） | 仅追加 datasource/redis 端口覆盖，密钥区未动 |
| `project-docs/04-Phase0B-1运行闭环验证.md` | **新增**（未跟踪） | 本报告 |
| `/tmp/polyu-0b1/*` | 仓库外 | 测试 Markdown、SSE 客户端、取证脚本与全部原始响应 JSON |

后端 Java、前端源码、Prompt/意图树/术语、`application.yaml`：**零改动**。`git status` 仅上述三个未跟踪文件。

## 11. 结论与未解决问题

### 结论：**Pass**

验收标准逐条核对：

| 验收项 | 结果 |
| --- | --- |
| 全部必要中间件及前后端成功运行 | ✅（4 中间件 healthy + 后端 8.1s 启动 + 前端 5173） |
| Markdown 问答闭环通过 | ✅（含引用与来源面板） |
| 一个 PolyU Library 页面成功入库 | ✅（url 路径 + 默认 chunk 模式） |
| Document、Chunk、Vector 数据一致 | ✅（1/1/1 与 17/17/17，维度全 1536） |
| 英文问题召回正确证据 | ✅（原始向量 #1 + Rerank #1，断层领先） |
| Reranker 和 Chat 实际参与调用 | ✅（归因日志 18→10、trace bailian-stream-chat、直连 API 复算一致） |
| 最终答案包含正确、可点击的官方来源 | ✅（浏览器实测新开官方页面，标题一致） |
| 未修改核心调用链 | ✅（产品代码零改动） |
| 简体中文问题 | ✅ 成功 → **跨语言初步通过**（n=1，非 Conditional Pass） |

### 未解决问题（移交后续阶段）

1. **Token 用量无系统级记录**（finish 事件/t_message/日志均无 usage）——建议 0M 前补最小 usage 采集或由评测脚本直连 API 记账。
2. **英文提问→中文回答**（改写翻译所致）——是否要求「以提问语言回答」需 Prompt 槽/改写策略决策，本轮禁改。
3. **SiliconFlow Embedding 延迟抖动大**（0.25s–9.1s，曾致 15s 通道超时）——0M Embedding 评测必须记录 P95；若持续超时再议 `channels.timeout-ms`（配置级，非核心链路）。
4. **qwen3-rerank 尾部排序非确定性**（两次调用 2–10 名顺序略异，Top1 稳定）——0M 评测需多次采样取均值。
5. **Tika 导航噪声随语料规模放大**（§7-4 风险预告）——0B 批量 20–50 页后复测，不达标再启动 `HtmlDocumentParser`。
6. trace `extra_data` 为空（实际 Model ID 未落库）——可观测性改进项。
7. RocketMQ proxy/dashboard（8080–8082）本机端口被占未暴露——remoting 客户端不受影响；如需 dashboard 再改映射。

## 12. 是否建议进入小规模语料与模型评测阶段

**建议进入，且有两条前置观察必须带上：**

1. **进入 0B 剩余项**（20–50 条 Library 种子页 + 意图树/术语/示例种子 + 更新重入库验证 + 最小品牌化）：
   单页闭环已证明零代码改动可承载 PolyU 语料；批量导入重点验证 §7-4 的导航噪声累积与脚本级 URL 幂等
   （系统无去重，02 §4.9）。
2. **随后进入 0M 模型评测门**：跨语言检索在 n=1 上初步通过（余弦损失 5–7%，Rerank 后双 #1），为
   Qwen3-Embedding-8B 对照组开了好头，但必须按 `03` §六的 20 题成对集量化 HitRate@10/MRR/跨语言召回损失；
   同时把 §11-3 的 Embedding P95 延迟与 §11-4 的 Rerank 非确定性纳入评测口径。
3. **暂不需要**：`HtmlDocumentParser`（单页检索达标）、UA 修复（站点放行无 UA 请求）、ES/翻译/OpenCC
   （跨语言单页通过，无掩盖性修补）。

---

### 附录 A：验证时点仍在运行的进程（供用户复用或清理）

- 容器：`polyu-agent-stack`（polyu-pg / polyu-redis / polyu-minio / polyu-rmqnamesrv / polyu-rmqbroker），
  `docker compose -f resources/docker/polyu-local-0b1.compose.yaml down -v` 可整体清除（含数据卷）。
- 后端：`mvnw spring-boot:run`（local profile）@ 9090；前端：`npm run dev` @ 5173。
- 测试数据：知识库 `polyu-0b1-md-test` / `polyu-lib`，文档 2 篇，会话 6 条——均在本机独立库，不影响任何现有项目。

### 附录 B：关键命令与证据锚点

```
docker compose -f resources/docker/polyu-local-0b1.compose.yaml up -d
docker exec -i polyu-pg psql -U postgres -d ragent -v ON_ERROR_STOP=1 -q < resources/database/schema_pg.sql   # exit 0
docker exec -i polyu-pg psql -U postgres -d ragent -v ON_ERROR_STOP=1 -q < resources/database/init_data_pg.sql # exit 0
curl -X POST .../auth/login -d '{"username":"admin","password":"admin"}'            # code 0
curl -F "sourceType=url" -F "sourceLocation=https://www.lib.polyu.edu.hk/facilities/copiers-printers-scanners" -F "processMode=chunk" .../docs/upload
curl -X PUT .../knowledge-base/docs/2095504090762051584 -d '{"docName":"Printing, Scanning & Copying | Pao Yue-kong Library, The Hong Kong Polytechnic University"}'
# Rerank 参与证据（backend.log）：
#   RRF 融合完成 - 通道数: 1, k: 20, 融合后: 18 个, 截断上限: 40, 送入 Rerank: 18 个
#   后置处理器 Rerank 完成 - 输入: 18 个 Chunk, 输出: 10 个 Chunk, 变化: -8
# Chat 参与证据（t_rag_trace_node）：LLM_PROVIDER=bailian-stream-chat（2952ms）/ bailian-chat（改写 886ms）
# 原始向量复算：evidence.py（SiliconFlow embed → L2 归一 → pgvector cosine Top10，含 ef_search=200/iterative_scan）
```

---

## 13. 评测口径勘误（2026-09-03 补记，0B-2A 前修正）

**§4 的"跨语言召回损失"结论不成立，予以更正；原始实验数据全部保留，不删除历史记录。**

1. **事实**：英文用户问题在进入检索前被问题改写器改写成中文（§9-E2 已观察到该行为）。§4.1 中标注
   "EN（经改写）" 的 0.7847 查询，实际进入 Embedding 的文本是**改写后的中文问题**，并非原始英文问题。
2. **推论**：`0.7847`（"EN"）与 `0.7448 / 0.7279`（ZH）之间的差异，是**两种中文问法之间的差异**，
   不能解释为英文与中文的跨语言召回损失。§4.1 的"相对下降约 5%–7%"与 §12-2 的"余弦损失 5–7%"
   均基于错误口径，作废。
3. **当前能确认的**：仅"简体中文查询英文文档"在该单样本（n=1）上检索成功（原始向量 #1 + Rerank #1）。
   **原始英文问题直接检索英文文档的效果从未被测量过。**
4. **正确口径**：真正的跨语言损失必须用**未经改写的原始英文问题和简体中文问题**（同语义成对）分别直接
   调用 Embedding 与向量检索后对比计算。该测量由 Phase 0B-2A 的"原始检索评测"层承担（见
   `05-Phase0B-2A五页解析质量门.md`），系统端到端（含改写）结果单独记录，两层不混用。

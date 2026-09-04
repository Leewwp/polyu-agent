# 02 · Phase 0A：ragent 1.1.0 底座适配性审计（PolyU 校园场景）

> 审计日期：2026-09-03。审计方式：本地代码静态审计 + 只读 git 检查 + 构建/环境最小验证（未启动完整服务）。
> 结论预告：**Go**（进入 Phase 0B），附带 5 项进入前需用户决策的问题（见 §9）与 2 项 Phase 0B 出口实测项（见 §8）。

---

## 1. 执行摘要

对锁定的 ragent 1.1.0 基线（`f64de341`，当前 HEAD `b2ceaa60` 的直接父提交）做了 12 项能力的逐项代码审计，核心结论：

1. **结构性 Go 条件全部满足**：校园内容可经「知识库上传 API（sourceType=url）+ DB 种子数据（意图树/术语/示例问题/Prompt）」接入，检索、生成、引用主链路零改动；默认配置即「pgvector 单通道 + RRF + Rerank、ES/图谱/Web 搜索全关」；官方 URL 在 `t_knowledge_document.source_location` 原样保存并透传到回答来源面板（`SourcesAssembler.resolveUrl`）。
2. **主要缺口全部在预期范围内**：无 sitemap 爬虫/链接发现（本来就是规划中的净新增项）、HTML 解析用 Tika 拍平丢标题层级（可选新增 `HtmlDocumentParser`）、`extras` 元数据「存储就绪但 chunk 模式不写入、检索过滤未实现」（规划文档对此有夸大，需修正；Phase 0B 不依赖它）。
3. **后端编译通过（`./mvnw -DskipTests compile` exit 0）、前端构建通过（`npm run build` 8.27s）**；完整启动未执行——本机 5432/6379 端口被其他项目容器占用、RocketMQ/MinIO 未起、无模型 API key，属环境阻塞而非代码问题（§3.3）。
4. **一处规划文档与本地版本的不一致**：`CHANGES.md` 引用的 `resources/initializer/enterprise-knowledge-base/IntentTreeInitMain.java` 在本仓库不存在（基线 tag 亦无）。意图树初始化应改为管理端操作或自写种子 SQL。

能力分类统计（12 项）：原生可用 5 项、配置可用 4 项、新增适配器即可 1 项、需要外围模块 2 项（其中 1 项 Phase 0B 明确不做）、需要修改核心链路 **0 项**。

---

## 2. 仓库和基线验证结果

| 检查项 | 命令 | 结果 |
| --- | --- | --- |
| 当前分支 | `git branch --show-current` | `main` |
| HEAD | `git rev-parse HEAD` | `b2ceaa60ac74183098677df08170db312d4d54b5` |
| 基线祖先关系 | `git merge-base --is-ancestor f64de341... HEAD` | 是（exit 0） |
| 工作区状态 | `git status` | 干净，仅两个未跟踪项：`CLAUDE.md`、`docs/agents/`（用户已有的 agent 工作流配置，**已保留未动**） |
| 最近提交 | `git log --oneline -5` | `b2ceaa60`（项目规划 docs）→ `f64de341`（上游 1.1.0 release notes）→ `fff96c23` / `5a1af64b` / `33acb48b`（上游代码提交） |

结论：仓库处于预期的「基线 1.1.0 + 规划文档」状态，无未提交的产品代码改动。

---

## 3. 构建与运行检查结果

### 3.1 后端编译 ✅

```
./mvnw -DskipTests -q compile   → exit 0
```

Java 17 / Spring Boot 3.5.7 / Maven 四模块（bootstrap、framework、infra-ai、mcp-server），根 `pom.xml`。测试未执行：`RagentCoreApplicationTests.contextLoads` 是裸 `@SpringBootTest`，需要全套中间件在线才能通过（无 Testcontainers/H2，`bootstrap/src/test/resources/` 无独立 test 配置），本轮环境不满足。

### 3.2 前端构建 ✅

```
cd frontend && npm install && npm run build
→ vite v5.4.21，4895 modules，✓ built in 8.27s
```

仅 2 个无害警告（esbuild/fsevents install scripts 未授权执行、chunk >500kB）。`npm install` 正常完成（无 node_modules 起步）。

### 3.3 完整启动：未执行，环境阻塞（非代码问题）

只读探测结果（`docker ps` + 端口探测）：

| 依赖 | 默认地址 | 本机现状 | 性质 |
| --- | --- | --- | --- |
| PostgreSQL + pgvector | 127.0.0.1:5432 | **被其他项目容器 `omnicraft-postgres` 占用** | 环境冲突 |
| Redis（密码 123456） | 127.0.0.1:6379 | **被其他项目容器 `omnicraft-redis` 占用** | 环境冲突 |
| RocketMQ | 127.0.0.1:9876 | 未启动（仓库有 compose：`resources/docker/rocketmq-stack-5.2.0.compose.yaml`，Apple Silicon 用 `-amd` 版） | 环境缺失 |
| rustfs/MinIO | localhost:9000 | 未启动（仓库 milvus compose 内含 rustfs） | 环境缺失 |
| 模型 API key | `BAILIAN_API_KEY` / `SILICONFLOW_API_KEY` 等 | `application-local.yaml` 尚不存在（`.gitignore` 已排除，符合 CHANGES.md 约定） | 环境缺失 |
| ES 9200 | 默认关闭 | 被其他项目 `omnicraft-opensearch` 占用（若未来开 ES 通道会冲突） | 潜在冲突 |

代码侧确认的启动行为（静态证据）：

- **启动硬依赖**：PG（手动建库，先按序执行 `resources/database/schema_pg.sql`、`init_data_pg.sql`，无 Flyway/自动初始化）、Redis（Redisson + `SnowflakeIdInitializer`/`StorageInitializer`/`SemaphoreInitializer`/`VectorSpaceInitializer` 四个 `@PostConstruct` 失败即启动失败）、对象存储（`StorageInitializer` 建桶快速失败）、RocketMQ（上下文可带病启动但分块/删库/反馈三条事务消息链路全断，视作必需）。
- **启动不需要**：任何模型 API key（首包探测在请求期，`RoutingLLMService`）、mcp-server（连不上仅 `log.error` 跳过工具注册，`McpClientAutoConfiguration`）、Milvus/ES/LightRAG/Web 搜索（默认全关）。
- **「只启用 pgvector、关闭 ES/Web/图谱」＝ 默认配置**：`application.yaml:68`（`rag.vector.type: pg`）、`:71`（`keyword.type: none`）、`:79`（`graph.type: none`）、`:152-153`（`web-search.enabled: false`、`keyword.enabled: false`）。另有启动校验器防止「通道开但后端没配」的错误组合（`rag/config/validation/RetrievalChannelConfigValidator.java:72-102`）。

---

## 4. 能力适配矩阵

分类口径：原生可用 / 配置可用 / 新增适配器即可 / 需要外围模块 / 需要修改核心链路 / 尚未确认。
路径缩写 `BOOT` = `bootstrap/src/main/java/com/nageoffer/ai/ragent`。

### 4.1 网页/远程文档进入知识库的入口 —— 原生可用（单 URL）；通用采集 需要外围模块

- **证据**：知识库主路径 `POST /knowledge-base/{kb-id}/docs/upload`（`BOOT/knowledge/controller/KnowledgeDocumentController.java:96`）→ `KnowledgeDocumentServiceImpl.upload()`（`BOOT/knowledge/service/impl/KnowledgeDocumentServiceImpl.java:144-185`）：`sourceType=url` 时 `RemoteFileFetcher.fetchAndStore()`（`BOOT/knowledge/handler/RemoteFileFetcher.java:59-73`，HEAD 预检 + 流式下载 + 大小限制）落对象存储，官方 URL 存 `t_knowledge_document.source_location`（schema `resources/database/schema_pg.sql:195`，VARCHAR(1024)）。
- 另有摄取 Pipeline 路径 `POST /ingestion/tasks`（`BOOT/ingestion/controller/IngestionTaskController.java:57`）+ `HttpUrlFetcher`（`BOOT/ingestion/strategy/fetcher/HttpUrlFetcher.java:38`，OkHttp）。
- **关键类/表**：`RemoteFileFetcher`、`KnowledgeDocumentDO`、`t_knowledge_document`。
- **改核心代码？** 否。但**无 sitemap 解析、无链接发现、无 robots.txt 过滤、无 UA 设置**——通用采集器是外围净新增（Phase 0B 用静态种子 URL 清单绕过）。
- **未知点**：PolyU 官网对未设 User-Agent 的 OkHttp 请求（`BOOT/rag/config/HttpClientConfig.java:52-59` 无 UA）是否放行。验证方式：Phase 0B 首批导入实测；必要时在 `HttpClientConfig`/`RemoteFileFetcher` 加 UA 头（小改，非核心链路）。

### 4.2 Fetcher/Parser/Chunker/Enricher/Enhancer/Indexer 六类节点 —— 原生可用（HTML 结构化质量有缺口）

- **证据**：节点链 `fetcher→parser→enhancer→chunker→enricher→indexer` 全部实现于 `BOOT/ingestion/node/`（`FetcherNode`/`ParserNode`/`EnhancerNode`/`ChunkerNode`/`EnricherNode`/`IndexerNode`），节点编排按 DB 存储的 `nextNodeId` 单向链执行（`BOOT/ingestion/engine/IngestionEngine.java:59-93`；表 `t_ingestion_pipeline`/`t_ingestion_pipeline_node`，schema:432/445）。
- Parser：注册表制（`BOOT/core/parser/registry/ParserRegistry.java:51`，启动期 `(MIME × 档位)` 建表+自检）。**HTML 用 Apache Tika**（`BOOT/core/parser/TikaDocumentParser.java:46`，认领 `text/html`，`parseToString` 拍平为段落，标题层级/表格结构丢失；全仓库无 Jsoup）。
- Chunker：Block 感知分块（`BOOT/core/chunk/ChunkingService.java:41` + `blockaware/BlockAwareChunkerDispatcher.java:42`，7 种 BlockChunker 按类型查表），预算 `ChunkBudget` 默认 1024 字符/8 重叠（`core/chunk/model/ChunkBudget.java:30-44`）。
- 知识库主路径不走节点链，走五步内核 `DefaultIngestionKernel`（`BOOT/core/ingest/DefaultIngestionKernel.java:48-123`：detect→parse→chunk→embed→index），分块预算经 `t_knowledge_document.ingestion_spec` JSONB 按文档配置（`core/ingest/IngestionSpec.java`、`knowledge/support/IngestionSpecCodec.java`）。
- **改核心代码？** 否。若 HTML 拍平导致检索质量差，新增 `HtmlDocumentParser implements DocumentParser`（Jsoup）即可被注册表自动接管——**新增适配器即可**，不动编排。
- **未知点**：Tika 拍平 PolyU Library 页面后的实际检索质量。验证方式：Phase 0B 评测实测（出口检查点②）。

### 4.3 自定义 DocumentFetcher / 摄取节点扩展 —— 新增适配器即可

- **证据**：接口 `DocumentFetcher { SourceType supportedType(); FetchResult fetch(DocumentSource); }`（`BOOT/ingestion/strategy/fetcher/DocumentFetcher.java:26-42`）。`FetcherNode` 构造器注入 `List<DocumentFetcher>` 自动建路由 Map（`FetcherNode.java:47-52`）——**新增一个 `@Component` 实现 + `SourceType` 枚举加值（`ingestion/domain/enums/SourceType.java:32-47`，现仅 file/url/feishu）即接入，无需改编排代码**。
- 节点扩展同构：`IngestionNode` 实现 + `IngestionNodeType` 枚举（`ingestion/domain/enums/IngestionNodeType.java:30-60`）。
- **改核心代码？** 否。但注意：**知识库主路径不经过 `FetcherNode`**（直接 `RemoteFileFetcher`），所以 PolyU 批量导入更适合「外围脚本循环调 upload API」而不是自定义 fetcher。
- **未知点**：无（机制代码完整可读）。

### 4.4 Chunk 元数据与 extras 的存储/传递 —— 存储原生；chunk 模式不写 extras、检索过滤未实现（需要外围模块，Phase 0B 不依赖）

- **证据**：`ChunkMetadata`（`BOOT/core/chunk/model/ChunkMetadata.java:41-46`）含 `extras Map<String,Object>`；`toMap()`（:80-99）是唯一序列化点；PG 落 `t_knowledge_vector.metadata JSONB` + GIN 索引（schema:511-527；写入 `BOOT/rag/core/vector/PgVectorStoreService.java:49-56`）。
- **三个限制（重要）**：
  1. extras 仅摄取 Pipeline 节点写入（`EnricherNode.java:83-108`、`IndexerNode.attachPipelineMetadata`——后者会把 `source_location` 等管道字段注入 extras，`IndexerNode.java:122-152`）；**知识库主路径 chunk 模式（`DefaultIngestionKernel`）不产 extras**，而该路径的 PIPELINE 模式被上游显式禁用（`KnowledgeDocumentServiceImpl.java:282`「管道模式重构中，暂不可用」）。
  2. 检索后不回填 extras：`RetrievedChunk`（`framework/.../convention/RetrievedChunk.java:37-93`）无 metadata 字段；PG 检索 SQL 只 SELECT id/content/collection/score（`PgVectorRetrieverService.java:90`）。
  3. 按 extras 过滤未实现：`RetrieveRequest.metadataFilters`（`BOOT/rag/core/retrieval/RetrieveRequest.java:65-74`）全仓库无消费点；所有检索仅按 `collection_name` 过滤。
- **改核心代码？** Phase 0B 不需要（部门路由用「知识库 collection + 意图树」实现，引用链路靠文档级 `source_location` 闭环）。若未来要按 department/language/effective_date 做**库内**过滤，需新增 postprocessor/检索 SQL 扩展（外围模块，PG JSONB+GIN 存储条件已就绪）。
- **规划文档修正项**：`project-docs/01` §4「元数据」与 `CHANGES.md`「语料管线」中对 extras 的预期（写 department/audience/effective_date/language 支撑过滤）高于本地版本实际能力，Phase 1 前需重新设计落点（候选：入库前把元数据写进 chunk 正文前缀，或等 extras 链路补齐）。

### 4.5 多知识库与意图树路由 —— 配置可用（纯 DB/管理端，零代码）

- **证据**：意图树存 `t_intent_node`（schema:300-325；level 0/1/2 = DOMAIN/CATEGORY/TOPIC，kind 0/1/2 = KB/SYSTEM/MCP；叶子节点绑 `collection_names JSONB` 多知识库集合 + `top_k` + `examples/description`）。调用链：`StreamChatPipeline`（`BOOT/rag/service/pipeline/StreamChatPipeline.java:82-120`：改写→意图→检索）→ `IntentResolver` → `DefaultIntentClassifier`（LLM 单次调用给全部叶子打分，prompt 模板 `resources/prompt/intent-classifier.st`，阈值 0.35/上限 3 意图）→ `RetrievalScopeResolver`（`BOOT/rag/core/retrieval/channel/` 下，`confidence-threshold=0.6` 以下退化全库检索，`supplement-ratio=0.25` 保底补未命中库）。
- 管理端 CRUD 完整（`BOOT/rag/controller/IntentTreeController.java:51-102`），**每次增删改自动清 Redis 意图缓存即时生效**（`IntentTreeServiceImpl` 各写方法调用 `intentTreeCacheManager.clearIntentTreeCache()`）。
- **空树优雅降级**：`DefaultIntentClassifier.loadIntentTreeData()`（:70-97）空树返回空叶子集 → 分类为空 → 全库检索，不影响启动。
- **改核心代码？** 否。新增一个校园分类→知识库路由 = 建库 + 意图树插节点（TOPIC 级 KB 节点必须绑库，`IntentTreeServiceImpl.java:136-140` 校验）。
- **注意**：`init_data_pg.sql` **无意图树种子数据**（grep 计数 0，仅 t_user/t_agent_profile/t_agent_prompt 三表有 INSERT）——PolyU 意图树要从零建（管理端或种子 SQL）。

### 4.6 向量检索、RRF、Rerank —— 原生可用（默认配置即目标形态）

- **证据**：通道编排 `MultiChannelRetrievalEngine.executeSearchChannels`（`BOOT/rag/core/retrieval/MultiChannelRetrievalEngine.java:132-198`，CompletableFuture 并行 + 单通道超时降级，线程池 `ThreadPoolExecutorConfig.java:66-117`）。四通道：Vector（默认开）/Keyword ES（`@ConditionalOnProperty rag.keyword.type=es`）/Graph LightRAG（`rag.graph.type=lightrag`）/WebSearch（默认关）。
- pgvector 查询：`PgVectorRetrieverService.java:71-99`（`SELECT id, content, collection_name, 1-(embedding <=> ?::vector) AS score ... WHERE collection_name IN (...) ORDER BY embedding <=> ?::vector LIMIT ?`，HNSW `ef_search=200` 会话调优；PG 支持跨库单 SQL 全局检索）。
- 后处理链（按 order）：去重 `DeduplicationPostProcessor`(1) → RRF `FusionPostProcessor`(5)（公式 `weight/(rrf-k+rank+1)`，`rrf-k=20`、通道权重 vector=1.0）→ Rerank `RerankPostProcessor`(10)（百炼 qwen3-rerank，失败降级 `rerank-noop` 截断保序，`infra-ai/.../rerank/NoopRerankClient.java:29-47`）→ 富化 `MetadataEnrichmentPostProcessor`(20)（回表补 docId/chunkIndex/docName）。
- 漏斗三段预算（recall-budget 20 → rerank-candidate-limit 40 → default-top-k 10）启动校验：`SearchChannelProperties.afterPropertiesSet()`（`BOOT/rag/config/SearchChannelProperties.java:79-122`）。
- **改核心代码？** 否。

### 4.7 中文问题检索英文文档的 Embedding —— 配置可用；实际效果 尚未确认（模型能力，代码无跨语逻辑）

- **证据**：查询侧与文档侧**同一模型同一注册表**：查询 `PgVectorRetrieverService.embedAndNormalize`（:57-59，问题原样 embed，无任何预处理）；文档 `ChunkEmbeddingService.embed`（:54，维度 1536 逐条校验）。默认候选 `qwen-emb-8b` = SiliconFlow `Qwen/Qwen3-Embedding-8B`（`application.yaml:253-260`），OpenAI 兼容 `/v1/embeddings` 且显式带 `dimensions=1536`（`infra-ai/.../embedding/AbstractOpenAIStyleEmbeddingClient.java:115-131`）；本地兜底候选 `qwen-emb-local`（Ollama qwen3-embedding:8b-fp16）。
- **全仓库无语言检测/翻译代码**（grep `翻译|detectLang|multilingual|translate` 仅命中 MinerU 解析参数 `language: ch`）。跨语召回完全依赖 Qwen3-Embedding-8B 模型自身多语能力；Rerank（qwen3-rerank）同样原样接收中文 query + 英文 text。
- **改核心代码？** 否。**未知点**：中文问→英文文档的实际 HitRate/MRR（模型能力无法从代码确认）。验证方式：Phase 0B 评测集实测（出口检查点①）。

### 4.8 引用、来源链接、原文预览 —— 原生可用（官方 URL 全链路无损，已逐环验证）

- **证据链（摄取→检索→生成→前端）**：
  1. 摄取：官方 URL 存 `t_knowledge_document.source_location`（`KnowledgeDocumentServiceImpl.java:171`）。
  2. 检索：命中 chunk 回表 `t_knowledge_chunk.doc_id → t_knowledge_document` 补 docName（`ChunkMetadataResolver.java:62-96`）。
  3. 生成：`SourcesAssembler`（`BOOT/rag/core/source/SourcesAssembler.java:43-138`）批量回表文档，`resolveUrl()`（:117-126）对 sourceType=url/feishu **返回 `doc.getSourceLocation()` 原文**（仅 blankToDefault），file 类型返回 null——**来源面板 URL 是官方 URL，不是 MinIO 对象地址**。
  4. 下发：SSE `finish` 事件携带 sources（`StreamChatEventHandler.java:216-217`）+ 落库 `t_message.sources JSONB`（schema:67-84）。
  5. 前端：`frontend/src/lib/source.ts:42-48`——有 url 直接 `window.open` 跳官方站，无 url 才走本地预览。
- 行内角标 `[N](#cite-N)`：prompt 注入式（`rag.citation.enabled: true` → `RAGPromptService.java:57-76` 追加 `resources/prompt/answer-citation-rules.st` + `CitationContextEnricher` 给上下文编号），模型自行输出；前端 `MarkdownRenderer.tsx` remark 插件渲染为可点击 `SourceCitation` 组件。
- 原文预览：`GET /knowledge-base/docs/{docId}/preview`（`KnowledgeDocumentController.java:179`）**仅支持 markdown**，从对象存储读文件流；其他类型走 `/file` 端点下载原文件（HTML 文档的「原文预览」实际是下载 HTML，非站内渲染——小体验缺口，引用面板有官方外链兜底）。
- **两个注意点**：`source_location` VARCHAR(1024) 超长截断（PolyU URL 一般远小于，低风险）；上传请求无 `docName` 字段，`docName` 取远端文件名（`upload()` `:163`）——**「官方页面标题」需导入脚本上传后经文档更新接口回填**。
- **改核心代码？** 否。

### 4.9 定时刷新、失败重试、幂等、删除更新 —— 原生可用（机制完整度高于预期）

- **证据**：`KnowledgeDocumentScheduleJob`（`BOOT/knowledge/schedule/KnowledgeDocumentScheduleJob.java:64-78`，每 10s 扫 `t_knowledge_document_schedule`，DB 行租约锁 `ScheduleLockManager`：CAS 抢锁 + 心跳续约 + 锁丢失中止）。刷新流程 `ScheduleRefreshProcessor.process()`（:66-274）：**三级变更检测**（HEAD 比 ETag/Last-Modified → 未变跳过；变了全量下载算 SHA-256 → 与 `last_content_hash` 相同则跳过）→ 变更才 CAS 占文档 → 新文件入存储 → 完整重摄取 → 成功后切换文件元数据、删旧文件。三层状态机（ScheduleRunStatus / DocumentStatus / 进程内 Phase 补偿）。
- 更新语义：**先删后插全量替换**，单事务扇出（`ChunkIndexWriter.replaceDocument` → `RelationalChunkSink` 写 `t_knowledge_chunk` + `VectorChunkSink` 先删后建向量；ES/图谱经装饰器 best-effort 同步）。cron 周期校验最小 60s（`min-interval-seconds`）。
- **缺失项（Phase 0B 由采集脚本兜底）**：无 URL 级去重（同 URL 重复上传 = 两个文档，`t_knowledge_document` 对 source_location 无唯一约束/查重）；无重试计数/退避（失败标 FAILED，定时场景等下个 cron 周期盲重试）；无增量更新（每次全删全建，chunkId 重新生成）。
- **改核心代码？** 否。「验证一次文档更新重入库」直接可用。
- **未知点**：无（机制代码完整可读）。

### 4.10 Prompt、示例问题、术语、意图树的配置化 —— 配置可用

- **证据**：6 个 Prompt slot（SYSTEM_CHAT/KB_ANSWER/MCP_ANSWER/MIXED_ANSWER/CONVERSATION_SUMMARY/RECOMMENDED_QUESTIONS）全在 DB `t_agent_prompt`（schema:413-426，种子见 `init_data_pg.sql:16/95/363/623/851/944`），管理端「智能体」页可编辑，**保存即清 Redis 缓存生效**（`AgentProfileAdminServiceImpl.java:281` 等调用 `cacheManager.clearCache()`；直接改 DB 最长延迟 1h 缓存过期）。
- 示例问题：`t_sample_question`（schema:105-114）管理端 CRUD 无缓存即时生效（`SampleQuestionController.java:51-96`）；**无种子数据**，前端 `WelcomeScreen.tsx` 有硬编码中文兜底卡片（内容总结/任务拆解/灵感扩展）——DB 有数据时覆盖兜底。
- 术语映射：`t_query_term_mapping`（schema:327-344）管理端 CRUD + 写后清缓存（`QueryTermMappingController.java:49-86`）；调用链在问题改写前（`MultiQuestionRewriteService.java:73-96` → `QueryTermMappingService.normalize`，仅精确匹配替换）。
- **边界**：意图分类器、问题改写、引用规则、上下文格式等**内部管线模板在 classpath `resources/prompt/*.st`**（`PromptTemplateLoader` 读取），不存 DB 不可管理端改——但均域无关，Phase 0B 无需动。
- **改核心代码？** 否（改 Prompt/示例/术语/意图树全部 DB/管理端操作）。
- **未知点**：无。

### 4.11 前端校园品牌化与 i18n 现状 —— 最小品牌化配置可用级轻改；完整 i18n 需要外围模块（Phase 1）

- **现状**：Vite + React 18 + Tailwind + Radix，**无任何 i18n 库**（package.json 无 react-i18next/react-intl）；**69/113 个 ts/tsx 文件含硬编码中文**（管理端页最密集：KnowledgeGraphPage 275 行、KnowledgeDocumentsPage 228 行、IngestionPage 192 行…）。
- 品牌落点：`frontend/index.html:6`（`<title>Ragent AI 智能体</title>`）、`frontend/src/components/layout/Sidebar.tsx:177-178`（`Ragent AI 智能体`/`Powered by AI`）、`frontend/src/pages/LoginPage.tsx`（欢迎回来/登录文案）、`frontend/public/favicon.svg`。
- 来源面板/引用组件齐备：`frontend/src/components/chat/SourcesPanel.tsx`、`SourceCitation.tsx`、`lib/source.ts`——校园场景展示官方 URL 与外链**零前端改动**。
- **最小品牌化改动清单（~5 文件）**：`index.html`（title）、`Sidebar.tsx`（品牌名+副标语，加非官方声明）、`LoginPage.tsx`（文案+声明）、`favicon.svg`（替换，避免校徽）、可选 `WelcomeScreen.tsx` DEFAULT_PRESETS（不改也行，DB 种子会覆盖）。
- **改核心代码？** 否（前端文案级改动）。完整 i18n 是 Phase 1 净新增项。

### 4.12 启动依赖（PG/Redis/RocketMQ/MinIO/模型服务） —— 环境问题，非代码问题

见 §3.3。要点：最小必需集 = PostgreSQL(pgvector) + Redis(密码 123456) + rustfs/MinIO + RocketMQ + 2 个模型 key（BAILIAN 聊天、SILICONFLOW embedding；PDF/Word/PPT 才需 MINERU，纯 HTML 语料不需要）。数据库初始化**手动**执行 `resources/database/schema_pg.sql` + `init_data_pg.sql`（`resources/database/README.md`）。Milvus/ES/LightRAG/mcp-server/OSS 全部可关。对个人项目部署成本**中等偏重但可控**（4 个标准 docker 容器 + 2 个 key，compose 现成一半：RocketMQ 和 rustfs 在 `resources/docker/` 有，PG/Redis 无 compose 需自备）。

---

## 5. 核心链路与可扩展边界

### 5.1 必须遵守的边界（Phase 0B 红线）

**只用「知识库路径 chunk 模式」摄取 PolyU 语料**：

- 摄取 Pipeline 独立路径（`/ingestion/tasks`）的 `IndexerNode` **只写向量库、不写 `t_knowledge_chunk`**（`IndexerNode.java:95-99` 直接 `vectorStoreService.indexDocumentChunks`）——其产物无法被检索后富化（`ChunkMetadataResolver` 回表查不到）和来源组装（`SourcesAssembler.loadDocs` 查 `t_knowledge_document` 查不到）解析，引用面板会退化。该路径留给上游 PIPELINE 重构，不用于本项目。
- 知识库路径的 PIPELINE 模式被上游禁用（`KnowledgeDocumentServiceImpl.java:282` 显式抛「管道模式重构中，暂不可用」），一律用默认 chunk 模式（`processMode=chunk` + `ingestionSpec` 调分块预算）。

### 5.2 可扩展点盘点（Phase 0B 会用到的）

| 扩展点 | 位置 | 接入方式 |
| --- | --- | --- |
| 知识库/文档/分块 | 管理端 + REST API | 建库→upload(url+cron)→chunk，全程 HTTP |
| 意图树 | `t_intent_node` + 管理端 CRUD | DB 种子或管理端建树，即时生效 |
| 术语映射 | `t_query_term_mapping` + 管理端 | DB 种子，即时生效 |
| Prompt 槽位 | `t_agent_prompt` + 管理端 | DB 种子/管理端改，即时生效 |
| 示例问题 | `t_sample_question` + 管理端 | DB 种子，即时生效 |
| HTML 结构化解析（可选） | `DocumentParser` 接口 + `ParserRegistry` | 新增 `@Component` 解析器即自动接管 |
| 文档刷新 | 每文档 cron | upload 时传 `scheduleEnabled`+`scheduleCron` |

### 5.3 不碰的核心链路（零改动承诺范围）

`StreamChatPipeline`（改写→意图→检索→生成→SSE）、`MultiChannelRetrievalEngine` + 四通道、后处理链、`RAGPromptService` 编排、`SourcesAssembler`/`CitationContextEnricher`、`IngestionKernel` 五步内核、`ChunkIndexWriter` 双落点、调度刷新状态机——以上均不改。前端 `SourcesPanel`/`MarkdownRenderer`/`source.ts` 不改。

---

## 6. 校园场景需要修改的文件范围

### 6.1 预计修改的现有文件（全部非核心链路）

| 文件 | 改动 | 性质 |
| --- | --- | --- |
| `frontend/index.html` | title → PolyU Campus Assistant（非官方） | 文案 |
| `frontend/src/components/layout/Sidebar.tsx` | 品牌名/副标语 + 非官方声明一行 | 文案 |
| `frontend/src/pages/LoginPage.tsx` | 欢迎文案 + 非官方声明 | 文案 |
| `frontend/public/favicon.svg` | 替换为无校徽元素的通用图标 | 资源 |
| `CHANGES.md` | 修正 `resources/initializer/...IntentTreeInitMain.java` 的失效引用（该文件不存在） | 文档 |
| `project-docs/01-项目规划与路线图.md` | 修正 extras 预期（§4 元数据、§五 底座映射表「ChunkMetadata.extras 原生」条目）与 initializer 引用 | 文档 |
| `bootstrap/src/main/resources/application.yaml` | （可选）注入采集 UA 相关配置若实测需要 | 配置 |

Java 后端：**预计 0 个现有文件需要修改**（若 PolyU 站点拒绝无 UA 请求，则 `HttpClientConfig`/`RemoteFileFetcher` 加 UA 头属小改；若 Tika 质量不达标，则新增 `HtmlDocumentParser`——均为新增/小改，不触核心链路）。

### 6.2 预计新增的文件

| 文件 | 用途 |
| --- | --- |
| `scripts/polyu_seed_urls.csv`（或 json） | 20–50 条 Library 种子 URL 清单：url、官方页面标题、department、language、建议 cron |
| `scripts/polyu_import.py`（或 shell+curl） | 批量导入：循环调 `POST /knowledge-base/{kb}/docs/upload`（sourceType=url）→ 回填 docName → `POST /docs/{id}/chunk`；记录已导入 URL 实现脚本级幂等（弥补系统无 URL 去重） |
| `resources/database/upgrades/polyu/seed_intent_tree.sql` 等 | 意图树/术语映射/示例问题/Prompt 槽位的种子 SQL（或全程管理端操作，二选一） |
| `project-docs/03-Phase0B-垂直切片实施记录.md` | 实施与验证记录（含三语问法结果、刷新验证记录） |

### 6.3 只需数据库种子或配置的内容

PolyU Library 知识库（`t_knowledge_base`）、意图树 v1（1 DOMAIN + 1 CATEGORY + 3–5 TOPIC）、术语映射 v1（10–20 条）、示例问题（EN/ZH 各若干）、6 个 Prompt 槽位双语改写、每文档刷新 cron。

### 6.4 需要新增适配器的内容

仅一项且为**条件触发**：`HtmlDocumentParser`（Jsoup，保标题层级与表格）——仅当 Phase 0B 检查点②不达标时实施。

### 6.5 是否触碰核心检索链路

**否。** §5.3 所列链路零改动。

---

## 7. Phase 0B 最小垂直切片设计（只设计，不实现）

### 7.1 范围确认（对齐任务约束）

只用 PolyU Library（lib.polyu.edu.hk）；20–50 条静态种子 URL（人工筛选拿『有明确操作类答案』的页面：开放时间、设施预订、借阅规则、打印服务、电子资源访问等）；不写通用爬虫；页面需含可核验的操作类答案；支持英文问题、中文问题、中文问题检索英文文档三类问法；回答展示官方页面标题、URL、支撑原文；至少验证一次文档更新重入库；最小品牌化；不做资讯流/公众号/完整 i18n。

### 7.2 数据流设计

```
种子清单(20-50 URL+标题)
  → scripts/polyu_import.py
      → POST /knowledge-base/{polyu-lib}/docs/upload  (sourceType=url, scheduleEnabled, cron)
      → PUT  文档更新接口回填 docName=官方页面标题
      → POST /knowledge-base/docs/{id}/chunk           (触发 MQ 事务消息 → 五步内核)
      → 脚本记录 url→docId 映射（幂等：已导入跳过）
  → 检索：意图树(TOPIC 绑 polyu_lib collection) → pgvector(1536, qwen-emb-8b) → RRF → qwen3-rerank
  → 回答：KB_ANSWER 槽(双语) + 行内引用 + SourcesPanel(官方标题/URL/摘录)
```

三语问法验证矩阵：EN→EN（基线）、ZH→EN（跨语检查点①）、ZH→EN 经术语映射（如「订馆/图书馆座位」→ library seat booking，验证 `t_query_term_mapping` 生效）。

文档更新重入库验证：挑 1–2 个 URL 配短周期 cron（如每 2 分钟，满足最小 60s），观察 `t_knowledge_document_schedule_exec` 记录 ETag/hash 未变→SKIPPED；用本地可控测试 URL（或等官网真实变更）触发变更→SUCCESS，抽查 `t_knowledge_chunk`/`t_knowledge_vector` 先删后插且引用不悬空。

### 7.3 实施顺序与工时估算

| # | 步骤 | 产出 | 工时 |
| --- | --- | --- | --- |
| 1 | 本地环境搭建：新建 PG(pgvector) 实例（避开 5432 冲突）+ Redis（密码 123456）+ rustfs + RocketMQ compose；执行 schema/init SQL；`application-local.yaml` 注入 key | 可启动的后端 | 2–4h（端口冲突排坑上限 +2–4h） |
| 2 | 对照组 B 冒烟：跑通底座自带 admin 账号登录→建测试库→传 1 个 md→问答→看引用/SSE | 底座功能正常性证据 | 1–2h |
| 3 | 种子 URL 人工筛选 20–50 条（Library 操作类页面，记录标题/语言） | `polyu_seed_urls.csv` | 3–5h |
| 4 | 导入脚本 + 首批入库 + docName 回填 | 脚本 + 20–50 文档入库 | 3–6h（含 UA/反爬实测，若被拒 +1–2h 加 UA） |
| 5 | DB 种子：意图树 v1（1 域 3–5 主题）+ 术语 10–20 条 + 示例问题 + Prompt 槽双语改写 | 种子 SQL/管理端配置 | 3–5h |
| 6 | 三语问法验证 + 引用 URL/标题/原文核对（10–20 条问题人工评测） | 验证记录 + 检查点①②结论 | 2–4h |
| 7 | 文档更新重入库验证（cron 短周期 + 变更触发） | 验证记录 | 1–2h |
| 8 | 前端最小品牌化（§6.1 的 4+1 文件） | 品牌化前端 | 2–4h |
| 9 | （条件触发）`HtmlDocumentParser`（Jsoup）+ 重灌语料 | 结构化 HTML 解析 | 4–8h |
| 10 | 汇总 Phase 0B 记录文档 + 决定是否进 Phase 1 | `03-Phase0B-*.md` | 1–2h |

**合计：不含步骤 9 约 18–34 小时；含步骤 9 上限约 42 小时。** 关键路径在步骤 1（环境）与步骤 3（内容工程），符合「主要新增工作集中在采集适配和内容工程」的预期。

### 7.4 Phase 0B 出口检查点

1. **跨语检索**：中文问题（含口语/小红书式问法）检索英文 Library 文档的 HitRate@10 可接受（对照英文问法基线不明显塌方）→ 不达标进入降级链（先术语映射加量 → 再评估 ES 开启）。
2. **HTML 解析质量**：Tika 拍平后操作类答案（步骤/时间/地点）能否被准确检索与引用 → 不达标则实施步骤 9。
3. 引用面板官方 URL/标题正确率 100%（错一条即查）。
4. 更新重入库后引用不悬空、无重复文档。

---

## 8. 风险和未知项

| # | 风险/未知 | 等级 | 说明与缓解 |
| --- | --- | --- | --- |
| R1 | 中文问→英文文档检索质量（模型能力，代码无跨语逻辑） | 中·未知 | Phase 0B 检查点①实测；降级链已定义 |
| R2 | Tika HTML 拍平丢标题层级/表格 → 检索质量 | 中·未知 | 检查点②实测；`HtmlDocumentParser` 适配器方案现成（4–8h） |
| R3 | PolyU 站点对无 UA 的 OkHttp 请求可能 403 | 低·未知 | `HttpClientConfig.java:52-59` 未设 UA；实测被拒则加 UA 头（小改） |
| R4 | 规划文档两处与本地版本不一致：`CHANGES.md:18` 引用不存在的 `resources/initializer/...IntentTreeInitMain.java`；extras 能力被高估（§4.4） | 低 | 文档修正即可；不影响主链路；意图树改走管理端/种子 SQL |
| R5 | URL 级去重缺失（同 URL 重复上传=双文档） | 低 | 导入脚本记录 url→docId 映射兜底 |
| R6 | 部署成本中等偏重（4 中间件 + 2 key） | 低-中 | compose 覆盖一半；PG/Redis 自备；纯 HTML 语料不需要 MINERU key |
| R7 | 本机端口冲突（5432/6379/9200 被其他项目容器占用） | 环境 | 用户决策（§9 Q1） |
| R8 | 刷新全删全建导致 chunkId 每次变化 | 低 | 无外部引用 chunkId 的场景无影响；记录为已知行为 |
| R9 | 上游正在重构 PIPELINE 模式（「管道模式重构中，暂不可用」） | 低 | 本项目只用 chunk 模式，不受影响；跟随上游 1.1.0 不升级 |
| R10 | `source_location` VARCHAR(1024) 截断 / docName 需回填 | 低 | 导入脚本侧处理 |

**尚未确认（无法从代码确认、必须实测）清单**：R1、R2、R3。其余均有代码级证据。

---

## 9. 结论：建议 **Go**（进入 Phase 0B）

对照判断标准逐条核对：

| Go 条件 | 结论 | 证据 |
| --- | --- | --- |
| 校园内容可经配置/数据/扩展点接入 | ✅ | §4.1/4.5/4.10：upload API + 意图树/术语/示例/Prompt 全 DB 驱动 |
| 不需重写检索/生成/引用主链路 | ✅ | §5.3：零改动清单 |
| 可保留 pgvector 并关闭非必要通道 | ✅ | §3.3：默认配置即目标形态 |
| 来源引用保留官方 URL | ✅ | §4.8：`source_location` → `resolveUrl` 原样透传，全链路逐环验证 |
| 定时/手动刷新复用现有机制 | ✅ | §4.9：cron + ETag/SHA256 + 全量重摄取 |
| 最小前端品牌化不需重构 | ✅ | §4.11：~5 文件文案级 |
| 主要新增工作集中在采集适配和内容工程 | ✅ | §7.3：工时分布 |

反向触发项（网页采集进不了 Pipeline / URL 丢失 / 部门路由侵入核心 / 重写文档状态机制 / 每分类改 Java / 部署明显不适合 / 规划重大不一致）**均未触发**：部署成本评「中等偏重但可控」（不构成「明显不适合」）；规划不一致为两处文档级修正项（非能力性重大不一致）。

**进入 Phase 0B 前需用户决定的问题**：

1. **本地端口冲突**：5432/6379 被 `omnicraft-*` 容器占用——停掉这些容器、给 polyu-agent 换端口（改 application.yaml）、还是另起独立实例？（推荐：PG/Redis 用独立端口新实例，不动其他项目）
2. **模型服务选型**：聊天用百炼（qwen3-max/qwen-plus）还是全本地 Ollama（qwen3:8b，免费但慢、意图分类质量待验）？Embedding 主路 SiliconFlow（Qwen3-Embedding-8B）需要真实 key——**跨语检索检查点①依赖云端 embedding 质量，Ollama 本地 8b 为降级兜底**。
3. **种子 SQL vs 管理端手搭**：意图树/术语/示例问题/Prompt 用种子 SQL（可版本控制、可重放，推荐）还是管理端手工操作？
4. **HTML 解析策略**：先接受 Tika 拍平跑检查点②（推荐，省 4–8h），还是直接实施 `HtmlDocumentParser`？
5. **繁简归一（OpenCC）**：Phase 0B 语料若只取英文版页面则完全不需要；若要中文版页面，是入库前脚本归一还是暂缓？（推荐 Phase 0B 只取英文版，中文靠跨语检索验证）

---

## 10. 下一阶段任务清单（Phase 0B）及工时估算

见 §7.3 表格（10 步，18–34h 基线 / 含条件项上限 42h）。另加两条审计遗留修正项（可并入步骤 5/10）：

- 修正 `CHANGES.md` 与 `project-docs/01` 的失效引用和 extras 预期（0.5h，文档）。
- 在 `CHANGES.md`「已实施」追加本审计报告条目（本轮产出，0.5h）。

---

## 附录：本轮审计执行的命令记录

```
git status / git branch --show-current / git log --oneline -5
git merge-base --is-ancestor f64de341452c8998ebf64cd264e60ccad6a31631 HEAD && git rev-parse HEAD
./mvnw -DskipTests -q compile                                   # exit 0
cd frontend && npm install --no-audit --no-fund && npm run build  # ✓ built in 8.27s
docker ps --format '...'                                        # 只读
nc -z 127.0.0.1 <5432/6379/9876/9000/11434/9200>                # 只读端口探测
grep -c "t_intent_node" resources/database/init_data_pg.sql     # 0
```

（完整启动未执行：RocketMQ/MinIO 未启动、PG/Redis 端口被占用、无模型 API key——均为环境阻塞，未伪造启动结果，未读取/输出任何密钥值。）

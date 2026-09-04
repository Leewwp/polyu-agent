# 改动清单（相对上游基线）

**基线**：[nageoffer/ragent](https://github.com/nageoffer/ragent) tag `1.1.0`（commit `f64de341452c8998ebf64cd264e60ccad6a31631`），2026-09-03 导入。
上游 main 分支正向 2.0 演进，本项目不跟随。如需与上游比对：`git fetch upstream && git diff 1.1.0...upstream/main`。

## 已实施

| 日期 | 类型 | 说明 |
| --- | --- | --- |
| 2026-09-03 | docs | 项目初始化：新增项目 README（上游 README 保留为 `README.upstream.md`）、本改动清单、`project-docs/`（项目规划与路线图） |
| 2026-09-03 | chore | `.gitignore` 追加 macOS `.DS_Store` |
| 2026-09-03 | chore | `.gitignore` 追加本地密钥配置排除：`bootstrap/src/main/resources/application-local.yaml`（模型服务 API 密钥本地注入，不入库；`local` profile 激活） |
| 2026-09-03 | feat | 新增 `HtmlDocumentParser`（Jsoup 1.18.3 结构化 HTML 解析：主体区域选择 → 导航/模板清洗 → DOM 产 Block），`TikaDocumentParser` 让渡 `text/html` 与 `application/xhtml+xml` 两个 MIME 精确键；经 `ParserRegistry` 自动接管，不改摄取/分块/检索/回答链路。同五页重灌 `polyu_lib_0b2b_html`（19 chunks）对照 Tika 基线（97 chunks）：纯导航占比中位数 83.3%→0%、跨页重复模板 43→0、EN/ZH HitRate@10 保持 5/5 且 MRR 上升（0.850→1.000 / 0.729→0.900）。详见 `project-docs/06-Phase0B-2B-HTML解析器实现与对照.md` |
| 2026-09-04 | chore | 新增 `resources/docker/polyu-local-0b1.compose.yaml`（0B-1 产物）：本机独立中间件栈（PG 5434 / Redis 6381 / MinIO 9000-9001 / RocketMQ 9876 与 10909-10912，避开本机被其他项目占用的上游默认端口），宿主机发布端口全部绑定 `127.0.0.1`，容器间通信走内部网络；`.gitignore` 同步解除 `/CHANGES.md` 忽略（README 公开引用本清单） |

## 计划中（随实施转入"已实施"并附 diff 说明）

| 模块 | 计划改动 |
| --- | --- |
| 意图树 | 参照 `resources/initializer/enterprise-knowledge-base/IntentTreeInitMain.java` 重写 PolyU 域意图树（15–25 意图：教务注册 / 图书馆 / 设施预订 / 学生服务 / 奖助 / 国际交流 / 日程节点 / 无关拒答，低置信度澄清）；Phase 0 先建 3 域 10–15 意图 |
| 提示词 | 6 个 DB 级 Prompt Slot（SYSTEM_CHAT / KB_ANSWER / MCP_ANSWER / MIXED_ANSWER / CONVERSATION_SUMMARY / RECOMMENDED_QUESTIONS）改写为 PolyU 双语校园助手口径（管理端修改不改代码） |
| 术语映射 | 口语↔官方术语归一化 50–100 条（抢课/Add-Drop、订游泳池/Sports Centre 洞窟泳池预订、打印机/Printing Services 等，`QueryTermMapping` DB 表直填） |
| 语料管线 | 公开官网采集脚本（home.xml / news-sitemap.xml 做种子，AR / Library / SAO 起步）+ 预处理：繁简 OpenCC 归一、条款转 heading 入标题链、`extras` 写 department / audience / effective_date / language；robots.txt 禁抓路径（~90 个 `*/search-result`）写入采集器过滤规则 |
| 资讯流模块 | 新增（最大净新增项）：发现型爬虫（news-sitemap + 部门新闻页）→ LLM 自动分类（荣誉 / 重要事项 / 截止时间）→ 入库打标 → 前端信息流页面（日期 + 分类 + 来源链接，参考 aihot.virxact.com） |
| 双语化 | 前端 i18n（EN / 繁中优先，现文案纯中文）+ 提示词双语；检索 Embedding 当前基线为 Qwen3-Embedding-8B（原生多语），最终选型由 Phase 0M 跨语评测决定（见 `project-docs/03`） |
| 检索配置 | Web 搜索通道关闭（权威性：答案只来自官网）；ES 关键词通道关闭（IK 偏中文而语料英文为主，列为 D11 不达标时的修复旋钮）；LightRAG 图通道留 Phase 2 |
| 评测体系 | 自建 golden set 80–100 条（分层：精确条文 / 语义泛化 / 时效敏感（过期通知=禁止引用集）/ 无答案拒答）+ 对照组 B（底座官方示例）基线 + 消融（单路向量 vs 混合 + RRF vs +rerank vs +术语归一化）；模型选型分层评测独立于管线消融，见 `project-docs/03-Phase0M-模型选型与评测计划.md` |
| 模型选型（Phase 0M） | 四类角色（Embedding / Reranker / 辅助 Chat / 主 Chat）分层评测与锁定，计划见 `project-docs/03-Phase0M-模型选型与评测计划.md`：Embedding 对照 Qwen3-Embedding-8B vs 百炼 qwen3.7-text-embedding（1536 维，锁定先于全量入库）；Reranker 三臂（无 / qwen3-rerank / qwen3.7-text-rerank）；辅 Chat 优先 qwen3.7-flash-2026-07-15；主 Chat 候选 qwen3.8-flash / qwen3.7-plus-2026-05-26（基准）/ qwen3.8-max（兜底，不默认） |
| MCP 工具 | 机动项（可砍）：校历查询、截止日期提醒（公开数据实现） |
| 合规 | 界面与 README 常驻非官方声明；不采集登录后内容；答案级引用回链官网，不整站镜像 |

## 决策记录

- 2026-09-03：场景定案 PolyU 校园问答（放弃清关、电商商城两个候选方向——两者均未进入开发，无沉没成本）。选型依据与外部调研（信息源 / robots.txt / 竞品格局）见 `project-docs/01-项目规划与路线图.md`。
- 2026-09-03：模型选型原则定案，新增 Phase 0M 决策门（位于最小运行环境与测试语料就绪之后、大规模语料入库之前，计划见 `project-docs/03-Phase0M-模型选型与评测计划.md`）。要点：开发验证期质量优先、成本完整记录但不为省小钱牺牲 Embedding/Reranker/回答质量，部署后按真实调用量与账单降本；所有模型仅作候选（对照组 + 挑战组），以 PolyU 真实语料可复现评测定胜负，不以厂商 Benchmark 为据；Embedding 换型需全量重建向量库故优先评测锁定，Chat 走路由配置可迭代不永久固定。本轮仅规划文档，未改动任何模型配置。

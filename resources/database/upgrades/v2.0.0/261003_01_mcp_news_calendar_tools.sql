-- v2.0.0 261003 MCP 资讯/校历工具挂载（issue #214，父票 #182 r3 §MCP）：意图树两个 MCP 节点 + 两份技能手册
--
-- 背景：mcp-server 自 #164 的单工具（search_exam_papers）扩展到三工具——
--   - search_news：资讯检索（关键词 × 主题 × 时间窗）。数据走 rag 公开查询面
--     /public/news/mcp-search（本次随票新增，SaToken 白名单免登录），可见性=统一公开资格
--     （#180 R4：只读已发布，下架/隐藏/未过门隔离）；mcp-server 不恢复数据库层（#164 裁剪决策）。
--   - query_key_dates：校历关键日期查询，复用 #193 已上线查询口 /public/calendar/key-dates。
--   - 工具挂载门：发现工具须与已启用意图树 MCP 节点（kind=2 叶子）取交集，仅加 server 配置
--     不会让 Agent 拿到工具（AgentToolCatalog.resolveMcpTools）；技能 toolIds 校验走同一节点集
--     （AgentSkillAdminServiceImpl.validateToolIds）——所以本 SQL 是工具生效的必要施工项，不是可选项。
--   - 节点挂法沿 260929 判例：独立根节点（parent_code NULL、无子节点）= 根 + 叶子，listMcpToolNodes
--     只看叶子，不挂进现有 KB/SYS 域，对既有域的子节点集合零扰动；它作为叶子会进意图分类候选集（上游语义）。
--
-- ⚠ 应用后必须清两级 Redis 缓存（直连 SQL 不触发缓存刷新；重启 app 不清缓存——Redis 是外部容器）：
--   DEL ragent:intent:tree          （意图树，TTL 7 天等不起；IntentTreeCacheManager）
--   DEL ragent:agent:enabled-skills:v1  （技能清单，TTL 1 小时，建议同步清；AgentSkillCacheManager）
--   验证：GET /agent/v1/meta 的 mcpConfigured=true（= 交集后绑定数 > 0，能捕获「发现但未挂载」假成功）；
--   工具面：对 polyu-mcp:9099 POST /mcp（streamable HTTP）tools/list 应含三个工具。
--
-- 幂等性：两表 INSERT 均走 ON CONFLICT (id) DO NOTHING，重复执行安全；
--   回滚路径：UPDATE t_intent_node SET enabled=0 WHERE id IN ('2610030000000000001','2610030000000000002')
--   （或管理面停用），清意图树缓存后工具即从 Agent 目录消失。

-- ============================================
-- §1 意图树 MCP 节点（kind=2，独立根+叶子；mcp_tool_id 与 executor 常量逐字符一致）
-- ============================================
INSERT INTO t_intent_node (id, kb_id, intent_code, name, level, parent_code, description, examples, collection_name, collection_names, top_k, mcp_tool_id, require_confirm, kind, prompt_snippet, prompt_template, sort_order, enabled, create_by, update_by) VALUES
  ('2610030000000000001', NULL, 'polyu-news-search', 'PolyU 资讯检索', 0, NULL,
   '用户想查 PolyU 校园资讯/新闻/通知：奖学金、招生、科研、校园活动、就业、交换等已发布消息，带关键词、主题或时间范围。Searching published PolyU campus news and announcements by keyword, topic or time window.',
   '["最近有什么奖学金的资讯","过去一周 PolyU 有什么新闻","查一下交换生项目的最新消息","有没有关于考试安排的通知"]'::jsonb,
   NULL, '[]'::jsonb, NULL,
   'search_news', 0, 2, NULL, NULL, 94, 1, 'sys', 'sys')
ON CONFLICT (id) DO NOTHING;

INSERT INTO t_intent_node (id, kb_id, intent_code, name, level, parent_code, description, examples, collection_name, collection_names, top_k, mcp_tool_id, require_confirm, kind, prompt_snippet, prompt_template, sort_order, enabled, create_by, update_by) VALUES
  ('2610030000000000002', NULL, 'polyu-key-dates', '校历关键日期查询', 0, NULL,
   '用户想查校历关键日期 / deadline：学期起止、Add/Drop、考试期、成绩发布、缴费截止等具体日期或临近安排。Querying academic calendar key dates and deadlines.',
   '["add drop 截止日期是什么时候","下学期几号开学","缴费 deadline 是哪天","最近有什么重要日期"]'::jsonb,
   NULL, '[]'::jsonb, NULL,
   'query_key_dates', 0, 2, NULL, NULL, 93, 1, 'sys', 'sys')
ON CONFLICT (id) DO NOTHING;

-- ============================================
-- §2 技能手册（tool_ids 绑定 §1 节点声明的工具；前提=节点在位且启用）
-- ============================================
INSERT INTO t_agent_skill (id, skill_code, name, description, content, tool_ids, sort_order, enabled, create_by, update_by) VALUES
  ('2610030000000000011', 'news_search', '查校园资讯',
   '用户想检索 PolyU 校园资讯/新闻/通知（奖学金、招生、科研、活动、就业、交换），按关键词/主题/时间窗过滤时加载本手册',
   $prompt$# 查校园资讯（PolyU 资讯流）

## 适用场景
用户想了解 PolyU 相关的资讯动态：奖学金、招生、科研进展、校园活动、就业、交换项目等；常见问法「最近有什么新闻」「有没有 XX 的消息」「过去一周有什么通知」。

## 办理步骤
1. **确定过滤条件**：从用户话里提取关键词 / 主题 / 时间窗。`search_news` 至少要带 keyword、topic、date_from、date_to 之一，一个都没有时先问用户想查什么，不要无条件拉全量。
2. **调用工具**：调 `search_news`。参数：keyword=检索词（匹配标题与摘要，中英均可）；topic=主题 slug（用户指明主题类别时传）；date_from/date_to=时间窗（YYYY-MM-DD，用户说「最近一周」时换算成具体日期再传）；limit 默认 10，最多 50。
3. **转述结果**：按工具返回的条目转述（标题+日期+要点），需要细节时给原文链接。结果为空时如实说明，建议用户换关键词或放宽时间窗，不要编造资讯。

## 边界
- 工具只返回**已发布**的资讯；结果为空不代表该事项不存在，只是没有相关资讯报道。
- 摘要是 AI 生成的，转述时注明「以原文为准」，并把原文链接给用户。
- 用户问的是校历/deadline/考试安排这类具体日期时改用 `query_key_dates`，不要拿资讯检索凑合；问往年试卷时改用 `search_exam_papers`。$prompt$,
   '["search_news"]'::jsonb, 2, 1, 'sys', 'sys')
ON CONFLICT (id) DO NOTHING;

INSERT INTO t_agent_skill (id, skill_code, name, description, content, tool_ids, sort_order, enabled, create_by, update_by) VALUES
  ('2610030000000000012', 'key_dates_query', '查校历关键日期',
   '用户想查校历关键日期 / deadline（学期起止、Add/Drop、考试期、成绩发布、缴费截止）时加载本手册',
   $prompt$# 查校历关键日期（deadline / 校历）

## 适用场景
用户想确认某个关键日期或临近安排：学期起止、Add/Drop 截止、考试期、成绩发布、缴费 deadline 等；常见问法「XX 几号截止」「下学期什么时候开学」「最近有什么重要日期」。

## 办理步骤
1. **选分段**：默认查进行中+即将到来（current_and_upcoming，临近度排序）。用户问「错过了什么/上周截止的」用 recent_past；明确要翻旧账用 archived；问「大概十月下旬」这类模糊窗口用 undated。
2. **调用工具**：调 `query_key_dates`，带 keyword（如「缴费」「add drop」「exam」）精确到具体事项；limit 默认 10，最多 50。
3. **转述结果**：逐条给出事项+日期+剩余天数；条目带「适用」人群限制时必须一并转述（官方原文要求，不得省略）；带官方来源链接时给用户备查。

## 边界
- 模糊窗口（如 Late October 2026）没有具体日，转述时保留原文窗口，不要换算/编造具体日期。
- 结果为空时如实说明并建议换关键词或分段，不要凭印象报日期；日历数据以官方来源为准。
- 返回里若注明「部分数据源异常、按最后完整同步版本呈现」，必须原样告知用户，不要冒充最新数据。
- 用户问的是资讯动态（新闻/通知）时改用 `search_news`。$prompt$,
   '["query_key_dates"]'::jsonb, 3, 1, 'sys', 'sys')
ON CONFLICT (id) DO NOTHING;

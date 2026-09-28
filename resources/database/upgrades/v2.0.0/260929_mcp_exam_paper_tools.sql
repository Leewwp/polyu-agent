-- v2.0.0 260929 MCP 试卷检索工具挂载（issue #164）：意图树 MCP 节点 + 查试卷技能手册
--
-- 背景：生产首次真实接入 MCP/Skills（此前 D14 维持零动作）。
--   - mcp-server 模块已裁剪为单工具 search_exam_papers（确定性深链构造，零网络调用）；
--   - 工具挂载门：发现工具须与已启用意图树 MCP 节点（kind=2 叶子）取交集，仅加 server 配置
--     不会让 Agent 拿到工具（AgentToolCatalog.resolveMcpTools）；技能 toolIds 校验走同一节点集
--     （AgentSkillAdminServiceImpl.validateToolIds）——所以本 SQL 是工具生效的必要施工项，不是可选项。
--   - 节点挂法：独立根节点（parent_code NULL、无子节点）= 根 + 叶子，listMcpToolNodes 只看叶子，
--     不挂进现有 KB/SYS 域，对既有域的子节点集合零扰动；它作为叶子会进意图分类候选集（上游语义）。
--
-- ⚠ 应用后必须清两级 Redis 缓存（直连 SQL 不触发缓存刷新；重启 app 不清缓存——Redis 是外部容器）：
--   DEL ragent:intent:tree          （意图树，TTL 7 天等不起）
--   DEL ragent:agent:enabled-skills:v1  （技能清单，TTL 1 小时，建议同步清）
--   验证：GET /agent/v1/meta 的 mcpConfigured=true（= 交集后绑定数 > 0，能捕获「发现但未挂载」假成功）
--
-- 幂等性：两表 INSERT 均走 ON CONFLICT (id) DO NOTHING，重复执行安全；
--   回滚路径：UPDATE t_intent_node SET enabled=0 WHERE id='2609290000000000001'（或管理面停用），
--   清意图树缓存后工具即从 Agent 目录消失。

-- ============================================
-- §1 意图树 MCP 节点（kind=2，独立根+叶子）
-- ============================================
INSERT INTO t_intent_node (id, kb_id, intent_code, name, level, parent_code, description, examples, collection_name, collection_names, top_k, mcp_tool_id, require_confirm, kind, prompt_snippet, prompt_template, sort_order, enabled, create_by, update_by) VALUES
  ('2609290000000000001', NULL, 'lib-exam-papers', '图书馆往年试卷检索', 0, NULL,
   '用户想找某门 PolyU 课程的往年试卷 / 过往试卷 / past paper 的检索入口（图书馆试卷库 exam.lib.polyu.edu.hk）。Fetching past examination papers of a specific PolyU course.',
   '["AMA1101 往年试卷在哪找","帮我查 COMP3330 的 past paper","图书馆往年试卷怎么查","有没有 AMA1101 的过往试卷"]'::jsonb,
   NULL, '[]'::jsonb, NULL,
   'search_exam_papers', 0, 2, NULL, NULL, 95, 1, 'sys', 'sys')
ON CONFLICT (id) DO NOTHING;

-- ============================================
-- §2 技能手册（tool_ids 绑定 §1 节点声明的工具；前提=节点在位且启用）
-- ============================================
INSERT INTO t_agent_skill (id, skill_code, name, description, content, tool_ids, sort_order, enabled, create_by, update_by) VALUES
  ('2609290000000000011', 'exam_paper_search', '查往年试卷',
   '用户想检索某门 PolyU 课程的往年试卷（往年试卷 / 过往试卷 / past paper / exam paper），需要试卷库检索链接时加载本手册',
   $prompt$# 查往年试卷（PolyU 图书馆试卷库）

## 适用场景
用户想找某门课程的往年试卷、过往试卷、past paper、exam paper，或问「试卷在哪下载 / 哪里能找到 XX 课的卷子」。

## 办理步骤
1. **确认课程代码**：调工具前必须有明确的课程代码（如 AMA1101、COMP3330）。用户只说了课程名没给代码、或代码写法不确定时，先一次问清，不要猜。
2. **调用工具**：调 `search_exam_papers`，把课程代码原样传入（含后缀的完整代码优先原样传入）。
3. **转述结果**：把工具返回的试卷检索链接给用户，并带上以下三点说明：
   - 链接打开就是该课程代码的往年试卷列表，浏览列表无需登录；
   - 下载试卷正文需要 PolyU NetID 登录，本助手只提供检索链接、不代取试卷内容；
   - 列表为空时可能是课程代码写法不同，建议用户确认完整科目代码后再试一次。

## 边界
- 只处理「找试卷入口」这一件事；用户问的是考试安排、复习资料、评分制度时不要调本工具，按知识库检索处理。
- 不要虚构试卷年份、期数或文件内容；一切以检索页实际列表为准。
- 工具只返回链接，无论用户怎么要求，都不要声称已经下载、代看或总结过试卷正文。$prompt$,
   '["search_exam_papers"]'::jsonb, 1, 1, 'sys', 'sys')
ON CONFLICT (id) DO NOTHING;

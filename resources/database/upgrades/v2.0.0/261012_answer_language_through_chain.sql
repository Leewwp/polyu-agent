-- v2.0.0 261012 回答语言贯穿 Agent-KB 链：静态提示词冲突消除
-- 背景（2026-10-09 核查 research/bilingual-support-audit-20261009.md）：
--   英文提问收到中文成品答案（A0 回归三例实证）；代码层已加「入口判定语言 → RuntimeContext →
--   主 Agent 与 KB 合成统一追加约束」，本迁移消除静态模板里与之打架的两处固定中文要求：
--   1) KB_ANSWER 槽「简体中文，语气专业、稳重、友好」→ 跟随提问主要语言
--      （与本地校园槽 2026-10-09 已有条款同口径）
--   2) AGENT_MAIN 的「原样全文输出」补语言优先级：返回语言与本轮不一致时先完整转换再输出，
--      事实、数值、日期、条件与链接不变
-- 覆盖面：不按固定 agent_id 圈定，带目标串的 KB_ANSWER / AGENT_MAIN 行（激活、内置、
--   其他智能体自有槽）一并改写；不含目标串的行不动，避免覆盖语义已异的模板。
-- 幂等：REPLACE 找不到目标串时无改动，重放无害。
-- ⚠ 应用后必须清提示词缓存：DEL ragent:agent:resolved-prompts:v2
--   （AgentPromptCacheManager 按 agent 预解析缓存，直连 SQL 不触发刷新，同类已知点）。
-- 回滚：对调 REPLACE 两串后同样 DEL 缓存；改前原值另存于施现场备份文件。

UPDATE t_agent_prompt
SET content = REPLACE(content,
        '简体中文，语气专业、稳重、友好。',
        '以用户提问的主要语言作答：英文提问全英文作答，中文提问全中文作答；语气专业、稳重、友好。'),
    update_time = CURRENT_TIMESTAMP
WHERE slot_key = 'KB_ANSWER'
  AND deleted = 0
  AND content LIKE '%简体中文，语气专业、稳重、友好。%';

UPDATE t_agent_prompt
SET content = REPLACE(content,
        '用户明确要求换形式（翻译、缩短、只要表格等）时以用户要求为准',
        '用户明确要求换形式（翻译、缩短、只要表格等）时以用户要求为准；返回内容与本轮回答语言不一致时，先完整转换为本轮语言再输出，事实、数值、日期、条件与链接保持不变'),
    update_time = CURRENT_TIMESTAMP
WHERE slot_key = 'AGENT_MAIN'
  AND deleted = 0
  AND content LIKE '%用户明确要求换形式（翻译、缩短、只要表格等）时以用户要求为准%';

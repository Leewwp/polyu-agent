-- MCP 工具只由 Agent 调用，删除废弃的参数提取列和回答槽位
-- 〔09-24 部署口径注记〕随上游 fd58ec76 收编带入。代码已不读该列与两槽位（schema_pg.sql 基线已同步删），
-- 属幂等清理（IF EXISTS / DELETE 无匹配即空操作），不随发布强制执行；生产可在维护批择机手工跑（upgrades 一律手工，见 deploy/README）

ALTER TABLE t_intent_node
    DROP COLUMN IF EXISTS param_prompt_template;

DELETE FROM t_agent_prompt
WHERE slot_key IN ('MCP_ANSWER', 'MIXED_ANSWER');

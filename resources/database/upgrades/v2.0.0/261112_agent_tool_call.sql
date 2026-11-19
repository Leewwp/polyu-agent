-- v2.0.0 261112 Agent 工具调用审计表（#486 T1）
-- agent.audit.enabled 默认关：表先建，行由 flag 开启后的旁路 writer 写入；90 天保留清理随表启用
-- 全部语句可重复执行

CREATE TABLE IF NOT EXISTS t_agent_tool_call (
    id              VARCHAR(20)  NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(20)  NOT NULL,
    user_id         VARCHAR(20)  NOT NULL,
    role            VARCHAR(16),
    message_id      VARCHAR(20)  NOT NULL,
    block_index     SMALLINT     NOT NULL,
    run_id          VARCHAR(64),
    tool_name       VARCHAR(128) NOT NULL,
    tool_call_id    VARCHAR(128),
    batch_id        VARCHAR(64),
    decision        VARCHAR(16)  NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    input_digest    VARCHAR(64),
    input_excerpt   VARCHAR(256),
    duration_ms     BIGINT,
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_tool_call_message_block ON t_agent_tool_call (message_id, block_index);
CREATE INDEX IF NOT EXISTS idx_agent_tool_call_user_time ON t_agent_tool_call (user_id, create_time);
CREATE INDEX IF NOT EXISTS idx_agent_tool_call_name_time ON t_agent_tool_call (tool_name, create_time);
COMMENT ON TABLE t_agent_tool_call IS 'Agent 工具调用审计（90 天保留，agent.audit.enabled 默认关）';
COMMENT ON COLUMN t_agent_tool_call.decision IS 'ALLOW/MASKED/ERROR/RULE_DENY/ASK_DENIED/ASK_APPROVED/INTERRUPTED（T2 复用词汇）';
COMMENT ON COLUMN t_agent_tool_call.status IS '消息 tool 块 status 原词';
COMMENT ON COLUMN t_agent_tool_call.input_digest IS '入参 canonical JSON 的 SHA-256 hex';
COMMENT ON COLUMN t_agent_tool_call.input_excerpt IS '入参截断脱敏摘录，≤256 字符，禁全文';

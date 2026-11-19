-- v2.0.0 261112 Agent 工具权限矩阵表（#487 T2）
-- role×tool_id×decision 声明式矩阵，每 run 会话级编译进 AgentScope 权限引擎；
-- agent.tool-permission.enabled 默认关：表先建，flag 开启后 startRun/startConfirmRun 检查点才编译装载。
-- tool_id 支持通配 'mcp:*'（=当前挂载的全部 MCP 工具，编译期展开；其余取值按精确工具名匹配）。
-- 全部语句可重复执行

CREATE TABLE IF NOT EXISTS t_agent_tool_permission (
    id          VARCHAR(20)  NOT NULL PRIMARY KEY,
    role        VARCHAR(16)  NOT NULL,
    tool_id     VARCHAR(128) NOT NULL,
    decision    VARCHAR(8)   NOT NULL CHECK (decision IN ('ALLOW','ASK','DENY')),
    enabled     SMALLINT     NOT NULL DEFAULT 1,
    description VARCHAR(256),
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_tool_permission_role_tool ON t_agent_tool_permission (role, tool_id);
COMMENT ON TABLE t_agent_tool_permission IS 'Agent 工具权限矩阵（role admin/user/guest × tool_id × decision；agent.tool-permission.enabled 默认关）';
COMMENT ON COLUMN t_agent_tool_permission.role IS '角色：admin/user/guest（按字符串校验，不强依赖 UserRole 枚举）';
COMMENT ON COLUMN t_agent_tool_permission.tool_id IS 'MCP 工具 id；通配 mcp:* = 当前挂载的全部 MCP 工具（编译期展开）';
COMMENT ON COLUMN t_agent_tool_permission.decision IS 'ALLOW/ASK/DENY（引擎判定序 deny→ask→per-tool→allow）';
COMMENT ON COLUMN t_agent_tool_permission.enabled IS '1 生效 0 停用；停用行不进编译快照';

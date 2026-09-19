-- v2.0.0 260919 Agent 会话只读分享表（issue #82）
-- 幂等：CREATE TABLE IF NOT EXISTS + CREATE INDEX IF NOT EXISTS，重放无害；新装环境走基线 schema 同款 DDL。
-- 纯新增表，不改任何既有表；公开读只读本表（快照值复制，不回链 t_agent_conversation/t_agent_message）。
-- feature flag：agent.share.enabled 默认关（AGENT_SHARE_ENABLED），关闭时端点不装配、公开路径兜底统一「链接无效」。

CREATE TABLE IF NOT EXISTS t_agent_conversation_share (
    id                VARCHAR(20)    NOT NULL PRIMARY KEY,
    token             VARCHAR(64)    NOT NULL,
    owner_user_id     VARCHAR(20)    NOT NULL,
    conversation_id   VARCHAR(20)    NOT NULL,
    title             TEXT           NOT NULL,
    messages          JSONB          NOT NULL,
    lang              VARCHAR(8),
    content_version   VARCHAR(64),
    status            VARCHAR(16)    NOT NULL DEFAULT 'ACTIVE',
    expire_time       TIMESTAMP,
    revoked_time      TIMESTAMP,
    create_time       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted           SMALLINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_agent_conversation_share_token UNIQUE (token)
);
CREATE INDEX IF NOT EXISTS idx_agent_conversation_share_owner ON t_agent_conversation_share (owner_user_id, create_time);
COMMENT ON TABLE t_agent_conversation_share IS 'Agent 会话只读分享快照表（issue #82；不可变快照，token 加密随机不可枚举）';
COMMENT ON COLUMN t_agent_conversation_share.token IS 'SecureRandom 32 字节 Base64URL（43 字符）';
COMMENT ON COLUMN t_agent_conversation_share.owner_user_id IS '创建者用户ID（仅归属校验与治理用，公开载荷不返回）';
COMMENT ON COLUMN t_agent_conversation_share.conversation_id IS '源会话业务ID（仅撤销/我的列表溯源，不进公开载荷）';
COMMENT ON COLUMN t_agent_conversation_share.messages IS '白名单消息快照有序数组（role/content/createTime；blocks/thinking/ID/userId 一律排除）';
COMMENT ON COLUMN t_agent_conversation_share.content_version IS '内容/知识版本标记（agent.share.content-version）';
COMMENT ON COLUMN t_agent_conversation_share.status IS 'ACTIVE/REVOKED';

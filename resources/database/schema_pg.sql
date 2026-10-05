-- PostgreSQL Schema for Ragent
-- Converted from MySQL schema_table.sql

-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- ============================================
-- User & Conversation Tables
-- ============================================

CREATE TABLE t_user (
    id             VARCHAR(20)  NOT NULL PRIMARY KEY,
    username       VARCHAR(255) NOT NULL,
    password       VARCHAR(128) NOT NULL,
    role           VARCHAR(32)  NOT NULL,
    avatar         VARCHAR(128),
    email          VARCHAR(255),
    email_verified SMALLINT     DEFAULT 0,
    delete_time    TIMESTAMP,
    create_time    TIMESTAMP  DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP  DEFAULT CURRENT_TIMESTAMP,
    deleted        SMALLINT     DEFAULT 0,
    CONSTRAINT uk_user_username UNIQUE (username)
);
-- 注册用户 username=email（上限 254），活跃用户邮箱唯一（部分索引）：软删/硬删后同名邮箱可再注册
CREATE UNIQUE INDEX uk_user_email_active ON t_user (email) WHERE deleted = 0 AND email IS NOT NULL;
COMMENT ON TABLE t_user IS '系统用户表';
COMMENT ON COLUMN t_user.id IS '主键ID';
COMMENT ON COLUMN t_user.username IS '用户名，唯一（注册用户=邮箱，varchar64→255 见 upgrades/v2.0.0/260909）';
COMMENT ON COLUMN t_user.password IS '密码';
COMMENT ON COLUMN t_user.role IS '角色：admin/user';
COMMENT ON COLUMN t_user.avatar IS '用户头像';
COMMENT ON COLUMN t_user.email IS '注册邮箱（小写规范化），存量/管理员建/游客为 NULL';
COMMENT ON COLUMN t_user.email_verified IS '邮箱是否已验证 0：未验证 1：已验证';
COMMENT ON COLUMN t_user.delete_time IS '注销软删时间，NULL=正常；非 NULL=30 天可撤销期内';
COMMENT ON COLUMN t_user.create_time IS '创建时间';
COMMENT ON COLUMN t_user.update_time IS '更新时间';
COMMENT ON COLUMN t_user.deleted IS '是否删除 0：正常 1：删除';

-- 注销邮箱哈希墓碑（180 天回查用，不存明文；到期清理由保留期任务负责）
CREATE TABLE t_user_email_tombstone (
    id          VARCHAR(20)  NOT NULL PRIMARY KEY,
    email_hash  VARCHAR(64)  NOT NULL,
    user_id     VARCHAR(20)  NOT NULL,
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expire_time TIMESTAMP    NOT NULL,
    CONSTRAINT uk_email_tombstone_hash UNIQUE (email_hash)
);
COMMENT ON TABLE t_user_email_tombstone IS '注销邮箱哈希墓碑（防重复注册滥用回查）';
COMMENT ON COLUMN t_user_email_tombstone.email_hash IS 'SHA-256(email 小写) 十六进制';
COMMENT ON COLUMN t_user_email_tombstone.user_id IS '已删除用户原 ID（仅回查留痕）';
COMMENT ON COLUMN t_user_email_tombstone.expire_time IS '过期时间（create_time + 180 天）';

CREATE TABLE t_conversation (
    id              VARCHAR(20) NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(20) NOT NULL,
    user_id         VARCHAR(20) NOT NULL,
    title           VARCHAR(128) NOT NULL,
    last_time       TIMESTAMP,
    create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT    DEFAULT 0,
    CONSTRAINT uk_conversation_user UNIQUE (conversation_id, user_id)
);
CREATE INDEX idx_user_time ON t_conversation (user_id, last_time);
COMMENT ON TABLE t_conversation IS '会话列表';
COMMENT ON COLUMN t_conversation.id IS '主键ID';
COMMENT ON COLUMN t_conversation.conversation_id IS '会话ID';
COMMENT ON COLUMN t_conversation.user_id IS '用户ID';
COMMENT ON COLUMN t_conversation.title IS '会话名称';
COMMENT ON COLUMN t_conversation.last_time IS '最近消息时间';
COMMENT ON COLUMN t_conversation.create_time IS '创建时间';
COMMENT ON COLUMN t_conversation.update_time IS '更新时间';
COMMENT ON COLUMN t_conversation.deleted IS '是否删除 0：正常 1：删除';

CREATE TABLE t_conversation_summary (
    id              VARCHAR(20)      NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(20) NOT NULL,
    user_id         VARCHAR(20) NOT NULL,
    last_message_id VARCHAR(20) NOT NULL,
    content         TEXT        NOT NULL,
    create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT    DEFAULT 0
);
CREATE INDEX idx_conv_user ON t_conversation_summary (conversation_id, user_id);
COMMENT ON TABLE t_conversation_summary IS '会话摘要表（与消息表分离存储）';

CREATE TABLE t_message (
    id                VARCHAR(20)      NOT NULL PRIMARY KEY,
    conversation_id   VARCHAR(20) NOT NULL,
    user_id           VARCHAR(20) NOT NULL,
    role              VARCHAR(16) NOT NULL,
    content           TEXT        NOT NULL,
    thinking_content  TEXT,
    thinking_duration INTEGER,
    sources              JSONB,
    recommended_questions JSONB,
    retrieved_chunks  JSONB,
    reply_to_message_id VARCHAR(20),
    message_status    VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    create_time       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted           SMALLINT    DEFAULT 0
);
CREATE INDEX idx_conversation_user_time ON t_message (conversation_id, user_id, create_time);
CREATE INDEX idx_conversation_summary ON t_message (conversation_id, user_id, create_time);
COMMENT ON TABLE t_message IS '会话消息记录表';

CREATE TABLE t_message_feedback (
    id              VARCHAR(20)       NOT NULL PRIMARY KEY,
    message_id      VARCHAR(20)       NOT NULL,
    conversation_id VARCHAR(20)  NOT NULL,
    user_id         VARCHAR(20)  NOT NULL,
    vote            SMALLINT     NOT NULL,
    reason          VARCHAR(255),
    comment         VARCHAR(1024),
    create_time     TIMESTAMP  NOT NULL,
    update_time     TIMESTAMP  NOT NULL,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_msg_user UNIQUE (message_id, user_id)
);
CREATE INDEX idx_conversation_id ON t_message_feedback (conversation_id);
CREATE INDEX idx_user_id ON t_message_feedback (user_id);
COMMENT ON TABLE t_message_feedback IS '会话消息反馈表';

CREATE TABLE t_sample_question (
    id          VARCHAR(20)        NOT NULL PRIMARY KEY,
    title       VARCHAR(64),
    description VARCHAR(255),
    question    VARCHAR(255) NOT NULL,
    lang        VARCHAR(8)   NOT NULL DEFAULT 'zh',
    create_time TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT      DEFAULT 0
);
CREATE INDEX idx_sample_question_deleted ON t_sample_question (deleted);
COMMENT ON TABLE t_sample_question IS '示例问题表';
COMMENT ON COLUMN t_sample_question.lang IS '语言 zh / en（T20 每语言一行，抽样按语言限定，无命中回落全量）';

-- ============================================
-- Business Change Audit Tables
-- ============================================

CREATE TABLE t_biz_change_log (
    id               VARCHAR(20)  NOT NULL PRIMARY KEY,
    biz_type         VARCHAR(64)  NOT NULL,
    biz_id           VARCHAR(64)  NOT NULL,
    operation_type   VARCHAR(32)  NOT NULL,
    action_desc      VARCHAR(512),
    before_snapshot  JSONB,
    after_snapshot   JSONB,
    change_diff      JSONB,
    operator_id      VARCHAR(64),
    operator_name    VARCHAR(128),
    operator_role    VARCHAR(64),
    success          BOOLEAN      NOT NULL DEFAULT TRUE,
    error_message    TEXT,
    class_name       VARCHAR(255),
    method_name      VARCHAR(255),
    ip               VARCHAR(64),
    user_agent       VARCHAR(512),
    create_time      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_biz_change_log_biz ON t_biz_change_log (biz_type, biz_id);
CREATE INDEX idx_biz_change_log_time ON t_biz_change_log (create_time);
CREATE INDEX idx_biz_change_log_operator ON t_biz_change_log (operator_id);
COMMENT ON TABLE t_biz_change_log IS '业务数据变更审计日志表';
COMMENT ON COLUMN t_biz_change_log.biz_type IS '业务对象类型';
COMMENT ON COLUMN t_biz_change_log.biz_id IS '业务对象主键';
COMMENT ON COLUMN t_biz_change_log.operation_type IS '操作类型';
COMMENT ON COLUMN t_biz_change_log.action_desc IS '操作描述';
COMMENT ON COLUMN t_biz_change_log.before_snapshot IS '变更前快照';
COMMENT ON COLUMN t_biz_change_log.after_snapshot IS '变更后快照';
COMMENT ON COLUMN t_biz_change_log.change_diff IS '变更差异';
COMMENT ON COLUMN t_biz_change_log.operator_id IS '操作人ID';
COMMENT ON COLUMN t_biz_change_log.operator_name IS '操作人名称';
COMMENT ON COLUMN t_biz_change_log.operator_role IS '操作人角色';
COMMENT ON COLUMN t_biz_change_log.success IS '是否成功';
COMMENT ON COLUMN t_biz_change_log.error_message IS '失败信息';
COMMENT ON COLUMN t_biz_change_log.class_name IS '触发类名';
COMMENT ON COLUMN t_biz_change_log.method_name IS '触发方法名';
COMMENT ON COLUMN t_biz_change_log.ip IS '来源IP';
COMMENT ON COLUMN t_biz_change_log.user_agent IS 'User-Agent';
COMMENT ON COLUMN t_biz_change_log.create_time IS '创建时间';

-- ============================================
-- Knowledge Base Tables
-- ============================================

CREATE TABLE t_knowledge_base (
    id              VARCHAR(20)       NOT NULL PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    embedding_model VARCHAR(64)  NOT NULL,
    collection_name VARCHAR(64) NOT NULL,
    created_by      VARCHAR(255) NOT NULL,
    updated_by      VARCHAR(255),
    create_time     TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_collection_name UNIQUE (collection_name)
);
CREATE INDEX idx_kb_name ON t_knowledge_base (name);
COMMENT ON TABLE t_knowledge_base IS '知识库表';

CREATE TABLE t_knowledge_document (
    id               VARCHAR(20)        NOT NULL PRIMARY KEY,
    kb_id            VARCHAR(20)        NOT NULL,
    doc_name         VARCHAR(256)  NOT NULL,
    enabled          SMALLINT      NOT NULL DEFAULT 1,
    chunk_count      INTEGER       DEFAULT 0,
    file_url         VARCHAR(1024) NOT NULL,
    file_type        VARCHAR(16)   NOT NULL,
    mime_type        VARCHAR(128),
    file_size        BIGINT,
    process_mode     VARCHAR(16)   DEFAULT 'chunk',
    status           VARCHAR(16)   NOT NULL DEFAULT 'pending',
    source_type      VARCHAR(16),
    source_location  VARCHAR(1024),
    schedule_enabled SMALLINT,
    schedule_cron    VARCHAR(64),
    ingestion_spec   JSONB,
    pipeline_id      VARCHAR(20),
    created_by       VARCHAR(255) NOT NULL,
    updated_by       VARCHAR(255),
    create_time      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted          SMALLINT      NOT NULL DEFAULT 0
);
CREATE INDEX idx_kb_id ON t_knowledge_document (kb_id);
COMMENT ON TABLE t_knowledge_document IS '知识库文档表';

CREATE TABLE t_knowledge_chunk (
    id             VARCHAR(20)      NOT NULL PRIMARY KEY,
    kb_id          VARCHAR(20)      NOT NULL,
    doc_id         VARCHAR(20)      NOT NULL,
    chunk_index    INTEGER     NOT NULL,
    content        TEXT        NOT NULL,
    content_hash   VARCHAR(64),
    char_count     INTEGER,
    token_count    INTEGER,
    embedding_text TEXT,
    enabled        SMALLINT    NOT NULL DEFAULT 1,
    created_by     VARCHAR(255) NOT NULL,
    updated_by     VARCHAR(255),
    create_time    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted        SMALLINT    NOT NULL DEFAULT 0
);
CREATE INDEX idx_doc_id ON t_knowledge_chunk (doc_id);
COMMENT ON TABLE t_knowledge_chunk IS '知识库文档分块表';

CREATE TABLE t_knowledge_document_chunk_log (
    id                 VARCHAR(20)      NOT NULL PRIMARY KEY,
    doc_id             VARCHAR(20)      NOT NULL,
    status             VARCHAR(16)      NOT NULL,
    process_mode       VARCHAR(16),
    parse_profile      VARCHAR(16),
    pipeline_id        VARCHAR(20),
    extract_duration   BIGINT,
    chunk_duration     BIGINT,
    embed_duration     BIGINT,
    persist_duration   BIGINT,
    total_duration     BIGINT,
    chunk_count        INTEGER,
    error_message      TEXT,
    start_time         TIMESTAMP,
    end_time           TIMESTAMP,
    create_time        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_doc_id_log ON t_knowledge_document_chunk_log (doc_id);
COMMENT ON TABLE t_knowledge_document_chunk_log IS '知识库文档分块日志表';

CREATE TABLE t_knowledge_document_schedule (
    id                VARCHAR(20)       NOT NULL PRIMARY KEY,
    doc_id            VARCHAR(20)       NOT NULL,
    kb_id             VARCHAR(20)       NOT NULL,
    cron_expr         VARCHAR(64),
    enabled           SMALLINT     DEFAULT 0,
    next_run_time     TIMESTAMP,
    last_run_time     TIMESTAMP,
    last_success_time TIMESTAMP,
    last_status       VARCHAR(16),
    last_error        VARCHAR(512),
    last_etag         VARCHAR(256),
    last_modified     VARCHAR(256),
    last_content_hash VARCHAR(128),
    consecutive_failures INT      DEFAULT 0,
    data_stale        SMALLINT   DEFAULT 0,
    lock_owner        VARCHAR(128),
    lock_until        TIMESTAMP,
    create_time       TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_doc_id UNIQUE (doc_id)
);
CREATE INDEX idx_next_run ON t_knowledge_document_schedule (next_run_time);
CREATE INDEX idx_lock_until ON t_knowledge_document_schedule (lock_until);
COMMENT ON TABLE t_knowledge_document_schedule IS '知识库文档定时刷新任务表';

CREATE TABLE t_knowledge_document_schedule_exec (
    id            VARCHAR(20)       NOT NULL PRIMARY KEY,
    schedule_id   VARCHAR(20)       NOT NULL,
    doc_id        VARCHAR(20)       NOT NULL,
    kb_id         VARCHAR(20)       NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    message       VARCHAR(512),
    start_time    TIMESTAMP,
    end_time      TIMESTAMP,
    file_name     VARCHAR(512),
    file_size     BIGINT,
    content_hash  VARCHAR(128),
    etag          VARCHAR(256),
    last_modified VARCHAR(256),
    create_time   TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_schedule_time ON t_knowledge_document_schedule_exec (schedule_id, start_time);
CREATE INDEX idx_doc_id_exec ON t_knowledge_document_schedule_exec (doc_id);
COMMENT ON TABLE t_knowledge_document_schedule_exec IS '知识库文档定时刷新执行记录';

-- ============================================
-- RAG Intent & Query Tables
-- ============================================

CREATE TABLE t_intent_node (
    id                    VARCHAR(20)       NOT NULL PRIMARY KEY,
    kb_id                 VARCHAR(20),
    intent_code           VARCHAR(64)  NOT NULL,
    name                  VARCHAR(64)  NOT NULL,
    level                 SMALLINT     NOT NULL,
    parent_code           VARCHAR(64),
    description           VARCHAR(512),
    examples              TEXT,
    collection_name       VARCHAR(128),
    collection_names      JSONB        NOT NULL DEFAULT '[]'::jsonb,
    top_k                 INTEGER,
    mcp_tool_id           VARCHAR(128),
    require_confirm       SMALLINT     NOT NULL DEFAULT 0,
    kind                  SMALLINT     NOT NULL DEFAULT 0,
    prompt_snippet        TEXT,
    prompt_template       TEXT,
    sort_order            INTEGER      NOT NULL DEFAULT 0,
    enabled               SMALLINT     NOT NULL DEFAULT 1,
    create_by             VARCHAR(255),
    update_by             VARCHAR(255),
    create_time           TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time           TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted               SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE t_intent_node IS '意图树节点配置表';

CREATE TABLE t_query_term_mapping (
    id          VARCHAR(20)       NOT NULL PRIMARY KEY,
    domain      VARCHAR(64),
    source_term VARCHAR(128) NOT NULL,
    target_term VARCHAR(128) NOT NULL,
    match_type  SMALLINT     NOT NULL DEFAULT 1,
    priority    INTEGER      NOT NULL DEFAULT 100,
    enabled     SMALLINT     NOT NULL DEFAULT 1,
    remark      VARCHAR(255),
    create_by   VARCHAR(255),
    update_by   VARCHAR(255),
    create_time TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT     NOT NULL DEFAULT 0
);
CREATE INDEX idx_domain ON t_query_term_mapping (domain);
CREATE INDEX idx_source ON t_query_term_mapping (source_term);
COMMENT ON TABLE t_query_term_mapping IS '关键词归一化映射表';

CREATE TABLE t_rag_trace_run (
    id              VARCHAR(20)           NOT NULL PRIMARY KEY,
    trace_id        VARCHAR(64)      NOT NULL,
    trace_name      VARCHAR(128),
    entry_method    VARCHAR(256),
    conversation_id VARCHAR(20),
    task_id         VARCHAR(20),
    user_id         VARCHAR(20),
    status          VARCHAR(16)      NOT NULL DEFAULT 'RUNNING',
    error_message   VARCHAR(1000),
    start_time      TIMESTAMP(3),
    end_time        TIMESTAMP(3),
    duration_ms     BIGINT,
    extra_data      TEXT,
    create_time     TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT         DEFAULT 0,
    CONSTRAINT uk_run_id UNIQUE (trace_id)
);
CREATE INDEX idx_task_id ON t_rag_trace_run (task_id);
CREATE INDEX idx_user_id_trace ON t_rag_trace_run (user_id);
COMMENT ON TABLE t_rag_trace_run IS 'Trace 运行记录表';

CREATE TABLE t_rag_trace_node (
    id             VARCHAR(20)           NOT NULL PRIMARY KEY,
    trace_id       VARCHAR(20)      NOT NULL,
    node_id        VARCHAR(20)      NOT NULL,
    parent_node_id VARCHAR(20),
    depth          INTEGER          DEFAULT 0,
    node_type      VARCHAR(16),
    node_name      VARCHAR(128),
    class_name     VARCHAR(256),
    method_name    VARCHAR(128),
    status         VARCHAR(16)      NOT NULL DEFAULT 'RUNNING',
    error_message  VARCHAR(1000),
    start_time     TIMESTAMP(3),
    end_time       TIMESTAMP(3),
    duration_ms    BIGINT,
    extra_data     TEXT,
    create_time    TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,
    deleted        SMALLINT         DEFAULT 0,
    CONSTRAINT uk_run_node UNIQUE (trace_id, node_id)
);
COMMENT ON TABLE t_rag_trace_node IS 'Trace 节点记录表';

-- ============================================
-- Agent Profile Tables
-- ============================================

CREATE TABLE t_agent_profile (
    id          VARCHAR(20)  NOT NULL PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(512),
    avatar      VARCHAR(32),
    builtin     SMALLINT     NOT NULL DEFAULT 0,
    active      SMALLINT     NOT NULL DEFAULT 0,
    create_by   VARCHAR(255),
    update_by   VARCHAR(255),
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_agent_name UNIQUE (name)
);
CREATE INDEX idx_agent_active ON t_agent_profile (active);
COMMENT ON TABLE t_agent_profile IS '智能体人设配置表';

CREATE TABLE t_agent_prompt (
    id          VARCHAR(20)  NOT NULL PRIMARY KEY,
    agent_id    VARCHAR(20)  NOT NULL,
    slot_key    VARCHAR(64)  NOT NULL,
    content     TEXT,
    create_by   VARCHAR(255),
    update_by   VARCHAR(255),
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_agent_slot UNIQUE (agent_id, slot_key)
);
CREATE INDEX idx_agent_prompt_agent ON t_agent_prompt (agent_id);
COMMENT ON TABLE t_agent_prompt IS '智能体提示词槽位表';

CREATE TABLE t_agent_skill (
    id          VARCHAR(20)  NOT NULL PRIMARY KEY,
    skill_code  VARCHAR(64)  NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(512) NOT NULL,
    content     TEXT         NOT NULL,
    tool_ids    JSONB        NOT NULL DEFAULT '[]'::jsonb,
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    enabled     SMALLINT     NOT NULL DEFAULT 1,
    create_by   VARCHAR(255),
    update_by   VARCHAR(255),
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT     NOT NULL DEFAULT 0
);
-- 逻辑删除后 skill_code 应可重新占用，唯一性只约束未删除的行
CREATE UNIQUE INDEX uk_agent_skill_code ON t_agent_skill (skill_code) WHERE deleted = 0;
CREATE INDEX idx_agent_skill_enabled ON t_agent_skill (enabled, deleted);
COMMENT ON TABLE t_agent_skill IS '智能体技能表';

-- ============================================
-- Agent Engine Tables (v2 ReAct，与 workflow 会话两套分立)
-- ============================================

CREATE TABLE t_agent_conversation (
    id              VARCHAR(20) NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(20) NOT NULL,
    user_id         VARCHAR(20) NOT NULL,
    title           VARCHAR(128) NOT NULL,
    last_time       TIMESTAMP,
    create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT    DEFAULT 0
);
-- 会话身份不可复用：逻辑删除后仍保留唯一键，新会话必须使用服务端生成的新 ID
CREATE UNIQUE INDEX uk_agent_conversation_user ON t_agent_conversation (conversation_id, user_id);
CREATE INDEX idx_agent_conv_user_time ON t_agent_conversation (user_id, last_time);
COMMENT ON TABLE t_agent_conversation IS 'Agent 会话列表';

CREATE TABLE t_agent_message (
    id                  VARCHAR(20) NOT NULL PRIMARY KEY,
    conversation_id     VARCHAR(20) NOT NULL,
    user_id             VARCHAR(20) NOT NULL,
    role                VARCHAR(16) NOT NULL,
    content             TEXT,
    thinking_content    TEXT,
    blocks              JSONB,
    reply_to_message_id VARCHAR(20),
    message_status      VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
    duration_ms         BIGINT,
    create_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted             SMALLINT    DEFAULT 0
);
CREATE INDEX idx_agent_msg_conv ON t_agent_message (conversation_id, user_id, create_time);
-- 长期记忆按用户跨会话取待处理消息，按 id 升序
CREATE INDEX idx_agent_msg_user ON t_agent_message (user_id, id);
COMMENT ON TABLE t_agent_message IS 'Agent 消息记录';

CREATE TABLE t_agent_state (
    user_id     VARCHAR(64) NOT NULL,
    session_id  VARCHAR(64) NOT NULL,
    state_key   VARCHAR(64) NOT NULL,
    payload     JSONB,
    create_time TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, session_id, state_key)
);
COMMENT ON TABLE t_agent_state IS 'AgentScope状态存储';

CREATE TABLE t_agent_context_compaction (
    id                   VARCHAR(20) NOT NULL PRIMARY KEY,
    user_id              VARCHAR(20) NOT NULL,
    conversation_id      VARCHAR(20) NOT NULL,
    generation           INTEGER     NOT NULL,
    summary              TEXT,
    material_msg_count   INTEGER     NOT NULL,
    material_chars       INTEGER     NOT NULL,
    summary_chars        INTEGER     NOT NULL,
    context_chars_before INTEGER     NOT NULL,
    context_chars_after  INTEGER     NOT NULL,
    create_time          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_agent_compaction_conv ON t_agent_context_compaction (conversation_id, user_id, create_time);
COMMENT ON TABLE t_agent_context_compaction IS 'Agent 上下文压缩事件，追加型审计日志，应用侧无读路径';

CREATE TABLE t_agent_memory (
    id            VARCHAR(20)  NOT NULL PRIMARY KEY,
    user_id       VARCHAR(20)  NOT NULL,
    content       VARCHAR(500) NOT NULL,
    source_type   VARCHAR(16)  NOT NULL,
    invalid_at    TIMESTAMP,
    superseded_by VARCHAR(20),
    create_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- 部分索引：读路径只查 ACTIVE，失效行不进索引
CREATE INDEX idx_agent_memory_active ON t_agent_memory (user_id) WHERE invalid_at IS NULL;
COMMENT ON TABLE t_agent_memory IS 'Agent长期记忆事实表';

CREATE TABLE t_agent_memory_extraction (
    id                      VARCHAR(20) NOT NULL PRIMARY KEY,
    user_id                 VARCHAR(20) NOT NULL,
    conversation_id         VARCHAR(20) NOT NULL,
    from_message_id         VARCHAR(20) NOT NULL,
    to_message_id           VARCHAR(20) NOT NULL,
    -- VARCHAR(32)：PENDING_APPROVAL 恰 16 字符顶满旧宽，状态机加宽防再犯（261005 迁移同步加宽存量库）
    status                  VARCHAR(32) NOT NULL,
    trigger_type            VARCHAR(16) NOT NULL,
    decision_count          INTEGER     NOT NULL DEFAULT 0,
    attempt_count           INTEGER     NOT NULL DEFAULT 1,
    plan_json               TEXT,
    plan_expires_at         TIMESTAMP,
    expected_revision       BIGINT,
    plan_tool_call_id       VARCHAR(64),
    plan_confirm_message_id VARCHAR(20),
    plan_result_json        TEXT,
    create_time             TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    settle_time             TIMESTAMP
);
CREATE INDEX idx_agent_memory_extraction_user ON t_agent_memory_extraction (user_id, to_message_id);
-- 部分唯一索引即分布式 claim：同一用户同时只允许一次在飞抽取，记忆只有一份，消费顺序必须是说话先后
CREATE UNIQUE INDEX uk_agent_memory_extraction_processing
    ON t_agent_memory_extraction (user_id) WHERE status = 'PROCESSING';
-- 审批互斥面：同一用户同时至多一个待审（或已批未执行）计划，见 261005_agent_memory_hitl_approval.sql
CREATE UNIQUE INDEX uk_agent_memory_extraction_plan_pending
    ON t_agent_memory_extraction (user_id) WHERE status IN ('PENDING_APPROVAL', 'APPROVED');
COMMENT ON TABLE t_agent_memory_extraction IS 'Agent长期记忆抽取台账';

CREATE TABLE t_agent_memory_control (
    user_id     VARCHAR(20) NOT NULL PRIMARY KEY,
    revision    BIGINT      NOT NULL DEFAULT 0,
    create_time TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE t_agent_memory_control IS 'Agent长期记忆控制面';

-- ============================================
-- Ingestion Pipeline Tables
-- ============================================

CREATE TABLE t_ingestion_pipeline (
    id          VARCHAR(20)      NOT NULL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    created_by  VARCHAR(255) DEFAULT '',
    updated_by  VARCHAR(255) DEFAULT '',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT    NOT NULL DEFAULT 0,
    CONSTRAINT uk_ingestion_pipeline_name UNIQUE (name, deleted)
);
COMMENT ON TABLE t_ingestion_pipeline IS '摄取流水线表';

CREATE TABLE t_ingestion_pipeline_node (
    id             VARCHAR(20)      NOT NULL PRIMARY KEY,
    pipeline_id    VARCHAR(20)      NOT NULL,
    node_id        VARCHAR(20) NOT NULL,
    node_type      VARCHAR(16) NOT NULL,
    next_node_id   VARCHAR(20),
    settings_json  JSONB,
    condition_json JSONB,
    created_by     VARCHAR(255) DEFAULT '',
    updated_by     VARCHAR(255) DEFAULT '',
    create_time    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted        SMALLINT    NOT NULL DEFAULT 0,
    CONSTRAINT uk_ingestion_pipeline_node UNIQUE (pipeline_id, node_id, deleted)
);
CREATE INDEX idx_ingestion_pipeline_node_pipeline ON t_ingestion_pipeline_node (pipeline_id);
COMMENT ON TABLE t_ingestion_pipeline_node IS '摄取流水线节点表';

CREATE TABLE t_ingestion_task (
    id               VARCHAR(20)      NOT NULL PRIMARY KEY,
    pipeline_id      VARCHAR(20)      NOT NULL,
    source_type      VARCHAR(20) NOT NULL,
    source_location  TEXT,
    source_file_name VARCHAR(255),
    status           VARCHAR(16) NOT NULL,
    chunk_count      INTEGER     DEFAULT 0,
    error_message    TEXT,
    logs_json        JSONB,
    metadata_json    JSONB,
    started_at       TIMESTAMP,
    completed_at     TIMESTAMP,
    created_by       VARCHAR(255) DEFAULT '',
    updated_by       VARCHAR(255) DEFAULT '',
    create_time      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted          SMALLINT    NOT NULL DEFAULT 0
);
CREATE INDEX idx_ingestion_task_pipeline ON t_ingestion_task (pipeline_id);
CREATE INDEX idx_ingestion_task_status ON t_ingestion_task (status);
COMMENT ON TABLE t_ingestion_task IS '摄取任务表';

CREATE TABLE t_ingestion_task_node (
    id            VARCHAR(20)      NOT NULL PRIMARY KEY,
    task_id       VARCHAR(20)      NOT NULL,
    pipeline_id   VARCHAR(20)      NOT NULL,
    node_id       VARCHAR(20) NOT NULL,
    node_type     VARCHAR(16) NOT NULL,
    node_order    INTEGER     NOT NULL DEFAULT 0,
    status        VARCHAR(16) NOT NULL,
    duration_ms   BIGINT      NOT NULL DEFAULT 0,
    message       TEXT,
    error_message TEXT,
    output_json   TEXT,
    create_time   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       SMALLINT    NOT NULL DEFAULT 0
);
CREATE INDEX idx_ingestion_task_node_task ON t_ingestion_task_node (task_id);
CREATE INDEX idx_ingestion_task_node_pipeline ON t_ingestion_task_node (pipeline_id);
CREATE INDEX idx_ingestion_task_node_status ON t_ingestion_task_node (status);
COMMENT ON TABLE t_ingestion_task_node IS '摄取任务节点表';

-- ============================================
-- Vector Storage Table (pgvector)
-- ============================================

CREATE TABLE t_knowledge_vector (
    id              VARCHAR(20) PRIMARY KEY,
    collection_name VARCHAR(64) NOT NULL,
    content         TEXT,
    metadata        JSONB,
    embedding       vector(1536)
);

CREATE INDEX idx_kv_collection_name ON t_knowledge_vector (collection_name);
CREATE INDEX idx_kv_metadata ON t_knowledge_vector USING gin(metadata);
CREATE INDEX idx_kv_embedding ON t_knowledge_vector USING hnsw (embedding vector_cosine_ops);
COMMENT ON TABLE t_knowledge_vector IS '知识库向量存储表';
COMMENT ON COLUMN t_knowledge_vector.id IS '分块ID';
COMMENT ON COLUMN t_knowledge_vector.collection_name IS '知识库Collection';
COMMENT ON COLUMN t_knowledge_vector.content IS '分块文本内容';
COMMENT ON COLUMN t_knowledge_vector.metadata IS '元数据';
COMMENT ON COLUMN t_knowledge_vector.embedding IS '向量';

-- ============================================
-- Column Comments
-- ============================================

-- t_conversation_summary
COMMENT ON COLUMN t_conversation_summary.id IS '主键ID';
COMMENT ON COLUMN t_conversation_summary.conversation_id IS '会话ID';
COMMENT ON COLUMN t_conversation_summary.user_id IS '用户ID';
COMMENT ON COLUMN t_conversation_summary.last_message_id IS '摘要最后消息ID';
COMMENT ON COLUMN t_conversation_summary.content IS '会话摘要内容';
COMMENT ON COLUMN t_conversation_summary.create_time IS '创建时间';
COMMENT ON COLUMN t_conversation_summary.update_time IS '更新时间';
COMMENT ON COLUMN t_conversation_summary.deleted IS '是否删除 0：正常 1：删除';

-- t_message
COMMENT ON COLUMN t_message.id IS '主键ID';
COMMENT ON COLUMN t_message.conversation_id IS '会话ID';
COMMENT ON COLUMN t_message.user_id IS '用户ID';
COMMENT ON COLUMN t_message.role IS '角色：user/assistant';
COMMENT ON COLUMN t_message.content IS '消息内容';
COMMENT ON COLUMN t_message.thinking_content IS '深度思考内容';
COMMENT ON COLUMN t_message.thinking_duration IS '深度思考耗时（秒）';
COMMENT ON COLUMN t_message.sources IS '回答来源';
COMMENT ON COLUMN t_message.recommended_questions IS '推荐追问问题';
COMMENT ON COLUMN t_message.retrieved_chunks IS '推荐问题 grounding 片段';
COMMENT ON COLUMN t_message.reply_to_message_id IS '当前助手消息对应的用户消息ID';
COMMENT ON COLUMN t_message.message_status IS '消息结束状态：NORMAL=正常完成，INTERRUPTED=用户中断，REJECTED=限流拒绝';
COMMENT ON COLUMN t_message.create_time IS '创建时间';
COMMENT ON COLUMN t_message.update_time IS '更新时间';
COMMENT ON COLUMN t_message.deleted IS '是否删除 0：正常 1：删除';

-- t_message_feedback
COMMENT ON COLUMN t_message_feedback.id IS '主键ID';
COMMENT ON COLUMN t_message_feedback.message_id IS '消息ID';
COMMENT ON COLUMN t_message_feedback.conversation_id IS '会话ID';
COMMENT ON COLUMN t_message_feedback.user_id IS '用户ID';
COMMENT ON COLUMN t_message_feedback.vote IS '投票 1：赞 -1：踩';
COMMENT ON COLUMN t_message_feedback.reason IS '反馈原因';
COMMENT ON COLUMN t_message_feedback.comment IS '反馈评论';
COMMENT ON COLUMN t_message_feedback.create_time IS '创建时间';
COMMENT ON COLUMN t_message_feedback.update_time IS '更新时间';
COMMENT ON COLUMN t_message_feedback.deleted IS '是否删除 0：正常 1：删除';

-- t_sample_question
COMMENT ON COLUMN t_sample_question.id IS 'ID';
COMMENT ON COLUMN t_sample_question.title IS '展示标题';
COMMENT ON COLUMN t_sample_question.description IS '描述或提示';
COMMENT ON COLUMN t_sample_question.question IS '示例问题内容';
COMMENT ON COLUMN t_sample_question.create_time IS '创建时间';
COMMENT ON COLUMN t_sample_question.update_time IS '更新时间';
COMMENT ON COLUMN t_sample_question.deleted IS '是否删除 0：正常 1：删除';

-- t_knowledge_base
COMMENT ON COLUMN t_knowledge_base.id IS '主键 ID';
COMMENT ON COLUMN t_knowledge_base.name IS '知识库名称';
COMMENT ON COLUMN t_knowledge_base.embedding_model IS '嵌入模型标识';
COMMENT ON COLUMN t_knowledge_base.collection_name IS 'Collection名称';
COMMENT ON COLUMN t_knowledge_base.created_by IS '创建人';
COMMENT ON COLUMN t_knowledge_base.updated_by IS '修改人';
COMMENT ON COLUMN t_knowledge_base.create_time IS '创建时间';
COMMENT ON COLUMN t_knowledge_base.update_time IS '更新时间';
COMMENT ON COLUMN t_knowledge_base.deleted IS '是否删除 0：正常 1：删除';

-- t_knowledge_document
COMMENT ON COLUMN t_knowledge_document.id IS 'ID';
COMMENT ON COLUMN t_knowledge_document.kb_id IS '知识库ID';
COMMENT ON COLUMN t_knowledge_document.doc_name IS '文档名称';
COMMENT ON COLUMN t_knowledge_document.enabled IS '是否启用 1：启用 0：禁用';
COMMENT ON COLUMN t_knowledge_document.chunk_count IS '分块数量';
COMMENT ON COLUMN t_knowledge_document.file_url IS '文件存储路径';
COMMENT ON COLUMN t_knowledge_document.file_type IS '文件类型';
COMMENT ON COLUMN t_knowledge_document.mime_type IS '真实MIME类型';
COMMENT ON COLUMN t_knowledge_document.file_size IS '文件大小（字节）';
COMMENT ON COLUMN t_knowledge_document.process_mode IS '处理模式：chunk/pipeline';
COMMENT ON COLUMN t_knowledge_document.status IS '状态：pending/running/success/failed';
COMMENT ON COLUMN t_knowledge_document.source_type IS '来源类型：file/url';
COMMENT ON COLUMN t_knowledge_document.source_location IS '来源地址';
COMMENT ON COLUMN t_knowledge_document.schedule_enabled IS '是否启用定时刷新';
COMMENT ON COLUMN t_knowledge_document.schedule_cron IS '定时表达式';
COMMENT ON COLUMN t_knowledge_document.ingestion_spec IS '文档级摄取配置：解析档位 + 分块预算';
COMMENT ON COLUMN t_knowledge_document.pipeline_id IS 'Pipeline ID';
COMMENT ON COLUMN t_knowledge_document.created_by IS '创建人';
COMMENT ON COLUMN t_knowledge_document.updated_by IS '修改人';
COMMENT ON COLUMN t_knowledge_document.create_time IS '创建时间';
COMMENT ON COLUMN t_knowledge_document.update_time IS '更新时间';
COMMENT ON COLUMN t_knowledge_document.deleted IS '是否删除 0：正常 1：删除';

-- t_knowledge_chunk
COMMENT ON COLUMN t_knowledge_chunk.id IS 'ID';
COMMENT ON COLUMN t_knowledge_chunk.kb_id IS '知识库ID';
COMMENT ON COLUMN t_knowledge_chunk.doc_id IS '文档ID';
COMMENT ON COLUMN t_knowledge_chunk.chunk_index IS '分块序号';
COMMENT ON COLUMN t_knowledge_chunk.content IS '分块内容';
COMMENT ON COLUMN t_knowledge_chunk.content_hash IS '内容哈希';
COMMENT ON COLUMN t_knowledge_chunk.char_count IS '字符数';
COMMENT ON COLUMN t_knowledge_chunk.token_count IS 'Token数';
COMMENT ON COLUMN t_knowledge_chunk.embedding_text IS '向量文本';
COMMENT ON COLUMN t_knowledge_chunk.enabled IS '是否启用';
COMMENT ON COLUMN t_knowledge_chunk.created_by IS '创建人';
COMMENT ON COLUMN t_knowledge_chunk.updated_by IS '修改人';
COMMENT ON COLUMN t_knowledge_chunk.create_time IS '创建时间';
COMMENT ON COLUMN t_knowledge_chunk.update_time IS '更新时间';
COMMENT ON COLUMN t_knowledge_chunk.deleted IS '是否删除 0：正常 1：删除';

-- t_knowledge_document_chunk_log
COMMENT ON COLUMN t_knowledge_document_chunk_log.id IS 'ID';
COMMENT ON COLUMN t_knowledge_document_chunk_log.doc_id IS '文档ID';
COMMENT ON COLUMN t_knowledge_document_chunk_log.status IS '状态';
COMMENT ON COLUMN t_knowledge_document_chunk_log.process_mode IS '处理模式';
COMMENT ON COLUMN t_knowledge_document_chunk_log.parse_profile IS '解析档位';
COMMENT ON COLUMN t_knowledge_document_chunk_log.pipeline_id IS 'Pipeline ID';
COMMENT ON COLUMN t_knowledge_document_chunk_log.extract_duration IS '提取耗时（毫秒）';
COMMENT ON COLUMN t_knowledge_document_chunk_log.chunk_duration IS '分块耗时（毫秒）';
COMMENT ON COLUMN t_knowledge_document_chunk_log.embed_duration IS '向量化耗时（毫秒）';
COMMENT ON COLUMN t_knowledge_document_chunk_log.persist_duration IS 'DB持久化耗时（毫秒）';
COMMENT ON COLUMN t_knowledge_document_chunk_log.total_duration IS '总耗时（毫秒）';
COMMENT ON COLUMN t_knowledge_document_chunk_log.chunk_count IS '分块数量';
COMMENT ON COLUMN t_knowledge_document_chunk_log.error_message IS '错误信息';
COMMENT ON COLUMN t_knowledge_document_chunk_log.start_time IS '开始时间';
COMMENT ON COLUMN t_knowledge_document_chunk_log.end_time IS '结束时间';
COMMENT ON COLUMN t_knowledge_document_chunk_log.create_time IS '创建时间';
COMMENT ON COLUMN t_knowledge_document_chunk_log.update_time IS '更新时间';

-- t_knowledge_document_schedule
COMMENT ON COLUMN t_knowledge_document_schedule.id IS 'ID';
COMMENT ON COLUMN t_knowledge_document_schedule.doc_id IS '文档ID';
COMMENT ON COLUMN t_knowledge_document_schedule.kb_id IS '知识库ID';
COMMENT ON COLUMN t_knowledge_document_schedule.cron_expr IS 'Cron表达式';
COMMENT ON COLUMN t_knowledge_document_schedule.enabled IS '是否启用';
COMMENT ON COLUMN t_knowledge_document_schedule.next_run_time IS '下次执行时间';
COMMENT ON COLUMN t_knowledge_document_schedule.last_run_time IS '上次执行时间';
COMMENT ON COLUMN t_knowledge_document_schedule.last_success_time IS '上次成功时间';
COMMENT ON COLUMN t_knowledge_document_schedule.last_status IS '上次状态';
COMMENT ON COLUMN t_knowledge_document_schedule.last_error IS '上次错误';
COMMENT ON COLUMN t_knowledge_document_schedule.last_etag IS '上次ETag';
COMMENT ON COLUMN t_knowledge_document_schedule.last_modified IS '上次修改时间';
COMMENT ON COLUMN t_knowledge_document_schedule.last_content_hash IS '上次内容哈希';
COMMENT ON COLUMN t_knowledge_document_schedule.lock_owner IS '锁持有者';
COMMENT ON COLUMN t_knowledge_document_schedule.lock_until IS '锁过期时间';
COMMENT ON COLUMN t_knowledge_document_schedule.create_time IS '创建时间';
COMMENT ON COLUMN t_knowledge_document_schedule.update_time IS '更新时间';

-- t_knowledge_document_schedule_exec
COMMENT ON COLUMN t_knowledge_document_schedule_exec.id IS 'ID';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.schedule_id IS '调度ID';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.doc_id IS '文档ID';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.kb_id IS '知识库ID';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.status IS '状态';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.message IS '消息';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.start_time IS '开始时间';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.end_time IS '结束时间';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.file_name IS '文件名';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.file_size IS '文件大小';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.content_hash IS '内容哈希';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.etag IS 'ETag';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.last_modified IS '最后修改时间';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.create_time IS '创建时间';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.update_time IS '更新时间';

-- t_intent_node
COMMENT ON COLUMN t_intent_node.id IS '自增主键';
COMMENT ON COLUMN t_intent_node.kb_id IS '知识库ID';
COMMENT ON COLUMN t_intent_node.intent_code IS '业务唯一标识';
COMMENT ON COLUMN t_intent_node.name IS '展示名称';
COMMENT ON COLUMN t_intent_node.level IS '层级 0：DOMAIN 1：CATEGORY 2：TOPIC';
COMMENT ON COLUMN t_intent_node.parent_code IS '父节点标识';
COMMENT ON COLUMN t_intent_node.description IS '语义描述';
COMMENT ON COLUMN t_intent_node.examples IS '示例问题';
COMMENT ON COLUMN t_intent_node.collection_name IS '兼容旧版本，后续删除';
COMMENT ON COLUMN t_intent_node.collection_names IS '知识库Collection集合';
COMMENT ON COLUMN t_intent_node.top_k IS '知识库检索TopK';
COMMENT ON COLUMN t_intent_node.mcp_tool_id IS 'MCP工具ID';
COMMENT ON COLUMN t_intent_node.require_confirm IS '执行前是否需要用户确认 1：需要 0：不需要';
COMMENT ON COLUMN t_intent_node.kind IS '类型 0：知识库 1：系统交互 2：MCP 工具';
COMMENT ON COLUMN t_intent_node.prompt_snippet IS '提示词片段';
COMMENT ON COLUMN t_intent_node.prompt_template IS '提示词模板';
COMMENT ON COLUMN t_intent_node.sort_order IS '排序字段';
COMMENT ON COLUMN t_intent_node.enabled IS '是否启用 1：启用 0：禁用';
COMMENT ON COLUMN t_intent_node.create_by IS '创建人';
COMMENT ON COLUMN t_intent_node.update_by IS '修改人';
COMMENT ON COLUMN t_intent_node.create_time IS '创建时间';
COMMENT ON COLUMN t_intent_node.update_time IS '修改时间';
COMMENT ON COLUMN t_intent_node.deleted IS '是否删除 0：正常 1：删除';

-- t_query_term_mapping
COMMENT ON COLUMN t_query_term_mapping.id IS 'ID';
COMMENT ON COLUMN t_query_term_mapping.domain IS '领域';
COMMENT ON COLUMN t_query_term_mapping.source_term IS '源词';
COMMENT ON COLUMN t_query_term_mapping.target_term IS '目标词';
COMMENT ON COLUMN t_query_term_mapping.match_type IS '匹配类型 1：精确 2：模糊';
COMMENT ON COLUMN t_query_term_mapping.priority IS '优先级';
COMMENT ON COLUMN t_query_term_mapping.enabled IS '是否启用';
COMMENT ON COLUMN t_query_term_mapping.remark IS '备注';
COMMENT ON COLUMN t_query_term_mapping.create_by IS '创建人';
COMMENT ON COLUMN t_query_term_mapping.update_by IS '修改人';
COMMENT ON COLUMN t_query_term_mapping.create_time IS '创建时间';
COMMENT ON COLUMN t_query_term_mapping.update_time IS '修改时间';
COMMENT ON COLUMN t_query_term_mapping.deleted IS '是否删除 0：正常 1：删除';

-- t_rag_trace_run
COMMENT ON COLUMN t_rag_trace_run.id IS 'ID';
COMMENT ON COLUMN t_rag_trace_run.trace_id IS '全局链路ID';
COMMENT ON COLUMN t_rag_trace_run.trace_name IS '链路名称';
COMMENT ON COLUMN t_rag_trace_run.entry_method IS '入口方法';
COMMENT ON COLUMN t_rag_trace_run.conversation_id IS '会话ID';
COMMENT ON COLUMN t_rag_trace_run.task_id IS '任务ID';
COMMENT ON COLUMN t_rag_trace_run.user_id IS '用户ID';
COMMENT ON COLUMN t_rag_trace_run.status IS 'RUNNING/SUCCESS/ERROR';
COMMENT ON COLUMN t_rag_trace_run.error_message IS '错误信息';
COMMENT ON COLUMN t_rag_trace_run.start_time IS '开始时间';
COMMENT ON COLUMN t_rag_trace_run.end_time IS '结束时间';
COMMENT ON COLUMN t_rag_trace_run.duration_ms IS '耗时毫秒';
COMMENT ON COLUMN t_rag_trace_run.extra_data IS '扩展字段(JSON)';
COMMENT ON COLUMN t_rag_trace_run.create_time IS '创建时间';
COMMENT ON COLUMN t_rag_trace_run.update_time IS '更新时间';
COMMENT ON COLUMN t_rag_trace_run.deleted IS '是否删除';

-- t_rag_trace_node
COMMENT ON COLUMN t_rag_trace_node.id IS 'ID';
COMMENT ON COLUMN t_rag_trace_node.trace_id IS '所属链路ID';
COMMENT ON COLUMN t_rag_trace_node.node_id IS '节点ID';
COMMENT ON COLUMN t_rag_trace_node.parent_node_id IS '父节点ID';
COMMENT ON COLUMN t_rag_trace_node.depth IS '节点深度';
COMMENT ON COLUMN t_rag_trace_node.node_type IS '节点类型';
COMMENT ON COLUMN t_rag_trace_node.node_name IS '节点名称';
COMMENT ON COLUMN t_rag_trace_node.class_name IS '类名';
COMMENT ON COLUMN t_rag_trace_node.method_name IS '方法名';
COMMENT ON COLUMN t_rag_trace_node.status IS 'RUNNING/SUCCESS/ERROR';
COMMENT ON COLUMN t_rag_trace_node.error_message IS '错误信息';
COMMENT ON COLUMN t_rag_trace_node.start_time IS '开始时间';
COMMENT ON COLUMN t_rag_trace_node.end_time IS '结束时间';
COMMENT ON COLUMN t_rag_trace_node.duration_ms IS '耗时毫秒';
COMMENT ON COLUMN t_rag_trace_node.extra_data IS '扩展字段(JSON)';
COMMENT ON COLUMN t_rag_trace_node.create_time IS '创建时间';
COMMENT ON COLUMN t_rag_trace_node.update_time IS '更新时间';
COMMENT ON COLUMN t_rag_trace_node.deleted IS '是否删除';

-- t_ingestion_pipeline
COMMENT ON COLUMN t_ingestion_pipeline.id IS 'ID';
COMMENT ON COLUMN t_ingestion_pipeline.name IS '流水线名称';
COMMENT ON COLUMN t_ingestion_pipeline.description IS '流水线描述';
COMMENT ON COLUMN t_ingestion_pipeline.created_by IS '创建人';
COMMENT ON COLUMN t_ingestion_pipeline.updated_by IS '更新人';
COMMENT ON COLUMN t_ingestion_pipeline.create_time IS '创建时间';
COMMENT ON COLUMN t_ingestion_pipeline.update_time IS '更新时间';
COMMENT ON COLUMN t_ingestion_pipeline.deleted IS '是否删除 0：正常 1：删除';

-- t_ingestion_pipeline_node
COMMENT ON COLUMN t_ingestion_pipeline_node.id IS 'ID';
COMMENT ON COLUMN t_ingestion_pipeline_node.pipeline_id IS '流水线ID';
COMMENT ON COLUMN t_ingestion_pipeline_node.node_id IS '节点标识(同一流水线内唯一)';
COMMENT ON COLUMN t_ingestion_pipeline_node.node_type IS '节点类型';
COMMENT ON COLUMN t_ingestion_pipeline_node.next_node_id IS '下一个节点ID';
COMMENT ON COLUMN t_ingestion_pipeline_node.settings_json IS '节点配置JSON';
COMMENT ON COLUMN t_ingestion_pipeline_node.condition_json IS '条件JSON';
COMMENT ON COLUMN t_ingestion_pipeline_node.created_by IS '创建人';
COMMENT ON COLUMN t_ingestion_pipeline_node.updated_by IS '更新人';
COMMENT ON COLUMN t_ingestion_pipeline_node.create_time IS '创建时间';
COMMENT ON COLUMN t_ingestion_pipeline_node.update_time IS '更新时间';
COMMENT ON COLUMN t_ingestion_pipeline_node.deleted IS '是否删除 0：正常 1：删除';

-- t_ingestion_task
COMMENT ON COLUMN t_ingestion_task.id IS 'ID';
COMMENT ON COLUMN t_ingestion_task.pipeline_id IS '流水线ID';
COMMENT ON COLUMN t_ingestion_task.source_type IS '来源类型';
COMMENT ON COLUMN t_ingestion_task.source_location IS '来源地址或URL';
COMMENT ON COLUMN t_ingestion_task.source_file_name IS '原始文件名';
COMMENT ON COLUMN t_ingestion_task.status IS '任务状态';
COMMENT ON COLUMN t_ingestion_task.chunk_count IS '分块数量';
COMMENT ON COLUMN t_ingestion_task.error_message IS '错误信息';
COMMENT ON COLUMN t_ingestion_task.logs_json IS '节点日志JSON';
COMMENT ON COLUMN t_ingestion_task.metadata_json IS '扩展元数据JSON';
COMMENT ON COLUMN t_ingestion_task.started_at IS '开始时间';
COMMENT ON COLUMN t_ingestion_task.completed_at IS '完成时间';
COMMENT ON COLUMN t_ingestion_task.created_by IS '创建人';
COMMENT ON COLUMN t_ingestion_task.updated_by IS '更新人';
COMMENT ON COLUMN t_ingestion_task.create_time IS '创建时间';
COMMENT ON COLUMN t_ingestion_task.update_time IS '更新时间';
COMMENT ON COLUMN t_ingestion_task.deleted IS '是否删除 0：正常 1：删除';

-- t_ingestion_task_node
COMMENT ON COLUMN t_ingestion_task_node.id IS 'ID';
COMMENT ON COLUMN t_ingestion_task_node.task_id IS '任务ID';
COMMENT ON COLUMN t_ingestion_task_node.pipeline_id IS '流水线ID';
COMMENT ON COLUMN t_ingestion_task_node.node_id IS '节点标识';
COMMENT ON COLUMN t_ingestion_task_node.node_type IS '节点类型';
COMMENT ON COLUMN t_ingestion_task_node.node_order IS '节点顺序';
COMMENT ON COLUMN t_ingestion_task_node.status IS '节点状态';
COMMENT ON COLUMN t_ingestion_task_node.duration_ms IS '执行耗时(毫秒)';
COMMENT ON COLUMN t_ingestion_task_node.message IS '节点消息';
COMMENT ON COLUMN t_ingestion_task_node.error_message IS '错误信息';
COMMENT ON COLUMN t_ingestion_task_node.output_json IS '节点输出JSON(全量)';
COMMENT ON COLUMN t_ingestion_task_node.create_time IS '创建时间';
COMMENT ON COLUMN t_ingestion_task_node.update_time IS '更新时间';
COMMENT ON COLUMN t_ingestion_task_node.deleted IS '是否删除 0：正常 1：删除';

-- t_agent_profile
COMMENT ON COLUMN t_agent_profile.id IS '主键ID';
COMMENT ON COLUMN t_agent_profile.name IS '智能体名称，唯一';
COMMENT ON COLUMN t_agent_profile.description IS '智能体描述';
COMMENT ON COLUMN t_agent_profile.avatar IS '头像预设标识，取值由前端预设表定义，认不出时按 id 哈希兜底';
COMMENT ON COLUMN t_agent_profile.builtin IS '是否内置 0：否 1：是。内置智能体不可编辑不可删除，是所有空槽位的回落终点';
COMMENT ON COLUMN t_agent_profile.active IS '是否激活 0：否 1：是。全局仅允许一条为 1';
COMMENT ON COLUMN t_agent_profile.create_by IS '创建人';
COMMENT ON COLUMN t_agent_profile.update_by IS '更新人';
COMMENT ON COLUMN t_agent_profile.create_time IS '创建时间';
COMMENT ON COLUMN t_agent_profile.update_time IS '更新时间';
COMMENT ON COLUMN t_agent_profile.deleted IS '是否删除 0：正常 1：删除';

-- t_agent_prompt
COMMENT ON COLUMN t_agent_prompt.id IS '主键ID';
COMMENT ON COLUMN t_agent_prompt.agent_id IS '所属智能体ID';
COMMENT ON COLUMN t_agent_prompt.slot_key IS '槽位标识，见 AgentPromptSlot 枚举';
COMMENT ON COLUMN t_agent_prompt.content IS '提示词全文，空白视为未配置并回落内置智能体';
COMMENT ON COLUMN t_agent_prompt.create_by IS '创建人';
COMMENT ON COLUMN t_agent_prompt.update_by IS '更新人';
COMMENT ON COLUMN t_agent_prompt.create_time IS '创建时间';
COMMENT ON COLUMN t_agent_prompt.update_time IS '更新时间';
COMMENT ON COLUMN t_agent_prompt.deleted IS '是否删除 0：正常 1：删除';

-- t_agent_skill
COMMENT ON COLUMN t_agent_skill.id IS '主键ID';
COMMENT ON COLUMN t_agent_skill.skill_code IS '技能标识，模型按此名加载正文';
COMMENT ON COLUMN t_agent_skill.name IS '技能展示名';
COMMENT ON COLUMN t_agent_skill.description IS '技能适用场景，随清单一起交给模型判断是否加载';
COMMENT ON COLUMN t_agent_skill.content IS '技能正文 Markdown，模型加载后按此执行';
COMMENT ON COLUMN t_agent_skill.tool_ids IS '加载技能后才解锁的 MCP 工具 ID，取自意图树 MCP 节点';
COMMENT ON COLUMN t_agent_skill.sort_order IS '排序，越小越靠前';
COMMENT ON COLUMN t_agent_skill.enabled IS '是否启用 0：停用 1：启用';
COMMENT ON COLUMN t_agent_skill.create_by IS '创建人';
COMMENT ON COLUMN t_agent_skill.update_by IS '更新人';
COMMENT ON COLUMN t_agent_skill.create_time IS '创建时间';
COMMENT ON COLUMN t_agent_skill.update_time IS '更新时间';
COMMENT ON COLUMN t_agent_skill.deleted IS '是否删除 0：正常 1：删除';

-- t_agent_conversation
COMMENT ON COLUMN t_agent_conversation.id IS '主键ID';
COMMENT ON COLUMN t_agent_conversation.conversation_id IS '会话ID';
COMMENT ON COLUMN t_agent_conversation.user_id IS '用户ID';
COMMENT ON COLUMN t_agent_conversation.title IS '会话标题';
COMMENT ON COLUMN t_agent_conversation.last_time IS '最后活动时间';
COMMENT ON COLUMN t_agent_conversation.create_time IS '创建时间';
COMMENT ON COLUMN t_agent_conversation.update_time IS '更新时间';
COMMENT ON COLUMN t_agent_conversation.deleted IS '是否删除 0：正常 1：删除';

-- t_agent_message
COMMENT ON COLUMN t_agent_message.id IS '主键ID';
COMMENT ON COLUMN t_agent_message.conversation_id IS '会话ID';
COMMENT ON COLUMN t_agent_message.user_id IS '用户ID';
COMMENT ON COLUMN t_agent_message.role IS '角色 user：用户 assistant：助手';
COMMENT ON COLUMN t_agent_message.content IS '消息正文';
COMMENT ON COLUMN t_agent_message.thinking_content IS '思考内容';
COMMENT ON COLUMN t_agent_message.blocks IS '运行轨迹块（reasoning/answer/tool 有序序列），回放还原时间线';
COMMENT ON COLUMN t_agent_message.reply_to_message_id IS '回复的用户消息ID';
COMMENT ON COLUMN t_agent_message.message_status IS '消息终态 NORMAL：正常 INTERRUPTED：用户中断';
COMMENT ON COLUMN t_agent_message.duration_ms IS '本轮 run 的服务端耗时（毫秒），仅 assistant 有值';
COMMENT ON COLUMN t_agent_message.create_time IS '创建时间';
COMMENT ON COLUMN t_agent_message.update_time IS '更新时间';
COMMENT ON COLUMN t_agent_message.deleted IS '是否删除 0：正常 1：删除';

-- t_agent_state
COMMENT ON COLUMN t_agent_state.user_id IS '用户ID';
COMMENT ON COLUMN t_agent_state.session_id IS '会话ID，即 AgentScope 的 sessionId';
COMMENT ON COLUMN t_agent_state.state_key IS '状态键，AgentScope 侧固定传 agent_state';
COMMENT ON COLUMN t_agent_state.payload IS '框架自有编码的状态 JSON，业务侧不解析';
COMMENT ON COLUMN t_agent_state.create_time IS '创建时间';
COMMENT ON COLUMN t_agent_state.update_time IS '更新时间';

-- t_agent_context_compaction
COMMENT ON COLUMN t_agent_context_compaction.id IS '主键ID';
COMMENT ON COLUMN t_agent_context_compaction.user_id IS '用户ID';
COMMENT ON COLUMN t_agent_context_compaction.conversation_id IS '会话ID，即 AgentScope 的 sessionId';
COMMENT ON COLUMN t_agent_context_compaction.generation IS '同一会话内的第几代摘要，从 1 起';
COMMENT ON COLUMN t_agent_context_compaction.summary IS '本代摘要正文，回填进上下文的那一份';
COMMENT ON COLUMN t_agent_context_compaction.material_msg_count IS '被换出的原文消息条数';
COMMENT ON COLUMN t_agent_context_compaction.material_chars IS '被换出的原文字符数';
COMMENT ON COLUMN t_agent_context_compaction.summary_chars IS '摘要正文字符数';
COMMENT ON COLUMN t_agent_context_compaction.context_chars_before IS '压缩前上下文总字符数';
COMMENT ON COLUMN t_agent_context_compaction.context_chars_after IS '压缩后上下文总字符数';
COMMENT ON COLUMN t_agent_context_compaction.create_time IS '创建时间';

-- t_agent_memory
COMMENT ON COLUMN t_agent_memory.id IS '主键ID';
COMMENT ON COLUMN t_agent_memory.user_id IS '用户ID';
COMMENT ON COLUMN t_agent_memory.content IS '记忆正文';
COMMENT ON COLUMN t_agent_memory.source_type IS '写入来源：FLUSH/BACKGROUND/CONSOLIDATION';
COMMENT ON COLUMN t_agent_memory.invalid_at IS '失效时刻，NULL 即 ACTIVE';
COMMENT ON COLUMN t_agent_memory.superseded_by IS '取代者ID，撤回与清空行留空';
COMMENT ON COLUMN t_agent_memory.create_time IS '创建时间';

-- t_agent_memory_extraction
COMMENT ON COLUMN t_agent_memory_extraction.id IS '主键ID';
COMMENT ON COLUMN t_agent_memory_extraction.user_id IS '用户ID';
COMMENT ON COLUMN t_agent_memory_extraction.conversation_id IS '触发本批的会话ID';
COMMENT ON COLUMN t_agent_memory_extraction.from_message_id IS '本批首条用户消息ID';
COMMENT ON COLUMN t_agent_memory_extraction.to_message_id IS '本批末条用户消息ID';
COMMENT ON COLUMN t_agent_memory_extraction.status IS '抽取状态：PROCESSING/WRITTEN/NOOP/DROPPED/CONFLICT/PENDING_APPROVAL/APPROVED/APPLIED/REJECTED/EXPIRED/INVALIDATED';
COMMENT ON COLUMN t_agent_memory_extraction.trigger_type IS '触发方：FLUSH/BACKGROUND';
COMMENT ON COLUMN t_agent_memory_extraction.decision_count IS '实际落库的决策条数';
COMMENT ON COLUMN t_agent_memory_extraction.attempt_count IS '第几次尝试，达上限记 DROPPED';
COMMENT ON COLUMN t_agent_memory_extraction.plan_json IS '冻结的记忆变更计划快照（受审批次决策+目标条目内容+消息范围+快照凭证），仅审批链路读写';
COMMENT ON COLUMN t_agent_memory_extraction.plan_expires_at IS '计划有效期截止（冻结时刻+30 分钟），读取/确认/执行/新请求时按需结算到期';
COMMENT ON COLUMN t_agent_memory_extraction.expected_revision IS '冻结时刻的记忆版本号，执行时复核，跨会话版本变化即失效';
COMMENT ON COLUMN t_agent_memory_extraction.plan_tool_call_id IS '批准绑定的 apply_memory_change 工具调用ID，执行时校验';
COMMENT ON COLUMN t_agent_memory_extraction.plan_confirm_message_id IS '批准所在的确认卡消息ID，审计用';
COMMENT ON COLUMN t_agent_memory_extraction.plan_result_json IS 'APPLIED 后的执行结果快照，重复执行读原结果不重复提交';
COMMENT ON COLUMN t_agent_memory_extraction.create_time IS '创建时间';
COMMENT ON COLUMN t_agent_memory_extraction.settle_time IS '抽取结束时刻，非终态为空';

-- t_agent_memory_control
COMMENT ON COLUMN t_agent_memory_control.user_id IS '用户ID';
COMMENT ON COLUMN t_agent_memory_control.revision IS '记忆集版本号，提交期与水位一同双校验';
COMMENT ON COLUMN t_agent_memory_control.create_time IS '建行时刻，兼作抽取下界：更早的历史消息不倒灌';
COMMENT ON COLUMN t_agent_memory_control.update_time IS '更新时间';

-- ============================================================
-- 统一分享快照（2026-09-22，issue #124）
-- 答案分享与会话分享是同一机制的两种粒度（CONTEXT.md 伞词条「分享快照」）：
-- kind 判别 + payload 不透明 JSONB（answer=messageId/question/answerMd/citations/contentVersion；
-- conversation=title/messages/contentVersion；类型与序列化归各粒度 adapter，本表零解析）。
-- 不可变快照：创建时值复制，公开读绝不回链 t_message/t_agent_conversation/t_agent_message；
-- 不含用户身份/思考/工具轨迹/IP（隐私负面清单）。存量两表由
-- upgrades/v2.0.0/260922_share_snapshot_unification.sql 合并迁移（token 原样平移）。
-- ============================================================
CREATE TABLE t_share_snapshot (
    id                VARCHAR(20)    NOT NULL PRIMARY KEY,
    token             VARCHAR(64)    NOT NULL,
    owner_user_id     VARCHAR(20)    NOT NULL,
    kind              VARCHAR(16)    NOT NULL,
    conversation_id   VARCHAR(20)    NOT NULL,
    lang              VARCHAR(8),
    status            VARCHAR(16)    NOT NULL DEFAULT 'ACTIVE',
    expire_time       TIMESTAMP,
    revoked_time      TIMESTAMP,
    create_time       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted           SMALLINT       NOT NULL DEFAULT 0,
    payload           JSONB          NOT NULL,
    CONSTRAINT uk_share_snapshot_token UNIQUE (token)
);
CREATE INDEX idx_share_snapshot_owner ON t_share_snapshot (owner_user_id, create_time);
CREATE INDEX idx_share_snapshot_kind ON t_share_snapshot (kind);
COMMENT ON TABLE t_share_snapshot IS '统一分享快照表（issue #124；答案/会话两粒度合表，kind 判别+payload 不透明 JSONB；token 加密随机不可枚举）';
COMMENT ON COLUMN t_share_snapshot.token IS 'SecureRandom 32 字节 Base64URL（43 字符）';
COMMENT ON COLUMN t_share_snapshot.owner_user_id IS '创建者用户ID（仅归属校验与撤销/治理用，公开载荷不返回）';
COMMENT ON COLUMN t_share_snapshot.kind IS '快照粒度判别：answer / conversation';
COMMENT ON COLUMN t_share_snapshot.conversation_id IS '来源会话ID（仅撤销/我的列表溯源，不进公开载荷）';
COMMENT ON COLUMN t_share_snapshot.status IS 'ACTIVE/REVOKED；注销级联=软撤销行保留，保留任务按 expire_time 硬删';
COMMENT ON COLUMN t_share_snapshot.expire_time IS '过期时刻，NULL 即不过期';
COMMENT ON COLUMN t_share_snapshot.payload IS '粒度侧不透明载荷 JSON（module 零解析；answer=messageId/question/answerMd/citations/contentVersion，conversation=title/messages/contentVersion）';

-- ============================================================
-- 资讯流四表（2026-09-10）
-- 与 RAG 知识库管线物理隔离：检索链路不读；条目 url_hash 幂等去重；
-- 90 天保留清理由 NewsRetentionJob 负责（不进通用 DataRetentionProperties）；
-- 种子数据（信源 7 行+主题 20 行）见 init_data_pg.sql
-- ============================================================
-- 源治理列（2026-09-30，#186——父票 #181 §2）：停用原因三分 disabled_reason
-- （manual 人工停用/auto 自动隔离/policy 策略禁止，NULL=启用中）+ 探活记账
-- （probe_successes/probe_time，日级节拍）+ 最近六类结果（last_outcome）+
-- 停止/复归时刻；事件流水见下方 t_news_source_health_event。增量环境走
-- upgrades/v2.0.0/260930_news_source_governance.sql（含存量禁用行判据迁移）
CREATE TABLE t_news_source (
  id             BIGSERIAL PRIMARY KEY,
  source_key     VARCHAR(64)  NOT NULL UNIQUE,
  platform       VARCHAR(32)  NOT NULL,          -- official / youtube / weibo / zhihu / prn / events
  display_name   VARCHAR(128) NOT NULL,
  display_name_en VARCHAR(128),
  home_url       VARCHAR(512),
  fetch_endpoint VARCHAR(1024) NOT NULL,         -- 列表页 / RSS URL
  fetch_strategy VARCHAR(32)  NOT NULL,          -- SITEMAP / HTML_LIST / RSS / JSON_API
  official       BOOLEAN      NOT NULL DEFAULT TRUE,
  enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
  consecutive_failures INT    NOT NULL DEFAULT 0,
  disabled_reason VARCHAR(16),                   -- manual/auto/policy；NULL=启用中（#186 三分）
  isolated_time  TIMESTAMP,                      -- 最近停用时刻（自动隔离/策略转停，#186）
  recovered_time TIMESTAMP,                      -- 最近探活复归时刻（#186）
  probe_successes INT,                           -- 探活连续有效完整成功次数（复归阈值 2，#186）
  probe_time     TIMESTAMP,                      -- 最近探活时刻（HKT 日级节拍，#186）
  last_outcome   VARCHAR(32),                    -- 最近一轮六类结果代码（#186）
  last_outcome_time TIMESTAMP,                   -- 最近一轮结果落账时刻（#186）
  independence_group VARCHAR(64),                -- 独立来源组（#187 投票去重键）；NULL=按 source_key 自成一组
  create_time    TIMESTAMP    NOT NULL DEFAULT now(),
  update_time    TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_news_source_probe_candidates
    ON t_news_source(disabled_reason) WHERE enabled = false;
COMMENT ON TABLE t_news_source IS '资讯信源注册表（V1 只上校级账号；扩源=加行无代码改动）';
COMMENT ON COLUMN t_news_source.source_key IS '信源稳定标识：news-sitemap / media-releases / youtube-main 等';
COMMENT ON COLUMN t_news_source.platform IS 'official=官网；youtube/prn=第三方平台（卡片带平台徽章）';
COMMENT ON COLUMN t_news_source.fetch_endpoint IS '抓取入口；events 型含 date=YYYY/MM 占位，由抓取器按当前月+下月替换';
COMMENT ON COLUMN t_news_source.fetch_strategy IS 'SITEMAP / HTML_LIST / RSS / JSON_API 四型';
COMMENT ON COLUMN t_news_source.official IS '是否官网（polyu.edu.hk）来源；false 的卡片带平台徽章';
COMMENT ON COLUMN t_news_source.enabled IS '源级开关（#186 起与 disabled_reason 联读）：true=启用；false 须看 disabled_reason 三分（manual/auto/policy）';
COMMENT ON COLUMN t_news_source.consecutive_failures IS '连续抓取失败计数（结构失配/网络失败两类，#186 六类分类学）：阈值 3 自动隔离（enabled=false + disabled_reason=auto）；defer 零计数豁免';
COMMENT ON COLUMN t_news_source.disabled_reason IS '停用原因三分（#186）：manual=人工停用（不探活不自动解禁）/ auto=自动隔离（连续 3 败滞回，日级探活两次有效完整成功自动复归）/ policy=策略禁止（robots/出站守卫拒绝，不因可达自动解禁）；NULL=启用中';
COMMENT ON COLUMN t_news_source.isolated_time IS '最近一次停用时刻（#186）：自动隔离或策略转停发生时间；迁移不回填';
COMMENT ON COLUMN t_news_source.recovered_time IS '最近一次探活自动复归时刻（#186）：连续两次有效完整成功达成';
COMMENT ON COLUMN t_news_source.probe_successes IS '探活连续有效完整成功次数（#186）：复归阈值默认 2；任一探活失败清零；defer 不变';
COMMENT ON COLUMN t_news_source.probe_time IS '最近一次探活时刻（#186）：HKT 日级节拍每源每日至多探一次；defer 不推进';
COMMENT ON COLUMN t_news_source.last_outcome IS '最近一轮单源抓取结果六类代码（#186）：valid_with_content / valid_empty / structure_mismatch / network_failure / policy_forbidden / defer';
COMMENT ON COLUMN t_news_source.last_outcome_time IS '最近一轮结果落账时刻（#186）';
COMMENT ON COLUMN t_news_source.independence_group IS '独立来源组（#187 事件投票去重键）：同机构多 feed/聚合口归同组只计一票（官网各栏目+官方 YouTube=polyu-official；PRN 双语 wire=prn-wire；GNews 检索面=gnews）；NULL=按 source_key 自成一组（种子映射见 init_data_pg.sql）';

-- 信源健康事件流水（2026-09-30，#186：停止/复归/探活记录可查，append-only 审计）
CREATE TABLE t_news_source_health_event (
  id          BIGSERIAL PRIMARY KEY,
  source_id   BIGINT      NOT NULL REFERENCES t_news_source(id),
  event_type  VARCHAR(32) NOT NULL,             -- isolated/policy_disabled/probe_pass/probe_fail/recovered
  outcome     VARCHAR(32),                      -- 触发事件的单轮六类结果代码（判定依据）
  detail      VARCHAR(512),                     -- 判定依据摘要（截断 500 字符）
  event_time  TIMESTAMP   NOT NULL,
  create_time TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_news_source_health_event_source
    ON t_news_source_health_event(source_id);
COMMENT ON TABLE t_news_source_health_event IS '信源健康事件流水（#186，append-only 审计）：isolated=自动隔离 / policy_disabled=策略转停 / probe_pass=探活通过 / probe_fail=探活失败 / recovered=探活复归；人工停用/启用走维护者 SQL 不落本表（以源行 disabled_reason=manual 为准）';
COMMENT ON COLUMN t_news_source_health_event.source_id IS '关联信源 t_news_source.id（源删除后保留事件行，admin 回退显示 deleted#id）';
COMMENT ON COLUMN t_news_source_health_event.event_type IS '事件类型：isolated / policy_disabled / probe_pass / probe_fail / recovered';
COMMENT ON COLUMN t_news_source_health_event.outcome IS '触发事件的单轮六类结果代码（与 t_news_source.last_outcome 同一枚举）';
COMMENT ON COLUMN t_news_source_health_event.detail IS '判定依据摘要（失败原因文本/成功计数）';
COMMENT ON COLUMN t_news_source_health_event.event_time IS '事件时刻（业务时钟）';

-- 处理状态五态+发布门列（2026-09-29，#185——父票 #180 §2/§3/§5）：
-- pending=待富化（已准入，付费队列）；published=发布资格就绪（公开可见还须过
-- 180s 发布门）；archived=终态·旧文归档（发现时原文发布超 48h，不进「今天」、
-- 跳过付费富化、不计日准入）；expired=终态·待富化超龄（48h 未获资格退出待办）；
-- hidden=人工下架。统一公开资格=status='published' AND (eligible_time IS NULL
-- OR eligible_time <= now-180s)；预算延期/无效摘要/待富化不落 published，
-- 不能靠 180s 超时放行。增量环境走 upgrades/v2.0.0/260929_02_news_item_pipeline_status.sql
CREATE TABLE t_news_item (
  id             BIGSERIAL PRIMARY KEY,
  source_id      BIGINT NOT NULL REFERENCES t_news_source(id),
  url            VARCHAR(1024) NOT NULL,
  url_hash       VARCHAR(64)  NOT NULL,            -- sha256，幂等去重唯一键
  title_zh       VARCHAR(512),
  title_en       VARCHAR(512),
  summary_zh     TEXT,
  summary_en     TEXT,
  category       VARCHAR(32) NOT NULL DEFAULT 'other',
  lang_raw       VARCHAR(8)  NOT NULL DEFAULT 'en',
  publish_time   TIMESTAMP,
  publish_time_precision VARCHAR(8) NOT NULL DEFAULT 'unknown',  -- date/datetime/unknown（#275；unknown=历史行不回填）
  fetch_time     TIMESTAMP NOT NULL DEFAULT now(),
  status         VARCHAR(16) NOT NULL DEFAULT 'published',  -- pending/published/archived/expired/hidden
  heat           INT NOT NULL DEFAULT 0,           -- V2 跨源聚类预留
  eligible_time  TIMESTAMP,                        -- 发布资格就绪时刻（#185 发布门起算；NULL=历史行）
  summary_source VARCHAR(8),                       -- llm/fallback/NULL=历史（#185）
  prompt_version VARCHAR(16),                      -- sha256(模板全文) 前 12 位（#185 可追溯）
  content_hash   VARCHAR(64),                      -- 富化判重内容哈希（#187）：同哈希零调用复用摘要
  create_time    TIMESTAMP NOT NULL DEFAULT now(),
  CONSTRAINT uq_news_item_url UNIQUE (url_hash)
);
CREATE INDEX idx_news_item_pub ON t_news_item(publish_time DESC) WHERE status = 'published';
CREATE INDEX idx_news_item_pending_ttl ON t_news_item(fetch_time) WHERE status = 'pending';
CREATE INDEX idx_news_item_fetch_day ON t_news_item(fetch_time);
CREATE INDEX idx_news_item_content_hash ON t_news_item(content_hash) WHERE content_hash IS NOT NULL;
COMMENT ON TABLE t_news_item IS '资讯条目表（AI 双语摘要+永久原文外链；不进 RAG 证据面）';
COMMENT ON COLUMN t_news_item.url_hash IS 'sha256(url) 十六进制，幂等去重唯一键';
COMMENT ON COLUMN t_news_item.category IS '固定 8 类：admission/scholarship/research/campus/event/career/exchange/admin（+other 兜底）';
COMMENT ON COLUMN t_news_item.lang_raw IS '原文语言（en/zh-Hant/zh-Hans）';
COMMENT ON COLUMN t_news_item.status IS '处理状态五态（#185）：pending=待富化（已准入）；published=发布资格就绪（公开可见还须过 180s 发布门，见 eligible_time）；archived=终态·旧文归档（发现时原文发布超 48h，不计日准入）；expired=终态·待富化超龄（48h 未获资格，退出待办）；hidden=人工下架';
COMMENT ON COLUMN t_news_item.eligible_time IS '发布资格就绪时刻（#185）：合格摘要落库或明示零调用回退时间；发布门 180s 从本列起算——统一公开资格=status=published AND (本列 IS NULL OR 本列 <= now-180s)；NULL=#185 前历史行（视同早已开启，不重算）';
COMMENT ON COLUMN t_news_item.summary_source IS '摘要产出方式（#185）：llm=LLM 富化；fallback=明示零调用回退（标题派生，守卫/回执终态的可解释回退）；NULL=历史行';
COMMENT ON COLUMN t_news_item.prompt_version IS '产出摘要所用提示词模板版本（#185）：sha256(模板全文) 前 12 位；改词即版本变化只影响新资料，历史不自动重算；fallback 无提示词为 NULL';
COMMENT ON COLUMN t_news_item.publish_time_precision IS '发布时间精度（#275）：date=只有日期证据（publish_time 为该日 23:59:59 HKT 归期代表值，落 [D 08:00,D+1 08:00) 归 D+1 刊；展示层只显日期）/ datetime=真实瞬时（RSS pubDate、lib HKT HH:mm、PRN HH:mm ET 换算）/ unknown=本列前历史行与非精确化路径（sitemap lastmod、events start-date 含义不改）——不猜测、不历史回填';
COMMENT ON COLUMN t_news_item.content_hash IS '富化判重内容哈希（#187）：sha256(规范化标题+正文摘录)；同哈希且供体 summary_source=llm 时零调用复用摘要（保留逐源证据行）；NULL=未富化/无正文（YouTube 跳过正文，不复用）';

CREATE TABLE t_news_topic (
  id            BIGSERIAL PRIMARY KEY,
  slug          VARCHAR(64) NOT NULL UNIQUE,
  name_zh       VARCHAR(128) NOT NULL,
  name_en       VARCHAR(128),
  topic_group   VARCHAR(32) NOT NULL,      -- FACULTY / RESEARCH / STUDENT_AFFAIRS
  description_zh VARCHAR(512),
  description_en VARCHAR(512),
  curated       BOOLEAN NOT NULL DEFAULT TRUE,  -- 种子词表 TRUE；AI 新提案 FALSE 待人工抽检审后转正
  status        VARCHAR(16) NOT NULL DEFAULT 'active',
  create_time   TIMESTAMP NOT NULL DEFAULT now(),
  update_time   TIMESTAMP NOT NULL DEFAULT now()
);
COMMENT ON TABLE t_news_topic IS '资讯主题词表（三维分组：学院与部门/研究领域与话题/学生事务）';
COMMENT ON COLUMN t_news_topic.slug IS '主题稳定标识，与原型 TOPICS 注册表键一致';
COMMENT ON COLUMN t_news_topic.topic_group IS 'FACULTY=学院与部门；RESEARCH=研究领域与话题；STUDENT_AFFAIRS=学生事务';
COMMENT ON COLUMN t_news_topic.curated IS 'TRUE=策展词表进目录；FALSE=AI 提案待审不进目录';
COMMENT ON COLUMN t_news_topic.status IS 'active=正常展示（curated=true 进目录；curated=false=AI 提案待审）/ merged=已并入近义 curated 主题（关联已迁移，本行保留审计，curated 保持 false）/ rejected=已弃（泛化无检索价值，残留关联已摘除并留痕，curated 保持 false）——一律软状态不硬删（#202）';

CREATE TABLE t_news_item_topic (
  item_id  BIGINT NOT NULL REFERENCES t_news_item(id) ON DELETE CASCADE,
  topic_id BIGINT NOT NULL REFERENCES t_news_topic(id),
  PRIMARY KEY (item_id, topic_id)
);
CREATE INDEX idx_news_item_topic ON t_news_item_topic(topic_id);
COMMENT ON TABLE t_news_item_topic IS '条目-主题多对多关联（保留期清理随 t_news_item 级联删除）';

-- 主题提案治理（2026-09-30，#202——父票 #181 §3 P1-B）：三轨处置（merge 并入/promote
-- 转正/reject 弃）当前态落 t_news_topic.status 软状态；别名账防再提（消费点命中别名
-- 不再落新行）；治理留痕 append-only 流水（沿 #186 t_news_source_health_event 形态：
-- 当前态可 UPDATE，流水只 INSERT）。增量环境走 upgrades/v2.0.0/260930_04_news_topic_governance.sql
CREATE TABLE t_news_topic_alias (
  id              BIGSERIAL PRIMARY KEY,
  alias_key       VARCHAR(128) NOT NULL UNIQUE,
  alias_display   VARCHAR(128),
  action          VARCHAR(16) NOT NULL,     -- merged / rejected
  source_topic_id BIGINT      NOT NULL REFERENCES t_news_topic(id),
  target_topic_id BIGINT      REFERENCES t_news_topic(id),  -- merged 必填；rejected NULL
  operator        VARCHAR(64),
  reason          VARCHAR(512),
  create_time     TIMESTAMP   NOT NULL DEFAULT now(),
  update_time     TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX idx_news_topic_alias_source ON t_news_topic_alias(source_topic_id);
CREATE INDEX idx_news_topic_alias_target ON t_news_topic_alias(target_topic_id);
COMMENT ON TABLE t_news_topic_alias IS '主题别名账（#202 防再提）：merged/rejected 提案 name_zh/name_en 的规范化映射（alias_key=lower+去全部空白）；富化提案消费点命中别名不再落新行——merged 别名回链 target_topic_id，rejected 别名跳过不挂关联';
COMMENT ON COLUMN t_news_topic_alias.alias_key IS '规范化别名键：lower(Locale.ROOT)+去全部空白（与 NewsEnrichService 提案消费点同一 normalize 口径）；同一提案 name_zh/name_en 规范化后同键只入一行';
COMMENT ON COLUMN t_news_topic_alias.action IS '入账动作：merged=并入近义 curated 主题（target_topic_id 必填）/ rejected=弃（target_topic_id 为 NULL）';
COMMENT ON COLUMN t_news_topic_alias.operator IS '处置操作者（admin 账号名；批量端点经 UserContext 落行）';

CREATE TABLE t_news_topic_governance_event (
  id              BIGSERIAL PRIMARY KEY,
  topic_id        BIGINT      NOT NULL REFERENCES t_news_topic(id),
  action          VARCHAR(16) NOT NULL,     -- merged / promoted / rejected
  target_topic_id BIGINT,
  detail          VARCHAR(512),
  operator        VARCHAR(64),
  event_time      TIMESTAMP   NOT NULL,
  create_time     TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX idx_news_topic_gov_event_topic ON t_news_topic_governance_event(topic_id);
COMMENT ON TABLE t_news_topic_governance_event IS '主题治理留痕流水（#202，append-only）：merged/promoted/rejected 三轨处置逐行留痕，detail 含关联迁移/摘除计数与 slug 变更明细——当前态在 t_news_topic.status，本表只增不改';
COMMENT ON COLUMN t_news_topic_governance_event.detail IS '处置明细（迁移关联数/摘除关联数/旧→新 slug/阈值依据，超长截断 500 字符）';
COMMENT ON COLUMN t_news_topic_governance_event.operator IS '处置操作者（admin 账号名；本地回放为 replay 标记）';

-- 事件最小模型（2026-09-30，#187——父票 #180 §3/§5/§8：判重三合同的事件面+持久身份
-- +48h 参与者证据+独立来源映射+24h 半衰；明确不做：综述/事件页/向量/评分。
-- 身份规则：未合并/分裂的同事件 ID 稳定；合并选存续 ID（最早首报，平手取小 id）并记
-- 旧→存续；分裂原 ID 留给含最早成员的确定原组，其余新 ID+迁移记录。
-- 增量环境走 upgrades/v2.0.0/260930_02_news_event_identity.sql
CREATE TABLE t_news_event (
  id                  BIGSERIAL PRIMARY KEY,
  status              VARCHAR(16) NOT NULL DEFAULT 'active',   -- active/superseded（合并非存续方）
  heat                INT         NOT NULL DEFAULT 0,          -- 事件热度（独立票×24h 半衰）
  first_report_time   TIMESTAMP,                               -- 最早成员 publish_time（衰减锚）
  last_activity_time  TIMESTAMP,                               -- 最晚成员 publish_time
  create_time         TIMESTAMP   NOT NULL DEFAULT now(),
  update_time         TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX idx_news_event_status ON t_news_event(status) WHERE status = 'active';
COMMENT ON TABLE t_news_event IS '资讯事件持久身份（#187 最小模型：只做身份/证据/热度，无综述/事件页/向量/评分）；未发生合并/分裂的同事件 ID 稳定；合并选存续 ID（最早首报，平手取小 id）并记旧→存续迁移';
COMMENT ON COLUMN t_news_event.status IS 'active=现行事件；superseded=已并入存续事件（身份迁移见 t_news_event_migration，行保留审计不再持有成员）';
COMMENT ON COLUMN t_news_event.heat IS '事件热度=（48h 证据窗内独立来源组数+Σ组内最大源权重）×24h 半衰（锚=first_report_time，未来封顶 1）；同步写成员条目 heat';
COMMENT ON COLUMN t_news_event.first_report_time IS '事件首报=成员最早 publish_time；24h 半衰与 48h 投票证据窗的共同锚点';

CREATE TABLE t_news_event_item (
  id                  BIGSERIAL PRIMARY KEY,
  event_id            BIGINT NOT NULL REFERENCES t_news_event(id),
  item_id             BIGINT NOT NULL REFERENCES t_news_item(id) ON DELETE CASCADE,
  source_id           BIGINT,
  independence_group  VARCHAR(64) NOT NULL,                    -- 入组时源独立组快照
  publish_time        TIMESTAMP,                               -- 证据时刻（48h 窗判定输入）
  joined_time         TIMESTAMP NOT NULL DEFAULT now(),        -- 首次入组时刻
  CONSTRAINT uq_news_event_item UNIQUE (item_id)
);
CREATE INDEX idx_news_event_item_event ON t_news_event_item(event_id);
COMMENT ON TABLE t_news_event_item IS '事件参与者证据（#187）：一条目至多属一事件（item_id 唯一）；重归组改写 event_id 并落 t_news_event_migration(regroup)；下架/过期摘除本行并落 detach 迁移';
COMMENT ON COLUMN t_news_event_item.independence_group IS '入组时 t_news_source.independence_group 快照（源映射变更不回溯历史证据）';
COMMENT ON COLUMN t_news_event_item.publish_time IS '成员条目 publish_time=参与者证据时刻；热度票资格=∈[事件 first_report_time, +48h]';

CREATE TABLE t_news_event_source_vote (
  id                  BIGSERIAL PRIMARY KEY,
  event_id            BIGINT NOT NULL REFERENCES t_news_event(id),
  independence_group  VARCHAR(64) NOT NULL,
  vote_count          INT NOT NULL DEFAULT 0,                  -- 组内参与条目数（仅计数，票=1）
  first_vote_time     TIMESTAMP,
  last_vote_time      TIMESTAMP,
  update_time         TIMESTAMP NOT NULL DEFAULT now(),
  CONSTRAINT uq_news_event_vote UNIQUE (event_id, independence_group)
);
COMMENT ON TABLE t_news_event_source_vote IS '事件独立来源投票账（#187）：(event_id, independence_group) 一行一票——同机构多 feed/聚合口不重复加票；vote_count 只记录组内条目数不放大票权；每轮重归组后按成员全量重建';

CREATE TABLE t_news_event_migration (
  id                  BIGSERIAL PRIMARY KEY,
  old_event_id        BIGINT NOT NULL,
  new_event_id        BIGINT,                                  -- detach 无新事件为 NULL
  kind                VARCHAR(16) NOT NULL,                    -- merge/split/regroup/detach
  item_id             BIGINT,                                  -- split/regroup/detach 携带
  reason              VARCHAR(512),
  create_time         TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_news_event_migration_old ON t_news_event_migration(old_event_id);
COMMENT ON TABLE t_news_event_migration IS '事件身份迁移账本（#187，append-only）：merge=旧事件并入存续（旧 superseded）；split=分裂迁出（原 ID 留给含最早成员的确定原组，迁出条目落新事件）；regroup=条目改组（双存活）；detach=条目下架/过期摘除证据';
COMMENT ON COLUMN t_news_event_migration.kind IS 'merge=事件级合并（item_id 空）/ split=分裂迁出（新事件为被迁入方）/ regroup=条目在存活事件间移动 / detach=终态摘除（new_event_id 空）';

-- 资讯 LLM 预算护栏+付费回执（2026-09-29，#184——父票 #181 §1 合同+维护者六点修正）
-- 一行=（请求指纹, 发生日）周期账本行：周期键首次计入时落定且永不改写（跨日/跨月重试
-- 在新周期行续算，历史归属不搬移）；attempts=真实发出次数（含 fallback/重试，write-ahead
-- 发出前记账，未知结果保守预留）；日/月额度按 stat_date/stat_month 聚合本表（重启不清零）；
-- 成本上界推导：FAST 链现行 [qwen-flash, qwen-plus] 最贵 qwen-plus ×完整限额
-- （输入 4000+2000、输出 1024）→ (6000×0.8+1024×2)/1e6 ≈ ¥0.006848/次。
-- 额度口径：资讯 LLM 专用独立额度（日 ¥1.0/月 ¥10，维护者 2026-09-29 指定保守默认），
-- 与全项目成本口径分开统计不混算。
-- 增量环境走 upgrades/v2.0.0/260929_01_news_llm_budget_receipt.sql
CREATE TABLE t_news_llm_receipt (
  id                  BIGSERIAL PRIMARY KEY,
  request_fingerprint VARCHAR(64)  NOT NULL,            -- sha256（提示词全文+模型+影响输出参数）
  stat_date           DATE         NOT NULL,            -- 本行发出所处日（HKT 日切，行落定后不改写）
  stat_month          VARCHAR(7)   NOT NULL,            -- yyyy-MM（HKT，随 stat_date 落定不改写）
  model_id            VARCHAR(64),                      -- 指纹成分模型（Tier.FAST 主选 id）
  served_model_id     VARCHAR(64),                      -- 实际服务模型（最后一次成功目标）
  attempts            INT          NOT NULL DEFAULT 0,  -- 本周期行内真实发出次数（含 fallback/重试，write-ahead）
  retries             INT          NOT NULL DEFAULT 0,  -- 本周期行内网关重试数（同指纹跨行累计判定 ≤上限）
  cost_estimate       NUMERIC(12,6) NOT NULL DEFAULT 0, -- 本周期行成本（元）= 行内 attempts × 单次上界 ≈0.006848
  response_text       TEXT,                             -- 成功响应原文（先落库再用，复用不重付费）
  status              VARCHAR(16)  NOT NULL DEFAULT 'PENDING',  -- PENDING/SUCCESS/DEGRADED/FAILED/POISONED
  error_brief         VARCHAR(512),                     -- 最近失败摘要（不含提示词与响应内容）
  create_time         TIMESTAMP    NOT NULL DEFAULT now(),
  update_time         TIMESTAMP    NOT NULL DEFAULT now(),
  CONSTRAINT uq_news_llm_receipt UNIQUE (request_fingerprint, stat_date)
);
CREATE INDEX idx_news_llm_receipt_date ON t_news_llm_receipt(stat_date);
CREATE INDEX idx_news_llm_receipt_month ON t_news_llm_receipt(stat_month);
COMMENT ON TABLE t_news_llm_receipt IS '资讯 LLM 付费回执（#184）：（指纹,日）周期账本行，attempts 双口径计数+成本上界估算，预算聚合数据源（重启不清零）';
COMMENT ON COLUMN t_news_llm_receipt.request_fingerprint IS 'sha256(实际渲染提示词全文+模型+temperature/topP/maxTokens)；同指纹重跑复用响应不重复付费';
COMMENT ON COLUMN t_news_llm_receipt.stat_date IS '本行发出所处日（HKT 日切）——日预算聚合键；行落定后不改写，跨日重试在新行续算（维护者修正点3）';
COMMENT ON COLUMN t_news_llm_receipt.stat_month IS 'yyyy-MM（HKT）——月预算聚合键；随 stat_date 落定不改写';
COMMENT ON COLUMN t_news_llm_receipt.model_id IS '指纹成分中的模型（Tier.FAST 主选 id，配置期口径）';
COMMENT ON COLUMN t_news_llm_receipt.served_model_id IS '实际服务模型 id（最后一次成功发出的路由目标；未成功为 NULL）';
COMMENT ON COLUMN t_news_llm_receipt.attempts IS '本周期行内真实发出次数：含路由 fallback 与网关重试；write-ahead 发出前记账（未知结果保守预留，不承诺绝对不重复计费）';
COMMENT ON COLUMN t_news_llm_receipt.retries IS '本周期行内网关重试数；同指纹全行累计 ≤ rag.news.llm-max-retries（跨调度/重启续算，维护者修正点4）';
COMMENT ON COLUMN t_news_llm_receipt.cost_estimate IS '本周期行成本（元）= 行内 attempts × 单次上界（FAST 链最贵候选×完整限额，现行 ≈¥0.006848/次，推导见 rag.news.budget-model-prices 注释）';
COMMENT ON COLUMN t_news_llm_receipt.response_text IS '成功响应原文：先落库再用，同指纹重跑复用不重复付费；复用解析无效时清除并置 POISONED（不无限复读）';
COMMENT ON COLUMN t_news_llm_receipt.status IS 'PENDING=发出中；SUCCESS=已回执；DEGRADED=预算耗尽降级（次日补偿后翻转）；FAILED=重试耗尽或解析失败；POISONED=复用响应持续无效已隔离';
COMMENT ON COLUMN t_news_llm_receipt.error_brief IS '最近一次失败原因摘要（诊断用，不含提示词与响应内容）';

-- 资讯日报（2026-10-03，#212——父票 #182 r3 §日报 P2-a 出口）：刊头（HKT 日期唯一一刊）
-- + 条目快照行表。行表而非 JSON：读取期须逐条回查 t_news_item 做主动下架复检
-- （存在且 status 不为 published → 失格过滤），行表支持 SQL 批量 join。快照独立性：
-- item_id/source_id 不建外键——t_news_item 90 天保留清理（NewsRetentionJob）不连带，
-- 快照字段全冗余，源行清理后日报仍完整可读。幂等=digest_date UNIQUE+先删后插重建。
-- 窗口=[D-1 08:00, D 08:00) HKT 左闭右开（恰落 08:00:00.000 归下一期）。
-- 增量环境走 upgrades/v2.0.0/261003_02_news_daily_digest.sql
CREATE TABLE t_news_daily_digest (
  id           BIGSERIAL PRIMARY KEY,
  digest_date  DATE        NOT NULL,             -- HKT 日报日期=窗口闭端日（唯一键）
  window_start TIMESTAMP   NOT NULL,             -- 窗口起点（D-1 08:00 HKT）
  window_end   TIMESTAMP   NOT NULL,             -- 窗口闭端（D 08:00 HKT，开区间）
  intro_zh     TEXT,                              -- 导语（中文；LLM 或模板回退）
  intro_en     TEXT,                              -- 导语（英文）
  intro_source VARCHAR(8)  NOT NULL,              -- llm / fallback / empty
  item_count   INT         NOT NULL DEFAULT 0,    -- 快照条数（生成时刻口径）
  status       VARCHAR(16) NOT NULL DEFAULT 'published',  -- published（预留 rebuild_pending）
  build_time   TIMESTAMP   NOT NULL,              -- 本刊生成时刻
  create_time  TIMESTAMP   NOT NULL DEFAULT now(),
  update_time  TIMESTAMP   NOT NULL DEFAULT now(),
  CONSTRAINT uq_news_daily_digest_date UNIQUE (digest_date)
);
COMMENT ON TABLE t_news_daily_digest IS '资讯日报刊头（#212：HKT 日期唯一一刊；快照独立于 t_news_item 90 天清理——条目内容冗余在快照行表，本表只存刊头/导语/统计）';
COMMENT ON COLUMN t_news_daily_digest.digest_date IS 'HKT 日报日期=窗口闭端日：窗口=[前一 08:00, 本日 08:00) 左闭右开；publish_time 恰落 08:00:00.000 归属下一期';
COMMENT ON COLUMN t_news_daily_digest.intro_source IS '导语产出方式：llm=预算护栏内单次调用；fallback=LLM 失败/预算耗尽的固定模板（零新增调用）；empty=空刊模板（零调用）';
COMMENT ON COLUMN t_news_daily_digest.item_count IS '生成时刻的快照条数；读取期主动下架复检后的可见条数以接口实时过滤为准';
COMMENT ON COLUMN t_news_daily_digest.status IS 'published=现行刊；rebuild_pending=预留（主动下架降级态），当前默认回退路径=读取期零调用过滤+模板导语';

CREATE TABLE t_news_daily_digest_item (
  id                   BIGSERIAL PRIMARY KEY,
  digest_id            BIGINT       NOT NULL REFERENCES t_news_daily_digest(id) ON DELETE CASCADE,
  item_id              BIGINT       NOT NULL,    -- 溯源 ID（无外键：t_news_item 90 天清理不得连带）
  seq                  INT          NOT NULL,    -- 刊内序（1 起，确定性：publish_time DESC, id DESC）
  url                  VARCHAR(1024) NOT NULL,
  url_hash             VARCHAR(64),
  title_zh             VARCHAR(512),
  title_en             VARCHAR(512),
  summary_zh           TEXT,
  summary_en           TEXT,
  category             VARCHAR(32)  NOT NULL DEFAULT 'other',
  topic_slugs          TEXT,                      -- 逗号分隔快照（生成时刻关联）
  source_id            BIGINT,                    -- 无外键（信源注册表删除不连带）
  source_key           VARCHAR(64),
  source_platform      VARCHAR(16),
  source_official      BOOLEAN,
  source_display_name  VARCHAR(128),
  source_display_name_en VARCHAR(128),
  publish_time         TIMESTAMP,
  publish_time_precision VARCHAR(8) NOT NULL DEFAULT 'unknown',  -- date/datetime/unknown（#275；旧快照 unknown 不回填）
  CONSTRAINT uq_news_daily_digest_item UNIQUE (digest_id, item_id)
);
CREATE INDEX idx_news_daily_digest_item_digest ON t_news_daily_digest_item(digest_id);
COMMENT ON COLUMN t_news_daily_digest_item.publish_time_precision IS '发布时间精度快照（#275）：新快照冗余源行精度；旧快照 unknown 不回填；date 精度展示层只显日期';
COMMENT ON TABLE t_news_daily_digest_item IS '资讯日报条目快照（#212：ID+全展示字段冗余留存，防 90 天保留清理连带；(digest_id,item_id) 唯一=同刊内一源条目一行）';
COMMENT ON COLUMN t_news_daily_digest_item.item_id IS '溯源 t_news_item.id（快照独立性：无外键，源行被保留清理删除后快照仍完整；读取期仅当源行仍存在且 status 不为 published 时过滤失格）';
COMMENT ON COLUMN t_news_daily_digest_item.seq IS '刊内序（确定性选材冻结口径：publish_time DESC, id DESC 的全部动态序，非评分排序）';

CREATE TABLE t_site_feedback (
  id            BIGSERIAL PRIMARY KEY,
  content       TEXT        NOT NULL,
  contact       VARCHAR(100),
  client_ip     VARCHAR(64) NOT NULL,
  status        SMALLINT    NOT NULL DEFAULT 0,
  create_time   TIMESTAMP   NOT NULL DEFAULT now(),
  update_time   TIMESTAMP   NOT NULL DEFAULT now(),
  deleted       SMALLINT    DEFAULT 0
);
CREATE INDEX idx_site_feedback_create_time ON t_site_feedback (create_time);
CREATE INDEX idx_site_feedback_ip_day ON t_site_feedback (client_ip, create_time);
COMMENT ON TABLE t_site_feedback IS '站点反馈（doc 25）：匿名访客反馈箱，admin 后台分页查看处理；与上游消息维度反馈（t_message_feedback）语义隔离';
COMMENT ON COLUMN t_site_feedback.contact IS '选填联系方式（≤100 字），维护者想追问时有渠道';
COMMENT ON COLUMN t_site_feedback.client_ip IS '提交方客户端 IP（XFF 首值口径），IP 日限与滥用排查用；admin 列表脱敏展示';
COMMENT ON COLUMN t_site_feedback.status IS '0 未处理 / 1 已处理 / 2 忽略';

CREATE TABLE t_site_about (
  id              BIGINT   PRIMARY KEY,
  content         TEXT,
  content_en      TEXT,
  qr_image_url    VARCHAR(512),
  qr_image_url_alt VARCHAR(512),
  create_time     TIMESTAMP NOT NULL DEFAULT now(),
  update_time     TIMESTAMP NOT NULL DEFAULT now()
);
COMMENT ON TABLE t_site_about IS '关于页单行内容表（doc 25）：id 固定 1，service 层 upsert，零种子依赖';
COMMENT ON COLUMN t_site_about.content IS '关于页 markdown 内容（作者/项目介绍），维护者后台编辑';
COMMENT ON COLUMN t_site_about.content_en IS '关于页英文 markdown（可空）；空时前端英文档回落中文内容';
COMMENT ON COLUMN t_site_about.qr_image_url IS '赞赏二维码 URL（可空）；与 alt 同时为空时前端赞赏区整区不渲染';
COMMENT ON COLUMN t_site_about.qr_image_url_alt IS '第二张赞赏二维码 URL（可空）';

-- ===== 校历关键日期（#192，合同 r3；迁移=260930_key_date_ingest.sql）=====
CREATE TABLE t_key_date (
    id              BIGSERIAL PRIMARY KEY,
    uid             VARCHAR(64)  NOT NULL,
    academic_year   VARCHAR(7)   NOT NULL,
    term            VARCHAR(2)   NOT NULL,
    event_code      VARCHAR(48)  NOT NULL,
    audience_code   VARCHAR(64)  NOT NULL,
    semantic_slot   VARCHAR(64)  NOT NULL,
    source_key      VARCHAR(48)  NOT NULL,
    source_url      VARCHAR(512) NOT NULL,
    title_en        VARCHAR(256) NOT NULL,
    title_zh        VARCHAR(256),
    audience_text   VARCHAR(256),
    raw_text        TEXT         NOT NULL,
    provenance      VARCHAR(256) NOT NULL,
    precision       VARCHAR(16)  NOT NULL,
    date_start      DATE,
    date_end        DATE,
    fuzzy_hint      VARCHAR(64),
    status          VARCHAR(16)  NOT NULL,
    revision        INT          NOT NULL DEFAULT 1,
    change_summary  VARCHAR(512),
    first_seen_at   TIMESTAMP    NOT NULL,
    last_seen_at    TIMESTAMP    NOT NULL,
    withdrawn_at    TIMESTAMP,
    ics_export_state  VARCHAR(16),
    ics_last_dates    VARCHAR(64),
    CONSTRAINT uk_key_date_uid UNIQUE (uid),
    CONSTRAINT ck_key_date_term CHECK (term IN ('AY', 'S1', 'S2', 'SU')),
    CONSTRAINT ck_key_date_precision CHECK (precision IN ('exact-day', 'exact-range', 'onwards', 'fuzzy')),
    CONSTRAINT ck_key_date_status CHECK (status IN ('published', 'withdrawn', 'archived')),
    CONSTRAINT ck_key_date_fuzzy_no_date CHECK (precision <> 'fuzzy' OR (date_start IS NULL AND date_end IS NULL))
);
CREATE INDEX idx_key_date_withdraw_scan ON t_key_date(source_key, academic_year, status);
CREATE INDEX idx_key_date_display ON t_key_date(date_start) WHERE status = 'published';

CREATE TABLE t_key_date_source (
    id                    BIGSERIAL PRIMARY KEY,
    source_key            VARCHAR(48)  NOT NULL,
    source_url            VARCHAR(512) NOT NULL,
    role                  VARCHAR(16)  NOT NULL,
    enabled               CHAR(1)      NOT NULL DEFAULT '1',
    auto_state            VARCHAR(16)  NOT NULL DEFAULT 'active',
    degraded_streak       INT          NOT NULL DEFAULT 0,
    probe_ok_streak       INT          NOT NULL DEFAULT 0,
    coverage_academic_year VARCHAR(7),
    last_success_at       TIMESTAMP,
    last_complete_snapshot TEXT,
    last_diag             TEXT,
    updated_at            TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uk_key_date_source UNIQUE (source_key),
    CONSTRAINT ck_key_date_source_role CHECK (role IN ('writer', 'verifier')),
    CONSTRAINT ck_key_date_source_auto CHECK (auto_state IN ('active', 'auto_isolated'))
);

COMMENT ON TABLE t_key_date IS '校历关键日期事件（#192，合同 r3）：稳定身份 UID=六段语义数组 SHA-256（日期/标题不入键）；published/withdrawn/archived 分离；纯校验源（cal-exam-timetable）零事件行';
COMMENT ON COLUMN t_key_date.uid IS 'SHA-256(["polyu-keydate-v1",学年,学期,事件码,受众码,语义阶段]) 小写 64 hex；改期不换 UID、跨学年新 UID；ICS 前缀由导出层拼接';
COMMENT ON COLUMN t_key_date.provenance IS '原始行/单元格引用列表（逗号分隔 locator，如 r12,r14）——一对多/多对一出处的证据闭合，不比较行数=事件数';
COMMENT ON COLUMN t_key_date.precision IS 'exact-day=精确日 / exact-range=精确区间（两端包含、库内含端）/ onwards=开放起点（首批不进 ICS）/ fuzzy=模糊窗（原文桶落 fuzzy_hint，不伪造具体日、不倒计时、不进 ICS）';
COMMENT ON COLUMN t_key_date.status IS 'published=发布 / withdrawn=限定覆盖域撤回（保留 UID 与历史，不物理删除；仅完整候选可撤回）/ archived=正常过期展示态（≠官方取消，新学年页面不批量取消旧学年）';
COMMENT ON COLUMN t_key_date.ics_export_state IS 'ICS 导出历史最小承载（#194 消费）：NULL=从未导出精确事件 / exported=当前导出中 / cancelled=已发取消——精确变模糊须据此发取消，不留旧精确提醒';
COMMENT ON TABLE t_key_date_source IS '校历源状态（合同§6）：人工启停（enabled，不自动复活）与自动隔离（auto_state，3 轮退化隔离、两次完整探测复归）严格区分；verifier 零事件写径';
COMMENT ON COLUMN t_key_date_source.last_success_at IS '最近完整同步时间（完整候选原子发布后才刷新；≠内容更新时间；退化轮不刷新）';
COMMENT ON COLUMN t_key_date_source.last_complete_snapshot IS '最后完整候选版本快照（JSON）；退化不覆盖——保留用于比对与恢复判定';

-- v2.0.0 260930 会话唯一索引改全量（收编上游 9fd1bda0 的存量库配套）
-- 上游把该变更直接写进 260812_agent_engine.sql（只对全新装库生效）；已应用过 260812 的库执行本脚本置换。
-- 语义：会话身份不可复用——逻辑删除后唯一键仍占用，新会话必须用服务端生成的新 ID（9fd1bda0 起代码保证）。
-- 前提：无跨删除态重复键。生产预检 2026-09-30：t_agent_conversation 按 (conversation_id, user_id) 分组重复 0 行。
DROP INDEX IF EXISTS uk_agent_conversation_user;
CREATE UNIQUE INDEX uk_agent_conversation_user ON t_agent_conversation (conversation_id, user_id);

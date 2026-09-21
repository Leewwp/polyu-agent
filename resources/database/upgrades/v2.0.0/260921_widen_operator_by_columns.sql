-- 260921 操作人列放宽：11 表 22 列 *_by 对齐 t_user.username VARCHAR(255)
-- 背景：260909 因「注册用户 username=email（RFC 5321 上限 254）」把 t_user.username 64→255，
-- 但所有承载 username 的操作人列漏网仍为 VARCHAR(20)（比上游 MySQL schema 的 64 与数据源 254 双窄）：
-- username ≥21 字符的账号进管理/配置面即 value too long 整单失败（#94 施工冒烟撞出）。
-- 仅放宽长度，不动约束/默认值/注释；纯 DDL 可任意时序重放。
ALTER TABLE t_knowledge_base        ALTER COLUMN created_by TYPE VARCHAR(255);
ALTER TABLE t_knowledge_base        ALTER COLUMN updated_by TYPE VARCHAR(255);
ALTER TABLE t_knowledge_document    ALTER COLUMN created_by TYPE VARCHAR(255);
ALTER TABLE t_knowledge_document    ALTER COLUMN updated_by TYPE VARCHAR(255);
ALTER TABLE t_knowledge_chunk       ALTER COLUMN created_by TYPE VARCHAR(255);
ALTER TABLE t_knowledge_chunk       ALTER COLUMN updated_by TYPE VARCHAR(255);
ALTER TABLE t_intent_node           ALTER COLUMN create_by  TYPE VARCHAR(255);
ALTER TABLE t_intent_node           ALTER COLUMN update_by  TYPE VARCHAR(255);
ALTER TABLE t_query_term_mapping    ALTER COLUMN create_by  TYPE VARCHAR(255);
ALTER TABLE t_query_term_mapping    ALTER COLUMN update_by  TYPE VARCHAR(255);
ALTER TABLE t_agent_profile         ALTER COLUMN create_by  TYPE VARCHAR(255);
ALTER TABLE t_agent_profile         ALTER COLUMN update_by  TYPE VARCHAR(255);
ALTER TABLE t_agent_prompt          ALTER COLUMN create_by  TYPE VARCHAR(255);
ALTER TABLE t_agent_prompt          ALTER COLUMN update_by  TYPE VARCHAR(255);
ALTER TABLE t_agent_skill           ALTER COLUMN create_by  TYPE VARCHAR(255);
ALTER TABLE t_agent_skill           ALTER COLUMN update_by  TYPE VARCHAR(255);
ALTER TABLE t_ingestion_pipeline    ALTER COLUMN created_by TYPE VARCHAR(255);
ALTER TABLE t_ingestion_pipeline    ALTER COLUMN updated_by TYPE VARCHAR(255);
ALTER TABLE t_ingestion_pipeline_node ALTER COLUMN created_by TYPE VARCHAR(255);
ALTER TABLE t_ingestion_pipeline_node ALTER COLUMN updated_by TYPE VARCHAR(255);
ALTER TABLE t_ingestion_task        ALTER COLUMN created_by TYPE VARCHAR(255);
ALTER TABLE t_ingestion_task        ALTER COLUMN updated_by TYPE VARCHAR(255);
-- 排除 t_agent_memory.superseded_by：列注释「取代者ID」，与 ID 列同宽语义，非 username 承载列

-- K2c 失败滞回：t_knowledge_document_schedule 增连续抓取失败计数与数据陈旧诊断标记
-- 连续抓取失败达到阈值（默认 3，rag.knowledge.schedule.failure-hysteresis-threshold）才禁用调度；
-- 之前的失败仅置 data_stale=1 作调度诊断，检索链路不读本表、行为不变
ALTER TABLE t_knowledge_document_schedule
    ADD COLUMN IF NOT EXISTS consecutive_failures INT DEFAULT 0;
ALTER TABLE t_knowledge_document_schedule
    ADD COLUMN IF NOT EXISTS data_stale SMALLINT DEFAULT 0;

-- v2.0.0 261005 长期记忆撤回/清空的 HITL 审批（#278）
-- 前置脚本：260927_agent_memory_and_compaction.sql。
-- 执行顺序：先执行本迁移再滚动新镜像（新代码读写新列；旧代码不认识这些列，列均可空，先滚后跑也安全）。
-- 在线可执行：只新增可空列与索引，不改既有行语义；存量行 plan_* 均为 NULL，不影响任何旧查询。
--
-- 计划状态机（t_agent_memory_extraction.status 新增值，extractionId 即 operationId）：
--   PROCESSING →(冻结受审批次) PENDING_APPROVAL →(确认端点·用户同意，绑 toolCallId+确认消息) APPROVED
--   APPROVED →(执行短事务，原子提交记忆变更与结果) APPLIED
--   PENDING_APPROVAL / APPROVED →(用户拒绝) REJECTED / (30 分钟到期按需结算) EXPIRED / (版本失效·源会话删除) INVALIDATED
-- APPLIED、REJECTED、EXPIRED、INVALIDATED 与 WRITTEN、NOOP、DROPPED 一样是终态并推进水位：
-- 受审区间一经裁决即消费，防止下一轮抽取把同一条「忘记/清空」指令再判一次。
-- PENDING_APPROVAL/APPROVED 不进任何水位查询，也不被 recycleStale（只收 PROCESSING）重领。

ALTER TABLE t_agent_memory_extraction
    ADD COLUMN IF NOT EXISTS plan_json TEXT,
    ADD COLUMN IF NOT EXISTS plan_expires_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS expected_revision BIGINT,
    ADD COLUMN IF NOT EXISTS plan_tool_call_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS plan_confirm_message_id VARCHAR(20),
    ADD COLUMN IF NOT EXISTS plan_result_json TEXT;

-- 每用户至多一个待审（或已批未执行）计划：与 PROCESSING 唯一索引同为用户级互斥面
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_memory_extraction_plan_pending
    ON t_agent_memory_extraction (user_id)
    WHERE status IN ('PENDING_APPROVAL', 'APPROVED');

COMMENT ON COLUMN t_agent_memory_extraction.status IS
    '抽取状态：PROCESSING/WRITTEN/NOOP/DROPPED/CONFLICT/PENDING_APPROVAL/APPROVED/APPLIED/REJECTED/EXPIRED/INVALIDATED';
COMMENT ON COLUMN t_agent_memory_extraction.plan_json IS
    '冻结的记忆变更计划快照（受审批次决策+目标条目内容+消息范围+快照凭证），仅审批链路读写';
COMMENT ON COLUMN t_agent_memory_extraction.plan_expires_at IS
    '计划有效期截止（冻结时刻+30 分钟），读取/确认/执行/新请求时按需结算到期';
COMMENT ON COLUMN t_agent_memory_extraction.expected_revision IS
    '冻结时刻的记忆版本号，执行时复核，跨会话版本变化即失效';
COMMENT ON COLUMN t_agent_memory_extraction.plan_tool_call_id IS
    '批准绑定的 apply_memory_change 工具调用ID，执行时校验';
COMMENT ON COLUMN t_agent_memory_extraction.plan_confirm_message_id IS
    '批准所在的确认卡消息ID，审计用';
COMMENT ON COLUMN t_agent_memory_extraction.plan_result_json IS
    'APPLIED 后的执行结果快照，重复执行读原结果不重复提交';

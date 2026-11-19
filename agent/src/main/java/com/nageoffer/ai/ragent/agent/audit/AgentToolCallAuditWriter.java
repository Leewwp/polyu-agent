/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.agent.audit;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMessageDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.ragent.agent.dto.AgentBlock;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工具调用审计旁路 writer（#486）：挂在消息终态落库后的旁路钩子上，异步批量写 t_agent_tool_call。
 * 纪律：写失败仅 WARN 不阻断主链、不改消息落库时序；flag 关态零采集零落行；
 * 同消息重算（拒卡改写补算）按 (message_id, block_index) upsert 覆盖，键不变即同一行。
 */
@Slf4j
@Component
public class AgentToolCallAuditWriter {

    /**
     * 列序与 UPSERT_SQL 的 VALUES 一一对应
     */
    static final String UPSERT_SQL = """
            INSERT INTO t_agent_tool_call (id, conversation_id, user_id, role, message_id, block_index,
                run_id, tool_name, tool_call_id, batch_id, decision, status, input_digest, input_excerpt, duration_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (message_id, block_index) DO UPDATE SET
                decision = EXCLUDED.decision,
                status = EXCLUDED.status,
                duration_ms = EXCLUDED.duration_ms,
                tool_name = EXCLUDED.tool_name,
                run_id = EXCLUDED.run_id,
                batch_id = EXCLUDED.batch_id,
                role = COALESCE(EXCLUDED.role, t_agent_tool_call.role),
                input_digest = COALESCE(EXCLUDED.input_digest, t_agent_tool_call.input_digest),
                input_excerpt = COALESCE(EXCLUDED.input_excerpt, t_agent_tool_call.input_excerpt)
            """;

    private static final int QUEUE_CAPACITY = 500;

    private final JdbcTemplate jdbcTemplate;
    private final AgentMessageMapper messageMapper;
    private final AgentProperties properties;
    private final Executor executor;
    private final AtomicLong dropped = new AtomicLong();

    @Autowired
    public AgentToolCallAuditWriter(JdbcTemplate jdbcTemplate, AgentMessageMapper messageMapper,
                                    AgentProperties properties) {
        this(jdbcTemplate, messageMapper, properties, defaultExecutor());
    }

    AgentToolCallAuditWriter(JdbcTemplate jdbcTemplate, AgentMessageMapper messageMapper,
                             AgentProperties properties, Executor executor) {
        this.jdbcTemplate = jdbcTemplate;
        this.messageMapper = messageMapper;
        this.properties = properties;
        this.executor = executor;
    }

    /**
     * settle 主路径旁路钩子：消息终态落库成功后由 AgentStreamEventBridge 调用（messageId 非空才可能到这里）
     */
    public void recordSettled(String conversationId, String userId, String role, String messageId,
                              List<AgentBlock> blocks, AgentToolExecutionFacts facts) {
        if (!enabled()) {
            return;
        }
        if (StrUtil.isBlank(messageId) || blocks == null || blocks.isEmpty()) {
            return;
        }
        Map<String, AgentToolExecutionFacts.ToolInputSummary> summaries = summariesSnapshot(facts, blocks);
        submit("settle", () -> upsertAll(AgentToolCallAuditRowFactory.rowsOf(
                new AgentToolCallAuditRowFactory.RunMetadata(conversationId, userId, role, messageId),
                blocks,
                id -> summaries.get(StrUtil.blankToDefault(id, null)))));
    }

    /**
     * 拒卡结算改写（awaiting→denied）后的补充触发点：重读改写后的消息 blocks 补算行集；
     * 摘要源为空，首写 digest/excerpt 由 upsert 的 COALESCE 保住
     */
    public void rebuildMessage(String conversationId, String userId, String messageId, String role) {
        if (!enabled()) {
            return;
        }
        if (StrUtil.hasBlank(conversationId, userId, messageId)) {
            return;
        }
        submit("rebuild", () -> {
            AgentMessageDO message = messageMapper.selectOne(Wrappers.lambdaQuery(AgentMessageDO.class)
                    .eq(AgentMessageDO::getId, messageId)
                    .eq(AgentMessageDO::getConversationId, conversationId)
                    .eq(AgentMessageDO::getUserId, userId)
                    .last("LIMIT 1"));
            if (message == null) {
                log.warn("审计补算未找到消息, conversationId: {}, messageId: {}", conversationId, messageId);
                return;
            }
            upsertAll(AgentToolCallAuditRowFactory.rowsOf(
                    new AgentToolCallAuditRowFactory.RunMetadata(conversationId, userId, role, messageId),
                    message.getBlocks(),
                    id -> null));
        });
    }

    /**
     * 行集 → 单条批量 upsert；同包测试与回放共用
     */
    void upsertAll(List<AgentToolCallAuditRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        List<Object[]> batch = new ArrayList<>(rows.size());
        for (AgentToolCallAuditRow row : rows) {
            batch.add(new Object[]{
                    IdUtil.getSnowflakeNextIdStr(),
                    row.conversationId(),
                    row.userId(),
                    row.role(),
                    row.messageId(),
                    row.blockIndex(),
                    row.runId(),
                    row.toolName(),
                    row.toolCallId(),
                    row.batchId(),
                    row.decision(),
                    row.status(),
                    row.inputDigest(),
                    row.inputExcerpt(),
                    row.durationMs()});
        }
        jdbcTemplate.batchUpdate(UPSERT_SQL, batch);
    }

    private Map<String, AgentToolExecutionFacts.ToolInputSummary> summariesSnapshot(
            AgentToolExecutionFacts facts, List<AgentBlock> blocks) {
        Map<String, AgentToolExecutionFacts.ToolInputSummary> summaries = new java.util.HashMap<>();
        if (facts == null) {
            return summaries;
        }
        for (AgentBlock block : blocks) {
            if (block == null || !AgentBlock.KIND_TOOL.equals(block.getKind())) {
                continue;
            }
            // blank toolCallId（端点不回 id）不跳过：facts 内部把 blank 归一到同一份 "unknown" 事实，
            // 并行 blank 块共享一份摘要优于全部留空；map 键同步归一到 null，与取数点 blankToDefault 对齐
            String key = StrUtil.blankToDefault(block.getToolCallId(), null);
            AgentToolExecutionFacts.ToolInputSummary summary =
                    facts.toolFact(block.getToolCallId()).inputSummary();
            if (summary != null) {
                summaries.put(key, summary);
            }
        }
        return summaries;
    }

    private boolean enabled() {
        return properties.getAudit() != null && properties.getAudit().isEnabled();
    }

    /**
     * 提交旁路任务：队列满丢弃并 WARN（审计是尽力而为面，绝不反压主链）
     */
    private void submit(String trigger, Runnable task) {
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    log.warn("工具调用审计写入失败({}), 已丢弃不阻断主链", trigger, e);
                }
            });
        } catch (Exception e) {
            long total = dropped.incrementAndGet();
            log.warn("工具调用审计任务提交失败({}), 累计丢弃 {} 条", trigger, total, e);
        }
    }

    private static Executor defaultExecutor() {
        return new ThreadPoolExecutor(0, 1, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "agent-tool-audit");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /**
     * 停机排水：优雅关闭 → 最多等 3s 排干队列内审计行 → 超时强制关（尽力而为面，
     * 排不干的行随停机丢弃，与队列满丢弃同一纪律——绝不反压停机）
     */
    @PreDestroy
    void shutdown() {
        if (!(executor instanceof ThreadPoolExecutor pool)) {
            return;
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

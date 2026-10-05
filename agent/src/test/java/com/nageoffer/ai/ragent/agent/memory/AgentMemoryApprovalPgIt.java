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

package com.nageoffer.ai.ragent.agent.memory;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMemoryControlDO;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMemoryDO;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMemoryExtractionDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryControlMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryExtractionMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.ragent.framework.database.MyMetaObjectHandler;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * HITL 审批计划 SQL 面与执行短事务的真库对版（#278）：可复跑的至多一次/原子回滚证据，
 * 外加冻结口会话存在性守卫（软删会话不再产生孤立计划）、批准绑定/重绑边界、
 * 到期结算与待审互斥的部分唯一索引。
 *
 * <p>门控：默认跳过，仓库既有 PgIT 约定（同 PublishTimePrecisionPgIt）。一次性容器：
 * {@code docker run -d --name hitl278-pg -e POSTGRES_PASSWORD=postgres
 * -e POSTGRES_DB=ragent -p 5435:5432 pgvector/pgvector:pg16} 后
 * {@code ./mvnw -pl agent test -Dtest=AgentMemoryApprovalPgIt -Dpolyu.pg.it=1
 * -Dpolyu.pg.it.url=jdbc:postgresql://127.0.0.1:5435/ragent}。
 * （70-执行进度 文档曾以「HITL_PG_URL 环境变量门控」描述本项证据面，实际交付按本约定执行。）
 *
 * <p>事务口径：仓储直接构造（不经 Spring 代理，其 @Transactional 不自激活），
 * 由外层 TransactionTemplate + SpringManagedTransactionFactory 提供真实库事务——
 * executeApprovedPlan 的全部语句与外层事务同生死，正好用强制回滚验证原子性。
 */
@EnabledIfSystemProperty(named = "polyu.pg.it", matches = "1")
class AgentMemoryApprovalPgIt {

    private static final String PREFIX = "it-hitl-";
    private static javax.sql.DataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;
    private static SqlSessionTemplate sqlSessionTemplate;
    private static TransactionTemplate transactionTemplate;
    private static DataSourceTransactionManager transactionManager;
    private static AgentMemoryRepository repository;
    private static AgentMemoryExtractionMapper extractionMapper;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        PGSimpleDataSource pgDataSource = new PGSimpleDataSource();
        dataSource = pgDataSource;
        pgDataSource.setURL(System.getProperty("polyu.pg.it.url", "jdbc:postgresql://127.0.0.1:5435/ragent"));
        pgDataSource.setUser(System.getProperty("polyu.pg.it.user", "postgres"));
        pgDataSource.setPassword(System.getProperty("polyu.pg.it.pass", "postgres"));
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS t_agent_conversation ("
                    + "id VARCHAR(20) NOT NULL PRIMARY KEY, conversation_id VARCHAR(20) NOT NULL,"
                    + "user_id VARCHAR(20) NOT NULL, title VARCHAR(128) NOT NULL, last_time TIMESTAMP,"
                    + "create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                    + "deleted SMALLINT DEFAULT 0)");
            statement.execute("CREATE TABLE IF NOT EXISTS t_agent_memory ("
                    + "id VARCHAR(20) NOT NULL PRIMARY KEY, user_id VARCHAR(20) NOT NULL,"
                    + "content VARCHAR(500) NOT NULL, source_type VARCHAR(16) NOT NULL,"
                    + "invalid_at TIMESTAMP, superseded_by VARCHAR(20),"
                    + "create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            statement.execute("CREATE TABLE IF NOT EXISTS t_agent_memory_extraction ("
                    + "id VARCHAR(20) NOT NULL PRIMARY KEY, user_id VARCHAR(20) NOT NULL,"
                    + "conversation_id VARCHAR(20) NOT NULL, from_message_id VARCHAR(20) NOT NULL,"
                    + "to_message_id VARCHAR(20) NOT NULL, status VARCHAR(32) NOT NULL,"
                    + "trigger_type VARCHAR(16) NOT NULL, decision_count INTEGER NOT NULL DEFAULT 0,"
                    + "attempt_count INTEGER NOT NULL DEFAULT 1, plan_json TEXT, plan_expires_at TIMESTAMP,"
                    + "expected_revision BIGINT, plan_tool_call_id VARCHAR(64), plan_confirm_message_id VARCHAR(20),"
                    + "plan_result_json TEXT, create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "settle_time TIMESTAMP)");
            statement.execute("CREATE TABLE IF NOT EXISTS t_agent_memory_control ("
                    + "user_id VARCHAR(20) NOT NULL PRIMARY KEY, revision BIGINT NOT NULL DEFAULT 0,"
                    + "create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_memory_extraction_processing"
                    + " ON t_agent_memory_extraction (user_id) WHERE status = 'PROCESSING'");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_memory_extraction_plan_pending"
                    + " ON t_agent_memory_extraction (user_id) WHERE status IN ('PENDING_APPROVAL', 'APPROVED')");
            statement.execute("DELETE FROM t_agent_memory_extraction WHERE user_id LIKE '" + PREFIX + "%'");
            statement.execute("DELETE FROM t_agent_memory WHERE user_id LIKE '" + PREFIX + "%'");
            statement.execute("DELETE FROM t_agent_memory_control WHERE user_id LIKE '" + PREFIX + "%'");
            statement.execute("DELETE FROM t_agent_conversation WHERE user_id LIKE '" + PREFIX + "%'");
        }
        MybatisConfiguration configuration = new MybatisConfiguration();
        // Spring 托管事务：mapper 语句加入 TransactionTemplate 的事务，原子性按真实库事务验证
        configuration.setEnvironment(new Environment("hitl-it", new SpringManagedTransactionFactory(), dataSource));
        GlobalConfigUtils.getGlobalConfig(configuration).setMetaObjectHandler(new MyMetaObjectHandler());
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, AgentMemoryDO.class);
        TableInfoHelper.initTableInfo(assistant, AgentMemoryExtractionDO.class);
        TableInfoHelper.initTableInfo(assistant, AgentMemoryControlDO.class);
        configuration.addMapper(AgentMemoryMapper.class);
        configuration.addMapper(AgentMemoryExtractionMapper.class);
        configuration.addMapper(AgentMemoryControlMapper.class);
        sqlSessionFactory = new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(configuration);
        sqlSessionTemplate = new SqlSessionTemplate(sqlSessionFactory);
        transactionManager = new DataSourceTransactionManager(dataSource);
        transactionTemplate = new TransactionTemplate(transactionManager);
        extractionMapper = sqlSessionTemplate.getMapper(AgentMemoryExtractionMapper.class);
        repository = new AgentMemoryRepository(sqlSessionTemplate.getMapper(AgentMemoryMapper.class),
                extractionMapper, sqlSessionTemplate.getMapper(AgentMemoryControlMapper.class),
                org.mockito.Mockito.mock(AgentMessageMapper.class), new AgentMemoryProperties());
    }

    @AfterAll
    static void cleanUp() throws Exception {
        if (dataSource != null) {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("DELETE FROM t_agent_memory_extraction WHERE user_id LIKE '" + PREFIX + "%'");
                statement.execute("DELETE FROM t_agent_memory WHERE user_id LIKE '" + PREFIX + "%'");
                statement.execute("DELETE FROM t_agent_memory_control WHERE user_id LIKE '" + PREFIX + "%'");
                statement.execute("DELETE FROM t_agent_conversation WHERE user_id LIKE '" + PREFIX + "%'");
            } catch (Exception ignore) {
                // 容器一次性，清理失败不阻断
            }
        }
    }

    /**
     * 冻结口会话存在性守卫：源会话被软删（@TableLogic deleted=1）后，judge 结束的冻结必须落空，
     * 不产生引用已删会话、仍可被领取确认执行的孤立计划；活会话照常冻结
     */
    @Test
    void freezeShouldRequireAliveSourceConversation() {
        String userId = PREFIX + "u1";
        insertConversation(userId, "c-live", 0);
        insertConversation(userId, "c-dead", 1);

        AgentMemoryExtractionDO live = insertProcessingExtraction("e-live", userId, "c-live");
        assertThat(extractionMapper.freezePlan(live.getId(), userId, planOf(userId, "c-live").toJson(), 0L, 30))
                .isEqualTo(1);
        assertThat(statusOf("e-live")).isEqualTo("PENDING_APPROVAL");

        AgentMemoryExtractionDO dead = insertProcessingExtraction("e-dead", userId, "c-dead");
        assertThat(extractionMapper.freezePlan(dead.getId(), userId, planOf(userId, "c-dead").toJson(), 0L, 30))
                .as("软删会话上的冻结必须落空")
                .isEqualTo(0);
        assertThat(statusOf("e-dead")).isEqualTo("PROCESSING");
    }

    /**
     * 批准绑定与重绑边界（决议 9+10 裁决落点）：待审/已批未执行可在有效期内幂等重绑（恢复路径）；
     * APPLIED 永不重批（approve 条件 IN 不含 APPLIED），重复执行只回放原结果
     */
    @Test
    void approvalShouldRebindWhilePendingAndNeverAfterApplied() {
        String userId = PREFIX + "u2";
        insertConversation(userId, "c-u2", 0);
        repository.ensureControl(userId);
        AgentMemoryExtractionDO extraction = insertProcessingExtraction("e-u2", userId, "c-u2");
        assertThat(extractionMapper.freezePlan(extraction.getId(), userId,
                planOf(userId, "c-u2").toJson(), 0L, 30)).isEqualTo(1);

        assertThat(extractionMapper.approvePlan("e-u2", userId, "call-a", "m-a")).isEqualTo(1);
        assertThat(extractionMapper.approvePlan("e-u2", userId, "call-b", "m-b"))
                .as("批准后未执行：新确认卡上再次同意=改绑（决议 10 恢复路径）")
                .isEqualTo(1);

        AgentMemoryPlanExecution execution = repository.executeApprovedPlan("e-u2", userId, "call-b");
        assertThat(execution.outcome()).isEqualTo(AgentMemoryPlanExecution.Outcome.APPLIED);
        assertThat(statusOf("e-u2")).isEqualTo("APPLIED");
        assertThat(activeMemoryCount(userId)).isEqualTo(1);

        assertThat(extractionMapper.approvePlan("e-u2", userId, "call-c", "m-c"))
                .as("已执行的计划不得重绑（决议 9：不能套用旧批准）")
                .isEqualTo(0);
        assertThat(rowOf("e-u2").getPlanToolCallId()).isEqualTo("call-b");

        AgentMemoryPlanExecution replay = repository.executeApprovedPlan("e-u2", userId, "call-b");
        assertThat(replay.outcome()).isEqualTo(AgentMemoryPlanExecution.Outcome.REPLAYED);
        assertThat(replay.resultJson()).isEqualTo(execution.resultJson());
        assertThat(activeMemoryCount(userId)).as("回放不得再动记忆").isEqualTo(1);
    }

    /**
     * 至多一次：同一已批计划两路并发执行，控制行 FOR UPDATE 串行化，
     * 恰一路 APPLIED，另一路回放原结果，记忆只变更一次
     */
    @Test
    void concurrentExecutionMustApplyAtMostOnce() throws Exception {
        String userId = PREFIX + "u3";
        insertConversation(userId, "c-u3", 0);
        repository.ensureControl(userId);
        AgentMemoryExtractionDO extraction = insertProcessingExtraction("e-u3", userId, "c-u3");
        assertThat(extractionMapper.freezePlan(extraction.getId(), userId,
                planOf(userId, "c-u3").toJson(), 0L, 30)).isEqualTo(1);
        assertThat(extractionMapper.approvePlan("e-u3", userId, "call-x", "m-x")).isEqualTo(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<AgentMemoryPlanExecution> first = pool.submit(
                    () -> runInTx(() -> repository.executeApprovedPlan("e-u3", userId, "call-x"), start));
            Future<AgentMemoryPlanExecution> second = pool.submit(
                    () -> runInTx(() -> repository.executeApprovedPlan("e-u3", userId, "call-x"), start));
            start.countDown();
            AgentMemoryPlanExecution a = first.get(60, TimeUnit.SECONDS);
            AgentMemoryPlanExecution b = second.get(60, TimeUnit.SECONDS);
            long applied = List.of(a, b).stream()
                    .filter(execution -> execution.outcome() == AgentMemoryPlanExecution.Outcome.APPLIED).count();
            long replayed = List.of(a, b).stream()
                    .filter(execution -> execution.outcome() == AgentMemoryPlanExecution.Outcome.REPLAYED).count();
            assertThat(applied).as("恰一路真正执行").isEqualTo(1);
            assertThat(replayed).as("另一路回放原结果").isEqualTo(1);
            assertThat(activeMemoryCount(userId)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 原子回滚：执行短事务中途失败整体回滚——记忆变更、结果快照、终态结算、版本号一个都不留
     */
    @Test
    void executionMustRollBackAtomicallyOnFailure() {
        String userId = PREFIX + "u4";
        insertConversation(userId, "c-u4", 0);
        repository.ensureControl(userId);
        AgentMemoryExtractionDO extraction = insertProcessingExtraction("e-u4", userId, "c-u4");
        assertThat(extractionMapper.freezePlan(extraction.getId(), userId,
                planOf(userId, "c-u4").toJson(), 0L, 30)).isEqualTo(1);
        assertThat(extractionMapper.approvePlan("e-u4", userId, "call-r", "m-r")).isEqualTo(1);
        long revisionBefore = repositoryControlRevision(userId);

        assertThrows(RuntimeException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            repository.executeApprovedPlan("e-u4", userId, "call-r");
            throw new RuntimeException("模拟执行后提交前崩溃");
        }));

        assertThat(statusOf("e-u4")).as("结算必须随事务回滚").isEqualTo("APPROVED");
        assertThat(rowOf("e-u4").getPlanResultJson()).isNull();
        assertThat(activeMemoryCount(userId)).as("记忆变更必须随事务回滚").isZero();
        assertThat(repositoryControlRevision(userId)).as("版本号必须随事务回滚").isEqualTo(revisionBefore);

        // 回滚后原批准仍可执行：恢复路径以同一事务面落账
        AgentMemoryPlanExecution retry = repository.executeApprovedPlan("e-u4", userId, "call-r");
        assertThat(retry.outcome()).isEqualTo(AgentMemoryPlanExecution.Outcome.APPLIED);
        assertThat(activeMemoryCount(userId)).isEqualTo(1);
    }

    /**
     * 到期结算与待审互斥：过期待审行按需结成 EXPIRED 且不可再批；部分唯一索引拦住同用户第二个待审行
     */
    @Test
    void expiredPlansSettleOnceAndPendingMutexHolds() throws Exception {
        String userId = PREFIX + "u5";
        insertConversation(userId, "c-u5", 0);
        AgentMemoryExtractionDO extraction = insertProcessingExtraction("e-u5", userId, "c-u5");
        assertThat(extractionMapper.freezePlan(extraction.getId(), userId,
                planOf(userId, "c-u5").toJson(), 0L, 30)).isEqualTo(1);
        exec("UPDATE t_agent_memory_extraction SET plan_expires_at = CURRENT_TIMESTAMP"
                + " - INTERVAL '1 minute' WHERE id = 'e-u5'");
        assertThat(extractionMapper.approvePlan("e-u5", userId, "call-e", "m-e"))
                .as("过期行不许批").isEqualTo(0);
        assertThat(extractionMapper.expireStalePlans(userId)).isEqualTo(1);
        assertThat(statusOf("e-u5")).isEqualTo("EXPIRED");
        assertThat(extractionMapper.expireStalePlans(userId)).as("结算幂等").isZero();

        // 待审互斥：首行仍在待审时直插第二行，必须被部分唯一索引拦下（DB 级，与应用条件更新正交）
        String mutexUser = PREFIX + "u6";
        insertPendingRowDirect(mutexUser, "e-mutex-1");
        // 唯一索引是 DB 级约束：语句执行即抛（org.postgresql.util.PSQLException 包装 P0301/P0001）
        assertThat(assertThrows(Exception.class, () -> insertPendingRowDirect(mutexUser, "e-mutex-2")))
                .as("同用户第二个待审行必须被部分唯一索引拦下")
                .hasMessageContaining("uk_agent_memory_extraction_plan_pending");
    }

    // ---- 数据与工具 ----

    /** 在独立事务里跑（并发用例两路各自成事务，靠 latch 对齐起跑） */
    private AgentMemoryPlanExecution runInTx(java.util.function.Supplier<AgentMemoryPlanExecution> body,
                                             CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return transactionTemplate.execute(status -> body.get());
    }

    private AgentMemoryPlan planOf(String userId, String conversationId) {
        return new AgentMemoryPlan("e-plan", userId, conversationId, "m-from", "m-to",
                2, 0, 0L, extractionMapper.selectWatermark(userId), "FLUSH",
                List.of(AgentMemoryDecision.clear(), AgentMemoryDecision.add("清空后要记的新事实")),
                java.util.Map.of());
    }

    private void insertConversation(String userId, String conversationId, int deleted) {
        exec("INSERT INTO t_agent_conversation (id, conversation_id, user_id, title, deleted)"
                + " VALUES ('" + PREFIX + "cv-" + conversationId + "', '" + conversationId + "', '"
                + userId + "', 'it', " + deleted + ")");
    }

    private AgentMemoryExtractionDO insertProcessingExtraction(String id, String userId, String conversationId) {
        AgentMemoryExtractionDO extraction = AgentMemoryExtractionDO.builder()
                .id(id).userId(userId).conversationId(conversationId)
                .fromMessageId(PREFIX + "m-from").toMessageId(PREFIX + "m-to")
                .status(com.nageoffer.ai.ragent.agent.enums.AgentMemoryExtractionStatus.PROCESSING.name())
                .triggerType("FLUSH").attemptCount(1)
                .build();
        sqlSessionTemplate.getMapper(AgentMemoryExtractionMapper.class).insert(extraction);
        return extraction;
    }

    /** 绕过冻结口直插待审行：验证部分唯一索引这道 DB 级互斥 */
    private void insertPendingRowDirect(String userId, String id) {
        exec("INSERT INTO t_agent_memory_extraction (id, user_id, conversation_id,"
                + " from_message_id, to_message_id, status, trigger_type) VALUES ('" + id + "', '" + userId
                + "', 'c-mutex', 'm', 'm', 'PENDING_APPROVAL', 'FLUSH')");
    }

    /** 裸 JDBC（自动提交，独立于 Spring 事务）：门面语句与断言读不走 SqlSessionTemplate——
     *  经模板 getConnection() 拿到的连接会随模板方法返回被关闭，事务提交时已成死连接 */
    private static void exec(String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String statusOf(String id) {
        return rowOf(id).getStatus();
    }

    private AgentMemoryExtractionDO rowOf(String id) {
        return extractionMapper.selectById(id);
    }

    private long activeMemoryCount(String userId) {
        return repository.listActiveItems(userId).size();
    }

    private long repositoryControlRevision(String userId) {
        try (Connection connection = dataSource.getConnection();
             var rs = connection.createStatement().executeQuery(
                     "SELECT revision FROM t_agent_memory_control WHERE user_id = '" + userId + "'")) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

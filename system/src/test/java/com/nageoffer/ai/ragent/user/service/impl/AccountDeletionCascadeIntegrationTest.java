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

package com.nageoffer.ai.ragent.user.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.nageoffer.ai.ragent.share.ShareKind;
import com.nageoffer.ai.ragent.user.schema.PgSchemaFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * 账号硬删级联可选真库集成测试（#545）：在按现行 schema 文件建出的库上执行 {@link AccountDeletionCascade#purge}，
 * 证明硬删完成后该用户在所有带 user_id 列的表里不留行，名下的分享快照行（含已撤销的）一并清空，后续步骤失败时
 * 已执行的语句随事务回滚。设置了系统属性 {@value #JDBC_URL_PROPERTY}（JDBC 连接串）才运行，缺省跳过、不进 CI；
 * CI 上的覆盖面由 {@link AccountDeletionCascadeTest} 的 schema 覆盖断言守住。
 *
 * <p>两种入参形态各跑一遍，三条调用路径共用同一个级联：带邮箱的 (userId, email) 对应管理员删号与
 * 注销冷静期到期硬删，(userId, null) 对应 guest 到期清理。
 *
 * <p>隔离：全程一条连接、关自动提交——建私有 schema → 用 schema_pg.sql 建库 → 造行 → 硬删 → 断言 → ROLLBACK。
 * CREATE SCHEMA 在 PostgreSQL 里是事务性的，断言失败也不留任何对象；库里已有的其他 schema 的表数据
 * 不被读、不被改、不被锁：
 * <ul>
 *   <li>建库阶段 search_path = 私有 schema 在前 + pgvector 扩展所在 schema（vector 类型与 vector_cosine_ops
 *       算符类只有扩展 schema 在路径上才解析得到）；建表语句一律落在排最前的私有 schema</li>
 *   <li>数据阶段（造行 / 硬删 / 断言）search_path 只含私有 schema：级联引用到私有 schema 之外的表名直接报错，
 *       不会落到库里已有的同名表上</li>
 * </ul>
 * 回滚用例的私有 schema 在建库后提交，事务形态与收尾见
 * {@link #purgeFailureInALaterStepRollsBackTheShareSnapshotDeletion()}；它的数据阶段同样只在私有 schema 上。
 *
 * <p>断言口径：
 * <ul>
 *   <li>硬删前：向目录（information_schema.columns，限定私有 schema）要出列名恰为 user_id 的全部表，
 *       逐表断言测试用户恰有一行——造行清单落后于 schema 时在这里红，不让零行断言空转</li>
 *   <li>硬删后：同一份目录表清单，逐表断言该用户零行</li>
 *   <li>邮箱墓碑表 t_user_email_tombstone 带 user_id 列，但不在零行之列：墓碑行按 180 天保留期留存、
 *       由保留期任务按 expire_time 清理，硬删不删墓碑——零行断言对它显式豁免，另行断言墓碑行存在</li>
 *   <li>反馈行匿名化而非删除：行还在，user_id 置哨兵 '0'（零行断言对反馈表因此成立）</li>
 *   <li>分享快照以 owner_user_id 关联用户，列名不是 user_id，不在目录表清单里，单独造行、单独断言：
 *       名下行数按情形恰为 0 行或 5 行（答案分享与会话分享两种粒度；未到期的各一条，另加已到期未清、不过期、
 *       置了删除标志的各一条；有效、已撤销、各一条三种情形），硬删前逐行核对 id、状态、到期与否与删除标志，
 *       硬删后零行、原有 token 查不到行；另一用户名下的行（未过期的有效行、已撤销行、已过期行各一条）逐行不变</li>
 * </ul>
 */
class AccountDeletionCascadeIntegrationTest {

    /**
     * JDBC 连接串；未设置即跳过
     */
    private static final String JDBC_URL_PROPERTY = "ragent.account-purge.test.jdbc-url";

    private static final String USER_ID = "100";

    /**
     * 另一个用户：名下的分享快照行不在硬删范围内
     */
    private static final String OTHER_USER_ID = "200";

    private static final String EMAIL = "u2@example.com";

    /**
     * 邮箱墓碑表：带 user_id 列但按保留期留存，硬删不删
     */
    private static final String TOMBSTONE_TABLE = "t_user_email_tombstone";

    private static final String FEEDBACK_ROW_ID = "900";

    private static final String SHARE_SNAPSHOT_TABLE = "t_share_snapshot";

    private static final String STATUS_ACTIVE = "ACTIVE";

    private static final String STATUS_REVOKED = "REVOKED";

    /**
     * 回滚用例里拦下用户行删除的触发器与它的函数（同在私有 schema）
     */
    private static final String USER_ROW_DELETE_BLOCKER = "account_purge_it_block_user_delete";

    /**
     * 上述触发器抛出的异常信息：库里的失败是否落在删除用户行这一步，以它辨认
     */
    private static final String USER_ROW_DELETE_BLOCKED = "account-purge-it user row delete blocked";

    /**
     * 快照载荷：对话正文（问题与回答）。行留着，正文就还在
     */
    private static final String SNAPSHOT_PAYLOAD = "{\"question\":\"提问\",\"answerMd\":\"回答\"}";

    /**
     * 另一用户名下的行：未过期的有效行、已撤销行、已过期行各一条，到期行归保留任务清理
     */
    private static final List<SeededSnapshot> OTHER_USER_ROWS = List.of(
            new SeededSnapshot("o1", ShareKind.ANSWER, STATUS_ACTIVE, Expiry.VALID, 0),
            new SeededSnapshot("o2", ShareKind.CONVERSATION, STATUS_REVOKED, Expiry.VALID, 0),
            new SeededSnapshot("o3", ShareKind.ANSWER, STATUS_ACTIVE, Expiry.EXPIRED, 0));

    private MockedStatic<StpUtil> stpUtil;

    @BeforeEach
    void setUp() {
        stpUtil = mockStatic(StpUtil.class);
    }

    @AfterEach
    void tearDown() {
        stpUtil.close();
    }

    @ParameterizedTest(name = "emailPresent={0}")
    @ValueSource(booleans = {true, false})
    void purgeLeavesTheUserWithNoRowInAnyTableKeyedByUserId(boolean emailPresent) throws Exception {
        String url = System.getProperty(JDBC_URL_PROPERTY);
        assumeTrue(url != null && !url.isBlank(),
                "Set " + JDBC_URL_PROPERTY + " to run PostgreSQL integration test");
        String email = emailPresent ? EMAIL : null;
        inFreshDatabase(url, (jdbc, schema) -> {
            AccountDeletionCascade cascade = new AccountDeletionCascade(jdbc);

            seedOneRowPerTableKeyedByUserId(jdbc, email);
            List<String> userIdTables = tablesKeyedByUserId(jdbc, schema);
            assertRowCount(jdbc, userIdTables, 1, "硬删前");

            cascade.purge(USER_ID, email);

            assertRowCount(jdbc, userIdTables.stream().filter(table -> !TOMBSTONE_TABLE.equals(table)).toList(),
                    0, "硬删后");
            // 墓碑行存在：造行时是已过期的一行，带邮箱时被硬删刷新为未过期
            List<Timestamp> tombstoneExpiry = jdbc.queryForList(
                    "SELECT expire_time FROM " + TOMBSTONE_TABLE + " WHERE user_id = ?", Timestamp.class, USER_ID);
            assertThat(tombstoneExpiry).as("邮箱墓碑行应存在且唯一").hasSize(1);
            if (email != null) {
                assertThat(tombstoneExpiry.get(0).toInstant()).as("硬删写入的墓碑应是未过期的").isAfter(Instant.now());
            }
            // 反馈行匿名化保留：行还在，user_id 已置哨兵
            assertThat(jdbc.queryForList("SELECT user_id FROM t_message_feedback WHERE id = ?", String.class,
                    FEEDBACK_ROW_ID))
                    .as("反馈行应匿名化保留而非删除")
                    .containsExactly("0");
            // 用户行物理删除
            assertThat(jdbc.queryForList("SELECT id FROM t_user WHERE id = ?", String.class, USER_ID))
                    .as("用户行应被物理删除")
                    .isEmpty();
            // 手动运行时的留痕：本次实际核对的表范围
            System.out.println("[account-purge-it] emailPresent=" + emailPresent + "，硬删前 " + userIdTables.size()
                    + " 张带 user_id 列的表各 1 行；硬删后除墓碑表外全部零行（墓碑行保留、反馈行匿名化、"
                    + "用户行已删）：" + userIdTables);
        });
    }

    /**
     * 名下分享快照按情形各跑一遍：有效、已撤销、有效与已撤销各一条、没有快照。名下有行的情形各 5 行——
     * 未到期的两条，已到期未清、不过期、置了删除标志的各一条，硬删对它们一视同仁
     */
    @ParameterizedTest(name = "emailPresent={0} snapshots={1}")
    @MethodSource("ownedSnapshots")
    void purgeLeavesTheUserWithNoShareSnapshotRow(boolean emailPresent, OwnedSnapshots owned) throws Exception {
        String url = System.getProperty(JDBC_URL_PROPERTY);
        assumeTrue(url != null && !url.isBlank(),
                "Set " + JDBC_URL_PROPERTY + " to run PostgreSQL integration test");
        String email = emailPresent ? EMAIL : null;
        inFreshDatabase(url, (jdbc, schema) -> {
            insertUser(jdbc, USER_ID, "purge-it", email == null ? "guest" : "user", email);
            insertUser(jdbc, OTHER_USER_ID, "purge-other", "user", "other@example.com");
            List<SeededSnapshot> ownedRows = owned.rows();
            insertSnapshots(jdbc, USER_ID, ownedRows);
            insertSnapshots(jdbc, OTHER_USER_ID, OTHER_USER_ROWS);

            // 硬删前：名下恰为造的那几行（含已撤销、已到期、不过期、置了删除标志的），不让零行断言空转
            assertOwnedSnapshots(jdbc, USER_ID, ownedRows, "硬删前");
            List<Map<String, Object>> otherRowsBefore = snapshotRows(jdbc, OTHER_USER_ID);
            assertThat(otherRowsBefore).as("硬删前：另一用户名下的分享快照行").hasSize(OTHER_USER_ROWS.size());

            AccountDeletionCascade cascade = new AccountDeletionCascade(jdbc);
            cascade.purge(USER_ID, email);

            // 硬删后：名下零行（有效的、已撤销的、已到期的、不过期的、置了删除标志的都不剩），原有 token 查不到行——
            // 公开读按 token 取数，查不到行与已撤销同一表现（见 ShareSnapshotServiceImplTest）
            assertThat(snapshotRows(jdbc, USER_ID)).as("硬删后：名下分享快照行应为零").isEmpty();
            List<String> ownedTokens = ownedRows.stream().map(row -> tokenOf(row.id())).toList();
            if (!ownedTokens.isEmpty()) {
                assertThat(jdbc.queryForList("SELECT token FROM " + SHARE_SNAPSHOT_TABLE + " WHERE token IN ("
                                + String.join(",", Collections.nCopies(ownedTokens.size(), "?")) + ")",
                                String.class, ownedTokens.toArray()))
                        .as("硬删后：名下原有的分享 token 不应再查到行")
                        .isEmpty();
            }
            // 另一用户名下的行逐行不变：未过期的、已撤销的、已过期的都还在，到期行仍归保留任务清理
            assertThat(snapshotRows(jdbc, OTHER_USER_ID))
                    .as("硬删后：另一用户名下的分享快照行应逐行不变")
                    .isEqualTo(otherRowsBefore);
            System.out.println("[account-purge-it] emailPresent=" + emailPresent + " snapshots=" + owned + "，硬删前名下 "
                    + ownedRows.size() + " 行" + labelsOf(ownedRows) + "；硬删后名下 0 行"
                    + (ownedTokens.isEmpty() ? "（名下本无快照，无 token 可查）" : "、原有 token 查不到行")
                    + "；另一用户 " + otherRowsBefore.size() + " 行逐行不变");
        });
    }

    /**
     * 后续步骤失败即回滚。级联经事务代理执行：事务边界与回滚规则取自 purge 自己的注解；拦截器、注解解析与
     * 事务管理器用的是 Spring 事务基础设施的同一套类，只是手工装配、不经容器；事务管理器与级联的 JdbcTemplate
     * 绑同一个数据源。删除用户行是级联发出的最后一条 SQL，私有 schema 里 t_user 上的触发器把它拦下，此前
     * 已执行的语句——含名下分享快照删除——随事务回滚；解除拦截后同一份数据经同一事务代理硬删成功并提交。
     *
     * <p>这一条的私有 schema 在建库后提交：回滚要抹掉的东西必须先提交过才观察得到。收尾时 DROP SCHEMA … CASCADE
     * 整体删除；数据阶段的 search_path 仍只含私有 schema，进数据阶段前先核对。
     */
    @Test
    void purgeFailureInALaterStepRollsBackTheShareSnapshotDeletion() throws Exception {
        String url = System.getProperty(JDBC_URL_PROPERTY);
        assumeTrue(url != null && !url.isBlank(),
                "Set " + JDBC_URL_PROPERTY + " to run PostgreSQL integration test");
        inCommittedDatabase(url, (jdbc, schema) -> {
            List<SeededSnapshot> ownedRows = OwnedSnapshots.MIXED.rows();
            insertUser(jdbc, USER_ID, "purge-it", "user", EMAIL);
            insertUser(jdbc, OTHER_USER_ID, "purge-other", "user", "other@example.com");
            jdbc.update("INSERT INTO t_conversation (id, conversation_id, user_id, title) VALUES (?, ?, ?, ?)",
                    "c1", "c1", USER_ID, "会话");
            insertSnapshots(jdbc, USER_ID, ownedRows);
            insertSnapshots(jdbc, OTHER_USER_ID, OTHER_USER_ROWS);
            List<Map<String, Object>> otherRowsBefore = snapshotRows(jdbc, OTHER_USER_ID);
            blockUserRowDeletion(jdbc);
            AccountDeletionCascade cascade = transactionalProxyOver(jdbc);

            // 失败落在预期的那一步：异常信息带拦截标记，删除用户行之前的级联语句都已执行过
            assertThatThrownBy(() -> cascade.purge(USER_ID, EMAIL))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining(USER_ROW_DELETE_BLOCKED);
            // 回滚后的库内状态：名下分享快照行（含已撤销、已到期、不过期、置了删除标志的）与先于它删除的
            // 对话行逐行保留
            assertOwnedSnapshots(jdbc, USER_ID, ownedRows, "后续步骤失败后");
            assertThat(jdbc.queryForList("SELECT id FROM t_conversation WHERE user_id = ?", String.class, USER_ID))
                    .as("后续步骤失败后：对话行应随事务回滚保留")
                    .containsExactly("c1");

            jdbc.execute("DROP TRIGGER " + USER_ROW_DELETE_BLOCKER + " ON t_user");
            cascade.purge(USER_ID, EMAIL);

            assertThat(snapshotRows(jdbc, USER_ID)).as("解除拦截后：名下分享快照行应为零").isEmpty();
            assertThat(snapshotRows(jdbc, OTHER_USER_ID))
                    .as("解除拦截后：另一用户名下的分享快照行应逐行不变")
                    .isEqualTo(otherRowsBefore);
            System.out.println("[account-purge-it] 回滚：删除用户行被拦下时名下 " + ownedRows.size() + " 行"
                    + labelsOf(ownedRows) + "与对话行逐行保留；解除拦截后名下 0 行、另一用户 "
                    + otherRowsBefore.size() + " 行逐行不变");
        });
    }

    /**
     * 两种入参形态 × 名下分享快照的全部情形
     */
    static Stream<Arguments> ownedSnapshots() {
        return Stream.of(OwnedSnapshots.values())
                .flatMap(owned -> Stream.of(true, false).map(emailPresent -> Arguments.of(emailPresent, owned)));
    }

    /**
     * 在私有 schema 上执行一段库内断言：建库在先、ROLLBACK 收尾，断言失败也不留任何对象
     */
    @FunctionalInterface
    private interface DatabaseBody {
        void run(JdbcTemplate jdbc, String schema) throws Exception;
    }

    private static void inFreshDatabase(String url, DatabaseBody body) throws Exception {
        String schema = "account_purge_it_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(url)) {
            connection.setAutoCommit(false);
            try {
                JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                buildDatabaseFromSchemaFile(jdbc, connection, schema);
                body.run(jdbc, schema);
            } finally {
                connection.rollback();
            }
        }
    }

    /**
     * 在已提交的私有 schema 上执行一段库内断言。建库沿用 {@link #inFreshDatabase} 的做法（一条连接、关自动提交、
     * SET LOCAL search_path），建完提交——purge 要在自己的事务里跑并真实回滚，回滚要抹掉的东西必须先提交过
     * 才观察得到。SET LOCAL 的 search_path 随建库事务结束失效，数据阶段重新收窄到私有 schema，进数据阶段前
     * 核对一次：级联的 SQL 都不带 schema 名，路径上多出别的 schema 就会落到库里已有的同名表上。
     * 收尾时回到自动提交（半途的未提交事务随之结束），再 DROP SCHEMA … CASCADE 整体删除——造的行、触发器、
     * purge 提交的结果都在其中
     */
    private static void inCommittedDatabase(String url, DatabaseBody body) throws Exception {
        String schema = "account_purge_it_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(url)) {
            connection.setAutoCommit(false);
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            try {
                buildDatabaseFromSchemaFile(jdbc, connection, schema);
                connection.setAutoCommit(true);
                jdbc.execute("SET search_path TO " + schema);
                assertThat(jdbc.queryForObject("SELECT current_schemas(false)::text", String.class))
                        .as("数据阶段的 search_path 应只含私有 schema")
                        .isEqualTo("{" + schema + "}");
                body.run(jdbc, schema);
            } finally {
                try {
                    connection.setAutoCommit(true);
                } finally {
                    jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                }
            }
        }
    }

    /**
     * 建私有 schema 并用现行 schema 文件建库；建库后收窄 search_path，数据阶段只认私有 schema
     */
    private static void buildDatabaseFromSchemaFile(JdbcTemplate jdbc, Connection connection, String schema)
            throws Exception {
        Path schemaFile = PgSchemaFile.locate();
        jdbc.execute("CREATE SCHEMA " + schema);
        jdbc.execute("SET LOCAL search_path TO " + schema + extensionSchema(jdbc));
        ScriptUtils.executeSqlScript(connection,
                new EncodedResource(new FileSystemResource(schemaFile), StandardCharsets.UTF_8));
        // 建库自检：私有 schema 里的表恰为 schema 文件声明的表，建库不完整不得进入数据阶段
        List<String> createdTables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ? ORDER BY table_name",
                String.class, schema);
        assertThat(createdTables)
                .as("私有 schema 应恰好建成 schema_pg.sql 声明的全部表")
                .containsExactlyInAnyOrderElementsOf(PgSchemaFile.parseTables(schemaFile).keySet());
        jdbc.execute("SET LOCAL search_path TO " + schema);
    }

    /**
     * pgvector 扩展所在 schema（带前导逗号）；扩展尚未安装时为空串——届时 schema 文件自己的
     * CREATE EXTENSION 会把它装进 search_path 排最前的私有 schema
     */
    private static String extensionSchema(JdbcTemplate jdbc) {
        List<String> schemas = jdbc.queryForList(
                "SELECT n.nspname FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace "
                        + "WHERE e.extname = 'vector'",
                String.class);
        return schemas.isEmpty() ? "" : ", \"" + schemas.get(0) + "\"";
    }

    /**
     * 造行清单：为测试用户在每张带 user_id 列的表里各造一行。清单手写，落后于 schema 时由硬删前的行数断言拦下
     */
    private static void seedOneRowPerTableKeyedByUserId(JdbcTemplate jdbc, String email) throws Exception {
        insertUser(jdbc, USER_ID, "purge-it", email == null ? "guest" : "user", email);
        // 墓碑行造成已过期：带邮箱时硬删须把它刷新成未过期。游客无邮箱、级联不写墓碑，仍造一行——
        // 硬删前的行数断言对所有表无条件成立，同时证明硬删不删墓碑行
        String tombstoneEmail = email == null ? "guest-no-email" : email;
        jdbc.update("INSERT INTO t_user_email_tombstone (id, email_hash, user_id, expire_time) VALUES (?, ?, ?, ?)",
                "tb1", sha256(tombstoneEmail), USER_ID, Timestamp.from(Instant.EPOCH));
        jdbc.update("INSERT INTO t_conversation (id, conversation_id, user_id, title) VALUES (?, ?, ?, ?)",
                "c1", "c1", USER_ID, "会话");
        jdbc.update("INSERT INTO t_conversation_summary (id, conversation_id, user_id, last_message_id, content) "
                + "VALUES (?, ?, ?, ?, ?)", "s1", "c1", USER_ID, "m1", "摘要");
        jdbc.update("INSERT INTO t_message (id, conversation_id, user_id, role, content) VALUES (?, ?, ?, ?, ?)",
                "m1", "c1", USER_ID, "user", "提问");
        jdbc.update("INSERT INTO t_message_feedback (id, message_id, conversation_id, user_id, vote, create_time, "
                        + "update_time) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                FEEDBACK_ROW_ID, "m1", "c1", USER_ID, 1);
        jdbc.update("INSERT INTO t_rag_trace_run (id, trace_id, user_id) VALUES (?, ?, ?)", "tr1", "tr1", USER_ID);
        jdbc.update("INSERT INTO t_agent_conversation (id, conversation_id, user_id, title) VALUES (?, ?, ?, ?)",
                "ac1", "ac1", USER_ID, "Agent 会话");
        jdbc.update("INSERT INTO t_agent_message (id, conversation_id, user_id, role) VALUES (?, ?, ?, ?)",
                "am1", "ac1", USER_ID, "user");
        jdbc.update("INSERT INTO t_agent_tool_call (id, conversation_id, user_id, message_id, block_index, "
                        + "tool_name, decision, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                "tc1", "ac1", USER_ID, "am1", 0, "search_knowledge", "ALLOW", "done");
        jdbc.update("INSERT INTO t_agent_state (user_id, session_id, state_key) VALUES (?, ?, ?)",
                USER_ID, "s1", "k1");
        jdbc.update("INSERT INTO t_agent_context_compaction (id, user_id, conversation_id, generation, "
                        + "material_msg_count, material_chars, summary_chars, context_chars_before, context_chars_after) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", "cc1", USER_ID, "ac1", 1, 1, 1, 1, 1, 1);
        jdbc.update("INSERT INTO t_agent_memory (id, user_id, content, source_type) VALUES (?, ?, ?, ?)",
                "mem1", USER_ID, "记忆", "user");
        jdbc.update("INSERT INTO t_agent_memory_cursor (user_id) VALUES (?)", USER_ID);
    }

    private static void insertUser(JdbcTemplate jdbc, String id, String username, String role, String email) {
        jdbc.update("INSERT INTO t_user (id, username, password, role, email) VALUES (?, ?, ?, ?, ?)",
                id, username, "x", role, email);
    }

    private static void insertSnapshots(JdbcTemplate jdbc, String ownerId, List<SeededSnapshot> rows) {
        for (SeededSnapshot row : rows) {
            jdbc.update("INSERT INTO " + SHARE_SNAPSHOT_TABLE + " (id, token, owner_user_id, kind, conversation_id, "
                            + "lang, status, expire_time, revoked_time, deleted, payload) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                    row.id(), tokenOf(row.id()), ownerId, row.kind().getCode(), "c-" + row.id(), "zh", row.status(),
                    expireTimeOf(row.expiry()), STATUS_REVOKED.equals(row.status()) ? new Date(0) : null, row.deleted(),
                    SNAPSHOT_PAYLOAD);
        }
    }

    /**
     * 各到期形态对应的 expire_time：未到期的在 90 天后，已到期的在 1 天前，不过期的为 NULL
     */
    private static Date expireTimeOf(Expiry expiry) {
        return switch (expiry) {
            case VALID -> daysFromNow(90);
            case EXPIRED -> daysFromNow(-1);
            case NEVER -> null;
        };
    }

    /**
     * 库里这一行的到期形态：expire_time 为 NULL 即不过期，否则按它与当前时刻的先后分未到期与已到期
     */
    private static Expiry expiryOf(Map<String, Object> row) {
        Timestamp expireTime = (Timestamp) row.get("expire_time");
        if (expireTime == null) {
            return Expiry.NEVER;
        }
        return expireTime.toInstant().isBefore(Instant.now()) ? Expiry.EXPIRED : Expiry.VALID;
    }

    /**
     * 断言某用户名下的分享快照行恰为给定的那几行（按 id 升序）：id、状态、到期与否、删除标志逐行一致
     */
    private static void assertOwnedSnapshots(JdbcTemplate jdbc, String ownerId, List<SeededSnapshot> expected,
            String stage) {
        assertThat(snapshotRows(jdbc, ownerId))
                .extracting(row -> row.get("id"), row -> row.get("status"), row -> expiryOf(row),
                        row -> row.get("deleted"))
                .as("%s：名下分享快照行（id, status, 到期与否, 删除标志）", stage)
                .containsExactlyElementsOf(expected.stream()
                        .map(row -> tuple(row.id(), row.status(), row.expiry(), row.deleted()))
                        .toList());
    }

    /**
     * 该用户名下的分享快照行，整行取回（载荷转文本），供硬删前后比对
     */
    private static List<Map<String, Object>> snapshotRows(JdbcTemplate jdbc, String ownerId) {
        return jdbc.queryForList("SELECT id, token, owner_user_id, kind, conversation_id, status, expire_time, "
                + "revoked_time, deleted, payload::text AS payload FROM " + SHARE_SNAPSHOT_TABLE
                + " WHERE owner_user_id = ? ORDER BY id", ownerId);
    }

    /**
     * 目录里当前 schema 内列名恰为 user_id 的表
     */
    private static List<String> tablesKeyedByUserId(JdbcTemplate jdbc, String schema) {
        return jdbc.queryForList(
                "SELECT table_name FROM information_schema.columns "
                        + "WHERE table_schema = ? AND column_name = 'user_id' ORDER BY table_name",
                String.class, schema);
    }

    /**
     * 逐表断言该用户的行数；不符的表一次收齐再断言，失败时看全
     */
    private static void assertRowCount(JdbcTemplate jdbc, List<String> tables, long expected, String stage) {
        Map<String, Long> mismatches = new LinkedHashMap<>();
        for (String table : tables) {
            long rows = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Long.class, USER_ID);
            if (rows != expected) {
                mismatches.put(table, rows);
            }
        }
        assertThat(mismatches)
                .as("%s：带 user_id 列的各表里用户 %s 的行数应恰为 %d；清单=%s，不符的表=行数：", stage, USER_ID, expected, tables)
                .isEmpty();
    }

    private static String tokenOf(String id) {
        return "token-" + id;
    }

    /**
     * 各行的简记 id:status:到期与否:删除标志，供手动运行时留痕
     */
    private static List<String> labelsOf(List<SeededSnapshot> rows) {
        return rows.stream().map(row -> row.id() + ":" + row.status() + ":" + row.expiry() + ":" + row.deleted()).toList();
    }

    /**
     * 给级联套上事务代理。事务拦截器从 purge 的注解读取事务属性，事务管理器与级联使用的 JdbcTemplate
     * 绑同一个数据源
     */
    private static AccountDeletionCascade transactionalProxyOver(JdbcTemplate jdbc) {
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()));
        ProxyFactory factory = new ProxyFactory(new AccountDeletionCascade(jdbc));
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (AccountDeletionCascade) factory.getProxy();
    }

    /**
     * 在私有 schema 里拦下用户行的删除：t_user 上的 BEFORE DELETE 触发器一律抛错，级联删除用户行这一步因此失败
     */
    private static void blockUserRowDeletion(JdbcTemplate jdbc) {
        jdbc.execute("CREATE FUNCTION " + USER_ROW_DELETE_BLOCKER + "() RETURNS trigger LANGUAGE plpgsql AS $body$"
                + " BEGIN RAISE EXCEPTION '" + USER_ROW_DELETE_BLOCKED + "'; END $body$");
        jdbc.execute("CREATE TRIGGER " + USER_ROW_DELETE_BLOCKER + " BEFORE DELETE ON t_user FOR EACH ROW"
                + " EXECUTE FUNCTION " + USER_ROW_DELETE_BLOCKER + "()");
    }

    private static Date daysFromNow(int days) {
        return Date.from(Instant.now().plusSeconds(days * 86_400L));
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 一条分享快照行
     *
     * @param id      主键；token 由它派生
     * @param kind    粒度
     * @param status  ACTIVE / REVOKED
     * @param expiry  到期与否；已到期的行在保留任务清走之前留在表里
     * @param deleted 删除标志
     */
    private record SeededSnapshot(String id, ShareKind kind, String status, Expiry expiry, int deleted) {
    }

    /**
     * 快照行的到期形态。expire_time 为 NULL 的行不过期；保留任务只清已到期的行
     */
    private enum Expiry {
        VALID,
        EXPIRED,
        NEVER
    }

    /**
     * 测试用户名下的分享快照情形：答案分享与会话分享两种粒度各一条未到期的，已到期、不过期、置了删除标志的
     * 各一条。后三条的状态随所属粒度在该情形下的状态
     */
    private enum OwnedSnapshots {

        /** 名下没有分享快照 */
        NONE,

        /** 两条都有效 */
        ACTIVE,

        /** 两条都已撤销 */
        REVOKED,

        /** 答案分享有效、会话分享已撤销 */
        MIXED;

        List<SeededSnapshot> rows() {
            return switch (this) {
                case NONE -> List.of();
                case ACTIVE -> ownedRows(STATUS_ACTIVE, STATUS_ACTIVE);
                case REVOKED -> ownedRows(STATUS_REVOKED, STATUS_REVOKED);
                case MIXED -> ownedRows(STATUS_ACTIVE, STATUS_REVOKED);
            };
        }

        private static List<SeededSnapshot> ownedRows(String answerStatus, String conversationStatus) {
            return List.of(
                    new SeededSnapshot("s1", ShareKind.ANSWER, answerStatus, Expiry.VALID, 0),
                    new SeededSnapshot("s2", ShareKind.CONVERSATION, conversationStatus, Expiry.VALID, 0),
                    new SeededSnapshot("s3", ShareKind.ANSWER, answerStatus, Expiry.EXPIRED, 0),
                    new SeededSnapshot("s4", ShareKind.CONVERSATION, conversationStatus, Expiry.NEVER, 0),
                    new SeededSnapshot("s5", ShareKind.ANSWER, answerStatus, Expiry.VALID, 1));
        }
    }
}

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

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
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * 账号硬删级联可选真库集成测试（#545）：在按现行 schema 文件建出的库上执行 {@link AccountDeletionCascade#purge}，
 * 证明硬删完成后该用户在所有带 user_id 列的表里不留行，名下的分享快照行（含已撤销的）一并清空。设置了系统属性
 * {@value #JDBC_URL_PROPERTY}（JDBC 连接串）才运行，缺省跳过、不进 CI；CI 上的覆盖面由
 * {@link AccountDeletionCascadeTest} 的 schema 覆盖断言守住。
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
 *       名下行数按情形恰为 0 行或 2 行（答案分享与会话分享两种粒度各一条；有效、已撤销、各一条三种情形），
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
     * 快照载荷：对话正文（问题与回答）。行留着，正文就还在
     */
    private static final String SNAPSHOT_PAYLOAD = "{\"question\":\"提问\",\"answerMd\":\"回答\"}";

    /**
     * 另一用户名下的行：未过期的有效行、已撤销行、已过期行各一条，到期行归保留任务清理
     */
    private static final List<SeededSnapshot> OTHER_USER_ROWS = List.of(
            new SeededSnapshot("o1", ShareKind.ANSWER, STATUS_ACTIVE, daysFromNow(90)),
            new SeededSnapshot("o2", ShareKind.CONVERSATION, STATUS_REVOKED, daysFromNow(90)),
            new SeededSnapshot("o3", ShareKind.ANSWER, STATUS_ACTIVE, daysFromNow(-1)));

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
     * 名下分享快照按情形各跑一遍：有效、已撤销、有效与已撤销各一条、没有快照
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

            // 硬删前：名下恰为造的那几行（含已撤销的），不让零行断言空转
            assertThat(snapshotRows(jdbc, USER_ID))
                    .extracting(row -> row.get("id"), row -> row.get("status"))
                    .as("硬删前：名下分享快照行（id, status）")
                    .containsExactlyElementsOf(ownedRows.stream()
                            .map(row -> tuple(row.id(), row.status())).toList());
            List<Map<String, Object>> otherRowsBefore = snapshotRows(jdbc, OTHER_USER_ID);
            assertThat(otherRowsBefore).as("硬删前：另一用户名下的分享快照行").hasSize(OTHER_USER_ROWS.size());

            AccountDeletionCascade cascade = new AccountDeletionCascade(jdbc);
            cascade.purge(USER_ID, email);

            // 硬删后：名下零行（有效的与已撤销的都不剩），原有 token 查不到行——公开读按 token 取数，
            // 查不到行与已撤销同一表现（见 ShareSnapshotServiceImplTest）
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
                    + ownedRows.size() + " 行" + idsWithStatus(ownedRows) + "；硬删后名下 0 行"
                    + (ownedTokens.isEmpty() ? "（名下本无快照，无 token 可查）" : "、原有 token 查不到行")
                    + "；另一用户 " + otherRowsBefore.size() + " 行逐行不变");
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
                            + "lang, status, expire_time, revoked_time, payload) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                    row.id(), tokenOf(row.id()), ownerId, row.kind().getCode(), "c-" + row.id(), "zh", row.status(),
                    row.expireTime(), STATUS_REVOKED.equals(row.status()) ? new Date(0) : null, SNAPSHOT_PAYLOAD);
        }
    }

    /**
     * 该用户名下的分享快照行，整行取回（载荷转文本），供硬删前后比对
     */
    private static List<Map<String, Object>> snapshotRows(JdbcTemplate jdbc, String ownerId) {
        return jdbc.queryForList("SELECT id, token, owner_user_id, kind, conversation_id, status, expire_time, "
                + "revoked_time, payload::text AS payload FROM " + SHARE_SNAPSHOT_TABLE
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

    private static List<String> idsWithStatus(List<SeededSnapshot> rows) {
        return rows.stream().map(row -> row.id() + ":" + row.status()).toList();
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
     * @param id         主键；token 由它派生
     * @param kind       粒度
     * @param status     ACTIVE / REVOKED
     * @param expireTime 过期时刻；已过期的行归保留任务清理
     */
    private record SeededSnapshot(String id, ShareKind kind, String status, Date expireTime) {
    }

    /**
     * 测试用户名下的分享快照情形：答案分享与会话分享两种粒度各一条
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
                case ACTIVE -> List.of(answer(STATUS_ACTIVE), conversation(STATUS_ACTIVE));
                case REVOKED -> List.of(answer(STATUS_REVOKED), conversation(STATUS_REVOKED));
                case MIXED -> List.of(answer(STATUS_ACTIVE), conversation(STATUS_REVOKED));
            };
        }

        private static SeededSnapshot answer(String status) {
            return new SeededSnapshot("s1", ShareKind.ANSWER, status, daysFromNow(90));
        }

        private static SeededSnapshot conversation(String status) {
            return new SeededSnapshot("s2", ShareKind.CONVERSATION, status, daysFromNow(90));
        }
    }
}

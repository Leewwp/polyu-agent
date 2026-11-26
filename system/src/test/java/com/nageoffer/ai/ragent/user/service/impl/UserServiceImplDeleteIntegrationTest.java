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
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.share.ShareKind;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import com.nageoffer.ai.ragent.user.mail.MailSender;
import com.nageoffer.ai.ragent.user.mail.MailVerificationService;
import com.nageoffer.ai.ragent.user.schema.PgSchemaFile;
import com.nageoffer.ai.ragent.user.security.PasswordCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mockito.MockedStatic;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;

/**
 * 管理端删号入口的可选真库集成测试（#600）：在按现行 schema 文件建出的库上，经 Spring 事务代理调用
 * {@link UserServiceImpl#delete(String)}——生产链路 DELETE /api/ragent/users/{id} → UserController →
 * UserServiceImpl.delete → AccountDeletionCascade.purge 里带事务的那一段。证明删号完成后该用户在所有带
 * user_id 列的表里不留行、名下的分享快照行（含已撤销的）一并清空、用户行物理删除；级联之后的一步失败时
 * 整体回滚。设置了系统属性 {@value #JDBC_URL_PROPERTY}（JDBC 连接串）才运行，缺省跳过、不进 CI——
 * 与 {@link AccountDeletionCascadeIntegrationTest} 同一个开关；那边直调 purge，不经 UserServiceImpl.delete。
 *
 * <p>装配：一个只开了事务管理（{@code @EnableTransactionManagement}，按目标类代理）的 Spring 容器。
 * UserServiceImpl 与 AccountDeletionCascade 按类注册、由容器构造注入，被测对象取自容器，是带事务拦截的
 * 代理而不是裸实例，注入它的级联同样是容器给出的事务代理；事务属性取自各自方法上的注解。UserMapper 经
 * SqlSessionTemplate 接到数据源：Spring 管理的事务工厂与事务管理器、级联的 JdbcTemplate 绑同一个数据源，
 * 删号前的取行与级联发出的 SQL 落在同一个事务、同一条连接上。BizChangeLogContext 与 PasswordCodec 是
 * 真实实例（前者为 spy，回滚用例靠它制造失败）；MailVerificationService、MailSender 在删号路径上用不到，
 * 是 mock。
 *
 * <p>断言口径沿用 {@link AccountDeletionCascadeIntegrationTest}：
 * <ul>
 *   <li>硬删前：向目录（information_schema.columns，限定私有 schema）要出列名恰为 user_id 的全部表，
 *       逐表断言测试用户恰有一行——造行清单落后于 schema 时在这里红，不让零行断言空转</li>
 *   <li>硬删后：同一份目录表清单，逐表断言该用户零行；邮箱墓碑表 t_user_email_tombstone 显式豁免
 *       （按 180 天保留期留存），另行断言墓碑行存在、带邮箱时未过期；反馈行匿名化保留（user_id 置哨兵 '0'）</li>
 *   <li>分享快照以 owner_user_id 关联用户，不在目录表清单里，单独造行、单独断言：名下 5 行——答案分享
 *       未到期的有效行，会话分享已撤销的行，另加已到期未清的已撤销行、不过期的行、置了删除标志的行各一条；
 *       硬删前逐行核对，硬删后零行、原有 token 查不到行；另一用户名下的行逐行不变</li>
 * </ul>
 *
 * <p>隔离：每个用例一个私有 schema，用 schema_pg.sql 建库后提交——delete 要在事务代理里真实提交、真实回滚，
 * 回滚要抹掉的东西必须先提交过才观察得到。数据阶段的 search_path 只含私有 schema，进数据阶段前核对一次；
 * 收尾 DROP SCHEMA … CASCADE 整体删除。全程一条连接。
 *
 * <p>不覆盖：
 * <ul>
 *   <li>控制器层（StpUtil.checkRole("admin")）与 @LogRecord 切面不在路径上——容器里只有事务切面</li>
 *   <li>Sa-Token 会话下线：StpUtil 是静态 mock</li>
 *   <li>应用自身的容器装配（Boot 自动配置、连接池）与生产使用的 Jackson 配置</li>
 * </ul>
 */
class UserServiceImplDeleteIntegrationTest {

    /**
     * JDBC 连接串；未设置即跳过。与 {@link AccountDeletionCascadeIntegrationTest} 同一个开关
     */
    private static final String JDBC_URL_PROPERTY = "ragent.account-purge.test.jdbc-url";

    private static final String USER_ID = "100";

    /**
     * 另一个用户：名下的分享快照行不在删号范围内
     */
    private static final String OTHER_USER_ID = "200";

    private static final String EMAIL = "u2@example.com";

    /**
     * 邮箱墓碑表：带 user_id 列但按保留期留存，删号不删
     */
    private static final String TOMBSTONE_TABLE = "t_user_email_tombstone";

    private static final String FEEDBACK_ROW_ID = "900";

    private static final String SHARE_SNAPSHOT_TABLE = "t_share_snapshot";

    private static final String STATUS_ACTIVE = "ACTIVE";

    private static final String STATUS_REVOKED = "REVOKED";

    /**
     * 回滚用例里，审计快照写入这一步抛出的异常信息；调用是否停在预期的那一步，以它辨认
     */
    private static final String AUDIT_SNAPSHOT_FAILURE = "admin-delete-it audit snapshot failure";

    /**
     * 快照载荷：对话正文（问题与回答）。行留着，正文就还在
     */
    private static final String SNAPSHOT_PAYLOAD = "{\"question\":\"提问\",\"answerMd\":\"回答\"}";

    /**
     * 测试用户名下的分享快照：未到期的答案分享、已撤销的会话分享各一条，另加已到期未清的已撤销行、
     * 不过期的行、置了删除标志的行各一条
     */
    private static final List<SeededSnapshot> OWNED_ROWS = List.of(
            new SeededSnapshot("s1", ShareKind.ANSWER, STATUS_ACTIVE, Expiry.VALID, 0),
            new SeededSnapshot("s2", ShareKind.CONVERSATION, STATUS_REVOKED, Expiry.VALID, 0),
            new SeededSnapshot("s3", ShareKind.ANSWER, STATUS_REVOKED, Expiry.EXPIRED, 0),
            new SeededSnapshot("s4", ShareKind.CONVERSATION, STATUS_ACTIVE, Expiry.NEVER, 0),
            new SeededSnapshot("s5", ShareKind.ANSWER, STATUS_ACTIVE, Expiry.VALID, 1));

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

    /**
     * 被测对象是容器给出的代理而不是裸实例，delete 在事务拦截器的覆盖之内：事务属性取自 delete 自己的注解
     */
    @Test
    void deleteIsCalledThroughTheSpringTransactionProxy() throws Exception {
        inCommittedDatabase(jdbcUrl(), (jdbc, userService, auditContext, schema) -> {
            TransactionAttribute attribute = transactionAttributeOfDelete(userService);
            assertThat(attribute)
                    .as("被测对象应是带事务拦截的 Spring 代理，delete 应在事务拦截器的覆盖之内")
                    .isNotNull();
            System.out.println("[admin-delete-it] 代理：" + userService.getClass().getName()
                    + "，delete 的事务属性=" + attribute);
        });
    }

    /**
     * 删号完成后该用户在所有带 user_id 列的表里零行（墓碑表除外：行保留，带邮箱时被刷新为未过期），
     * 名下的分享快照行（含已撤销的）零行、原有 token 查不到行，用户行物理删除；另一用户名下的行逐行不变。
     * 带邮箱与不带邮箱各跑一遍：邮箱取自用户行，管理员建号的用户可以没有邮箱
     */
    @ParameterizedTest(name = "emailPresent={0}")
    @ValueSource(booleans = {true, false})
    void deleteLeavesTheUserWithNoRowInAnyTableAndNoShareSnapshot(boolean emailPresent) throws Exception {
        String email = emailPresent ? EMAIL : null;
        inCommittedDatabase(jdbcUrl(), (jdbc, userService, auditContext, schema) -> {
            seedOneRowPerTableKeyedByUserId(jdbc, email);
            insertUser(jdbc, OTHER_USER_ID, "delete-other", "user", "other@example.com");
            insertSnapshots(jdbc, USER_ID, OWNED_ROWS);
            insertSnapshots(jdbc, OTHER_USER_ID, OTHER_USER_ROWS);
            List<String> userIdTables = tablesKeyedByUserId(jdbc, schema);

            // 删号前：各表恰为一行、名下恰为造的那 5 行（含已撤销的），不让零行断言空转
            assertRowCount(jdbc, userIdTables, 1, "删号前");
            assertOwnedSnapshots(jdbc, USER_ID, OWNED_ROWS, "删号前");
            List<Map<String, Object>> otherRowsBefore = snapshotRows(jdbc, OTHER_USER_ID);

            userService.delete(USER_ID);

            assertDeleted(jdbc, userIdTables, email, "删号后");
            assertThat(snapshotRows(jdbc, OTHER_USER_ID))
                    .as("删号后：另一用户名下的分享快照行应逐行不变")
                    .isEqualTo(otherRowsBefore);
            System.out.println("[admin-delete-it] emailPresent=" + emailPresent + "，删号前 " + userIdTables.size()
                    + " 张带 user_id 列的表各 1 行、名下分享快照 " + OWNED_ROWS.size() + " 行"
                    + labelsOf(OWNED_ROWS) + "；删号后除墓碑表外全部零行（墓碑行保留、反馈行匿名化、用户行已删）、"
                    + "名下 0 行、原有 token 查不到行；另一用户 " + otherRowsBefore.size() + " 行逐行不变："
                    + userIdTables);
        });
    }

    /**
     * 后续步骤失败即整体回滚。级联之后 delete 只剩一步：写审计快照（bizChangeLogContext.put，写线程上下文、
     * 不落库）。生产代码里没有别的缝能让这一步失败，所以把真实实例包成 spy，令 put 抛错。经事务代理调用
     * delete：异常带失败标记向上传播，此前级联已执行的全部删除——名下分享快照行（含已撤销的）与各张带
     * user_id 列的表里的行——随事务回滚保留，用户行还在，墓碑行与反馈行原样未动。恢复 put 的真实行为后，
     * 同一份数据经同一代理删号成功并提交。
     */
    @Test
    void deleteRollsBackTheWholeCascadeWhenALaterStepFails() throws Exception {
        inCommittedDatabase(jdbcUrl(), (jdbc, userService, auditContext, schema) -> {
            seedOneRowPerTableKeyedByUserId(jdbc, EMAIL);
            insertUser(jdbc, OTHER_USER_ID, "delete-other", "user", "other@example.com");
            insertSnapshots(jdbc, USER_ID, OWNED_ROWS);
            insertSnapshots(jdbc, OTHER_USER_ID, OTHER_USER_ROWS);
            List<String> userIdTables = tablesKeyedByUserId(jdbc, schema);
            assertRowCount(jdbc, userIdTables, 1, "删号前");
            assertOwnedSnapshots(jdbc, USER_ID, OWNED_ROWS, "删号前");
            List<Map<String, Object>> otherRowsBefore = snapshotRows(jdbc, OTHER_USER_ID);
            doThrow(new IllegalStateException(AUDIT_SNAPSHOT_FAILURE))
                    .when(auditContext).put(any(), any(), any());

            // 失败落在预期的那一步
            assertThatThrownBy(() -> userService.delete(USER_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(AUDIT_SNAPSHOT_FAILURE);
            // 回滚后的库内状态：删号前的一切原样保留
            assertThat(jdbc.queryForList("SELECT id FROM t_user WHERE id = ?", String.class, USER_ID))
                    .as("后续步骤失败后：用户行应随事务回滚保留")
                    .containsExactly(USER_ID);
            assertRowCount(jdbc, userIdTables, 1, "后续步骤失败后");
            assertThat(jdbc.queryForList("SELECT expire_time FROM " + TOMBSTONE_TABLE + " WHERE user_id = ?",
                    Timestamp.class, USER_ID))
                    .as("后续步骤失败后：邮箱墓碑行应回到造行时的已过期")
                    .containsExactly(Timestamp.from(Instant.EPOCH));
            assertThat(jdbc.queryForList("SELECT user_id FROM t_message_feedback WHERE id = ?", String.class,
                    FEEDBACK_ROW_ID))
                    .as("后续步骤失败后：反馈行应保持原样、未被匿名化")
                    .containsExactly(USER_ID);
            assertOwnedSnapshots(jdbc, USER_ID, OWNED_ROWS, "后续步骤失败后");
            assertThat(snapshotRows(jdbc, OTHER_USER_ID))
                    .as("后续步骤失败后：另一用户名下的分享快照行应逐行不变")
                    .isEqualTo(otherRowsBefore);

            doCallRealMethod().when(auditContext).put(any(), any(), any());
            userService.delete(USER_ID);

            assertDeleted(jdbc, userIdTables, EMAIL, "恢复后");
            assertThat(snapshotRows(jdbc, OTHER_USER_ID))
                    .as("恢复后：另一用户名下的分享快照行应逐行不变")
                    .isEqualTo(otherRowsBefore);
            System.out.println("[admin-delete-it] 回滚：审计快照写入失败时名下 " + OWNED_ROWS.size() + " 行"
                    + labelsOf(OWNED_ROWS) + "、" + userIdTables.size() + " 张带 user_id 列的表里的行、用户行、"
                    + "墓碑行与反馈行全部保留；恢复后名下 0 行、除墓碑表外全部零行、用户行已删，另一用户 "
                    + otherRowsBefore.size() + " 行逐行不变");
        });
    }

    /**
     * 在已提交的私有 schema 上，经容器装配出的事务代理执行一段库内断言。建库在一条连接上关自动提交完成，
     * 建完提交；SET LOCAL 的 search_path 随建库事务结束失效，数据阶段重新收窄到私有 schema，进数据阶段前
     * 核对一次。收尾时回到自动提交（半途的未提交事务随之结束），再 DROP SCHEMA … CASCADE 整体删除——
     * 造的行、delete 提交的结果都在其中
     */
    private static void inCommittedDatabase(String url, DatabaseBody body) throws Exception {
        String schema = "admin_delete_it_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(url)) {
            connection.setAutoCommit(false);
            SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            BizChangeLogContext auditContext = spy(new BizChangeLogContext(new ObjectMapper()));
            try {
                buildDatabaseFromSchemaFile(jdbc, connection, schema);
                connection.setAutoCommit(true);
                jdbc.execute("SET search_path TO " + schema);
                assertThat(jdbc.queryForObject("SELECT current_schemas(false)::text", String.class))
                        .as("数据阶段的 search_path 应只含私有 schema")
                        .isEqualTo("{" + schema + "}");
                try (AnnotationConfigApplicationContext context = contextOver(dataSource, auditContext)) {
                    body.run(jdbc, context.getBean(UserServiceImpl.class), auditContext, schema);
                }
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
     * 只开了事务管理的容器：数据源上的事务管理器与 JdbcTemplate、经 SqlSessionTemplate 的 UserMapper、
     * 以及 UserServiceImpl 与 AccountDeletionCascade。按类注册的两个由容器构造注入并套上事务代理
     */
    private static AnnotationConfigApplicationContext contextOver(DataSource dataSource,
                                                                  BizChangeLogContext auditContext) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfig globalConfig = GlobalConfigUtils.defaults();
        globalConfig.setBanner(false);
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
        configuration.addMapper(UserMapper.class);
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        factoryBean.setGlobalConfig(globalConfig);
        UserMapper userMapper = new SqlSessionTemplate(factoryBean.getObject()).getMapper(UserMapper.class);

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource));
        context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(UserMapper.class, () -> userMapper);
        context.registerBean(BizChangeLogContext.class, () -> auditContext);
        context.registerBean(PasswordCodec.class, PasswordCodec::new);
        context.registerBean(MailVerificationService.class, () -> mock(MailVerificationService.class));
        context.registerBean(MailSender.class, () -> mock(MailSender.class));
        context.register(Transactions.class);
        context.register(AccountDeletionCascade.class, UserServiceImpl.class);
        context.refresh();
        return context;
    }

    /**
     * 事务代理上 delete 的事务属性：从代理的事务拦截器读，读的是 delete 自己的注解；delete 不在事务拦截器的
     * 覆盖之内时为 null
     */
    private static TransactionAttribute transactionAttributeOfDelete(Object userService) throws Exception {
        Method delete = UserServiceImpl.class.getMethod("delete", String.class);
        assertThat(userService).as("被测对象应是 Spring 代理，不是裸实例").isInstanceOf(Advised.class);
        return Arrays.stream(((Advised) userService).getAdvisors())
                .map(Advisor::getAdvice)
                .filter(TransactionInterceptor.class::isInstance)
                .map(TransactionInterceptor.class::cast)
                .map(interceptor -> interceptor.getTransactionAttributeSource()
                        .getTransactionAttribute(delete, UserServiceImpl.class))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
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
     * 造行清单：为测试用户在每张带 user_id 列的表里各造一行。清单手写，落后于 schema 时由删号前的行数断言拦下
     */
    private static void seedOneRowPerTableKeyedByUserId(JdbcTemplate jdbc, String email) throws Exception {
        insertUser(jdbc, USER_ID, "delete-it", "user", email);
        // 墓碑行造成已过期：带邮箱时删号须把它刷新成未过期。不带邮箱时级联不写墓碑，仍造一行——
        // 删号前的行数断言对所有表无条件成立，同时证明删号不删墓碑行
        String tombstoneEmail = email == null ? "delete-it-no-email" : email;
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

    /**
     * 删号完成后的库内状态：带 user_id 列的各表零行（墓碑表除外，行保留且带邮箱时未过期）、反馈行匿名化保留、
     * 用户行物理删除、名下分享快照零行且原有 token 查不到行
     */
    private static void assertDeleted(JdbcTemplate jdbc, List<String> userIdTables, String email, String stage) {
        assertRowCount(jdbc, userIdTables.stream().filter(table -> !TOMBSTONE_TABLE.equals(table)).toList(), 0, stage);
        List<Timestamp> tombstoneExpiry = jdbc.queryForList(
                "SELECT expire_time FROM " + TOMBSTONE_TABLE + " WHERE user_id = ?", Timestamp.class, USER_ID);
        assertThat(tombstoneExpiry).as("%s：邮箱墓碑行应存在且唯一", stage).hasSize(1);
        if (email != null) {
            assertThat(tombstoneExpiry.get(0).toInstant()).as("%s：删号写入的墓碑应是未过期的", stage)
                    .isAfter(Instant.now());
        }
        assertThat(jdbc.queryForList("SELECT user_id FROM t_message_feedback WHERE id = ?", String.class,
                FEEDBACK_ROW_ID))
                .as("%s：反馈行应匿名化保留而非删除", stage)
                .containsExactly("0");
        assertThat(jdbc.queryForList("SELECT id FROM t_user WHERE id = ?", String.class, USER_ID))
                .as("%s：用户行应被物理删除", stage)
                .isEmpty();
        assertThat(snapshotRows(jdbc, USER_ID)).as("%s：名下分享快照行应为零", stage).isEmpty();
        List<String> ownedTokens = OWNED_ROWS.stream().map(row -> tokenOf(row.id())).toList();
        assertThat(jdbc.queryForList("SELECT token FROM " + SHARE_SNAPSHOT_TABLE + " WHERE token IN ("
                        + String.join(",", Collections.nCopies(ownedTokens.size(), "?")) + ")",
                        String.class, ownedTokens.toArray()))
                .as("%s：名下原有的分享 token 不应再查到行", stage)
                .isEmpty();
    }

    private static String jdbcUrl() {
        String url = System.getProperty(JDBC_URL_PROPERTY);
        assumeTrue(url != null && !url.isBlank(),
                "Set " + JDBC_URL_PROPERTY + " to run PostgreSQL integration test");
        return url;
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
     * 断言测试用户名下的分享快照行恰为给定的那几行（按 id 升序）：id、状态、到期与否、删除标志逐行一致
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
     * 该用户名下的分享快照行，整行取回（载荷转文本），供删号前后比对
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
     * 逐表断言测试用户的行数；不符的表一次收齐再断言，失败时看全
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
                .as("%s：带 user_id 列的各表里用户 %s 的行数应恰为 %d；清单=%s，不符的表=行数：",
                        stage, USER_ID, expected, tables)
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

    private static Date daysFromNow(int days) {
        return Date.from(Instant.now().plusSeconds(days * 86_400L));
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions {
    }

    @FunctionalInterface
    private interface DatabaseBody {

        void run(JdbcTemplate jdbc, UserServiceImpl userService, BizChangeLogContext auditContext, String schema)
                throws Exception;
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
}

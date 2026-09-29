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

package com.nageoffer.ai.ragent.news.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.news.dao.entity.NewsLlmReceiptDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsLlmReceiptMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * String statDate 与 PG DATE 绑定的非生产实证（#184 修正点6 / 验收反例 B07）
 *
 * <p><b>背景</b>：实体 statDate 原为 String，PgJDBC 的 setString 与 DATE 列双向
 * 不兼容（INSERT 报 "column is of type date but expression is of type character
 * varying"、等值比较报 "operator does not exist: date = character varying"，
 * 2026-09-29 本地 polyu-pg JDBC 探针实证；三套环境 URL 均无 stringtype=unspecified
 * 兜底）——mock 单测的绿不能替代该映射证明。修正=statDate 改 {@link LocalDate}
 * 走 mybatis LocalDateTypeHandler 原生 DATE 通道。
 *
 * <p><b>本 IT 在真实 PostgreSQL 上端到端证明</b>：insert / lambdaQuery eq(stat_date)
 * / lambdaUpdate set / QueryWrapper SUM 聚合（NewsLlmBudgetService 与 admin 的全部
 * 四种访问形状）经 LocalDate 绑定全链路可用。
 *
 * <p>门控：CI 无 PG 不跑（默认跳过）；本地执行=
 * {@code docker 侧 polyu-pg 起着后 ./mvnw -pl rag test
 * -Dtest=NewsLlmReceiptPgDateBindingIt -Dpolyu.pg.it=1}（-Dpolyu.pg.it.url/
 * user/pass 可覆盖默认连接）。行以 it-pgdate- 前缀隔离，收尾自清。
 */
@EnabledIfSystemProperty(named = "polyu.pg.it", matches = "1")
class NewsLlmReceiptPgDateBindingIt {

    private static final String FINGERPRINT_PREFIX = "it-pgdate-";

    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(System.getProperty("polyu.pg.it.url", "jdbc:postgresql://127.0.0.1:5434/ragent"));
        dataSource.setUser(System.getProperty("polyu.pg.it.user", "postgres"));
        dataSource.setPassword(System.getProperty("polyu.pg.it.pass", "postgres"));
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            // 与 260929_01_news_llm_budget_receipt.sql 同构的核心 DDL（幂等；本地库随迁移管道补齐后共存）
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS t_news_llm_receipt (
                      id                  BIGSERIAL PRIMARY KEY,
                      request_fingerprint VARCHAR(64)  NOT NULL,
                      stat_date           DATE         NOT NULL,
                      stat_month          VARCHAR(7)   NOT NULL,
                      model_id            VARCHAR(64),
                      served_model_id     VARCHAR(64),
                      attempts            INT          NOT NULL DEFAULT 0,
                      retries             INT          NOT NULL DEFAULT 0,
                      cost_estimate       NUMERIC(12,6) NOT NULL DEFAULT 0,
                      response_text       TEXT,
                      status              VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
                      error_brief         VARCHAR(512),
                      create_time         TIMESTAMP    NOT NULL DEFAULT now(),
                      update_time         TIMESTAMP    NOT NULL DEFAULT now(),
                      CONSTRAINT uq_news_llm_receipt UNIQUE (request_fingerprint, stat_date)
                    )""");
            // 自愈：早前半建表缺列时补齐（幂等）
            statement.execute("ALTER TABLE t_news_llm_receipt ADD COLUMN IF NOT EXISTS model_id VARCHAR(64)");
            statement.execute("ALTER TABLE t_news_llm_receipt ADD COLUMN IF NOT EXISTS served_model_id VARCHAR(64)");
        }
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("pg-date-it", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(NewsLlmReceiptMapper.class);
        sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @AfterAll
    static void cleanUpSeededRows() throws Exception {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            NewsLlmReceiptMapper mapper = session.getMapper(NewsLlmReceiptMapper.class);
            mapper.selectList(Wrappers.lambdaQuery(NewsLlmReceiptDO.class)
                            .likeRight(NewsLlmReceiptDO::getRequestFingerprint, FINGERPRINT_PREFIX))
                    .forEach(row -> mapper.deleteById(row.getId()));
        }
    }

    @Test
    void localDateBindsToPgDateAcrossAllLedgerAccessShapes() {
        LocalDate day = LocalDate.of(2026, 9, 29);
        String fingerprint = FINGERPRINT_PREFIX + "binding";

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            NewsLlmReceiptMapper mapper = session.getMapper(NewsLlmReceiptMapper.class);

            // 形状1：insert（周期行新建）——LocalDate 写入 DATE 列
            NewsLlmReceiptDO inserted = NewsLlmReceiptDO.builder()
                    .requestFingerprint(fingerprint)
                    .statDate(day)
                    .statMonth("2026-09")
                    .attempts(0).retries(0)
                    .costEstimate(BigDecimal.ZERO.setScale(6))
                    .status(NewsLlmBudgetService.STATUS_PENDING)
                    .build();
            assertEquals(1, mapper.insert(inserted));
            assertNotNull(inserted.getId(), "BIGSERIAL 主键回填");

            // 形状2：lambdaQuery eq(stat_date, LocalDate)——findOrCreatePeriodRow 的取行路径
            NewsLlmReceiptDO found = mapper.selectOne(Wrappers.lambdaQuery(NewsLlmReceiptDO.class)
                    .eq(NewsLlmReceiptDO::getRequestFingerprint, fingerprint)
                    .eq(NewsLlmReceiptDO::getStatDate, day)
                    .last("LIMIT 1"));
            assertNotNull(found, "LocalDate 等值比较命中 DATE 列（String 绑定在此报 operator does not exist）");
            assertEquals(day, found.getStatDate(), "DATE 读回 LocalDate 无损");
            assertEquals("2026-09", found.getStatMonth());

            // 形状3：lambdaUpdate set（write-ahead 记账路径）
            mapper.update(null, Wrappers.lambdaUpdate(NewsLlmReceiptDO.class)
                    .eq(NewsLlmReceiptDO::getId, found.getId())
                    .set(NewsLlmReceiptDO::getAttempts, 2)
                    .set(NewsLlmReceiptDO::getRetries, 1)
                    .set(NewsLlmReceiptDO::getCostEstimate, new BigDecimal("0.013696"))
                    .set(NewsLlmReceiptDO::getStatus, NewsLlmBudgetService.STATUS_FAILED));

            // 形状4：QueryWrapper SUM 聚合 + ne 排除指纹（sumCost 的日/月基数路径）
            List<Object> sum = mapper.selectObjs(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<NewsLlmReceiptDO>()
                    .select("COALESCE(SUM(cost_estimate), 0) AS total_cost")
                    .eq("stat_date", day)
                    .ne("request_fingerprint", "not-this-one"));
            assertNotNull(sum.get(0), "聚合含 DATE 等值过滤");
            assertEquals(0, new BigDecimal(String.valueOf(sum.get(0))).compareTo(new BigDecimal("0.013696")),
                    "SUM 聚合按 stat_date 过滤正确，实际=" + sum.get(0));

            // 周期行落定后跨日重取=新行（不命中旧行）
            NewsLlmReceiptDO nextDay = mapper.selectOne(Wrappers.lambdaQuery(NewsLlmReceiptDO.class)
                    .eq(NewsLlmReceiptDO::getRequestFingerprint, fingerprint)
                    .eq(NewsLlmReceiptDO::getStatDate, day.plusDays(1))
                    .last("LIMIT 1"));
            assertNull(nextDay, "跨日不命中旧行（周期行按日隔离）");

            NewsLlmReceiptDO after = mapper.selectById(found.getId());
            assertEquals(2, after.getAttempts());
            assertEquals(1, after.getRetries());
            assertEquals(NewsLlmBudgetService.STATUS_FAILED, after.getStatus());
        }
    }
}

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
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.nageoffer.ai.ragent.framework.database.MyMetaObjectHandler;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import com.nageoffer.ai.ragent.news.fetch.PublishTimePrecision;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 发布时间精度列与日报窗口边界的真库对版（#275）：一次性 PG 容器上验证
 * 初始化 DDL/幂等迁移后的真实列、date-only 归期代表值落窗（D+1 刊）、
 * 左闭右开边界样本（07:59:59.999/08:00:00/08:00:00.001）。
 *
 * <p>JVM 时区一致性口径：TIMESTAMP WITHOUT TIME ZONE 列以写入会话墙钟落库，
 * 生产 JVM 时区恒定（写/读/窗口推导同一 JVM）自洽——本测试在 UTC 与 HKT
 * 两种默认时区下各自完成「插入→窗口查询」闭环并断言归属相同；不跨时区
 * 混写混读（那不属于本系统的部署形态。#275 票面「JVM UTC/HKT 结果一致」
 * = 两种部署各自内部一致）。
 *
 * <p>门控：默认跳过。本地一次性容器（doc69 §6：独立端口 5435，不碰本地栈）：
 * {@code docker run -d --name daily271-pg -e POSTGRES_PASSWORD=postgres
 * -e POSTGRES_DB=ragent -p 5435:5432 pgvector/pgvector:pg16}（schema_pg.sql
 * + 261005_01 迁移先行应用）后
 * {@code ./mvnw -pl rag test -Dtest=PublishTimePrecisionPgIt -Dpolyu.pg.it=1
 * -Dpolyu.pg.it.url=jdbc:postgresql://127.0.0.1:5435/ragent}。
 */
@EnabledIfSystemProperty(named = "polyu.pg.it", matches = "1")
class PublishTimePrecisionPgIt {

    private static final String PREFIX = "it-precision-";
    private static org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory;
    private static TimeZone originalZone;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(System.getProperty("polyu.pg.it.url", "jdbc:postgresql://127.0.0.1:5435/ragent"));
        dataSource.setUser(System.getProperty("polyu.pg.it.user", "postgres"));
        dataSource.setPassword(System.getProperty("polyu.pg.it.pass", "postgres"));
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            // 幂等自愈（与迁移管道共存；独立容器上通常已由 261005_01 建好）
            statement.execute("ALTER TABLE t_news_item ADD COLUMN IF NOT EXISTS publish_time_precision VARCHAR(8) NOT NULL DEFAULT 'unknown'");
            statement.execute("INSERT INTO t_news_source (source_key, platform, official, display_name, display_name_en, fetch_endpoint, fetch_strategy, enabled) "
                    + "VALUES ('" + PREFIX + "src', 'official', TRUE, 'it', 'it', 'https://it-precision.example.com/', 'HTML_LIST', TRUE) "
                    + "ON CONFLICT (source_key) DO NOTHING");
        }
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("precision-it", new JdbcTransactionFactory(), dataSource));
        com.baomidou.mybatisplus.core.config.GlobalConfig globalConfig =
                com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(configuration);
        globalConfig.setMetaObjectHandler(new MyMetaObjectHandler());
        MybatisPlusInterceptor pagination = new MybatisPlusInterceptor();
        pagination.addInnerInterceptor(new PaginationInnerInterceptor());
        configuration.addInterceptor(pagination);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, NewsItemDO.class);
        configuration.addMapper(NewsItemMapper.class);
        sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        originalZone = TimeZone.getDefault();
    }

    @AfterAll
    static void cleanUp() {
        if (originalZone != null) {
            TimeZone.setDefault(originalZone);
        }
        if (sqlSessionFactory != null) {
            try (SqlSession session = sqlSessionFactory.openSession(true)) {
                session.getConnection().createStatement().execute(
                        "DELETE FROM t_news_item WHERE url LIKE 'https://it-precision.example.com/%'");
                session.getConnection().createStatement().execute(
                        "DELETE FROM t_news_source WHERE source_key = '" + PREFIX + "src'");
            } catch (Exception ignore) {
                // 容器一次性，清理失败不阻断
            }
        }
    }

    private static long sourceId(SqlSession session) {
        try (var rs = session.getConnection().createStatement()
                .executeQuery("SELECT id FROM t_news_source WHERE source_key = '" + PREFIX + "src'")) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** scope=独立数据集前缀（boundary/tz-utc/tz-hkt），隔离各用例/各时区的行 */
    private static void insertItem(SqlSession session, long sourceId, String scope, String slug,
                                   Date publishTime, String precision) {
        NewsItemDO item = NewsItemDO.builder()
                .sourceId(sourceId)
                .url("https://it-precision.example.com/" + scope + "/" + slug)
                .urlHash(scope + "-" + slug)
                .titleEn(slug)
                .langRaw("en")
                .publishTime(publishTime)
                .publishTimePrecision(precision)
                .status("published")
                .build();
        session.getMapper(NewsItemMapper.class).insert(item);
    }

    /** 窗口命中数：真实窗口谓词 publish_time >= start AND publish_time < end（selectWindowCandidates 同形） */
    private static long windowHits(SqlSession session, Date start, Date end, String scope) {
        try (var ps = session.getConnection().prepareStatement(
                "SELECT count(*) FROM t_news_item WHERE status = 'published' "
                        + "AND publish_time >= ? AND publish_time < ? "
                        + "AND url LIKE ?")) {
            ps.setTimestamp(1, new java.sql.Timestamp(start.getTime()));
            ps.setTimestamp(2, new java.sql.Timestamp(end.getTime()));
            ps.setString(3, "https://it-precision.example.com/" + scope + "/%");
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** D 刊窗口=[D-1 08:00, D 08:00) HKT；D+1 刊窗口=[D 08:00, D+1 08:00) */
    private static Date hktAt(LocalDate date, int hour) {
        return Date.from(date.atTime(hour, 0).atZone(ZoneId.of("Asia/Hong_Kong")).toInstant());
    }

    @Test
    void dateOnlyRepresentativeAndWindowBoundariesAlignOnRealDdl() {
        LocalDate d = LocalDate.of(2026, 10, 3);
        ZoneId hkt = ZoneId.of("Asia/Hong_Kong");
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            long sourceId = sourceId(session);
            // 边界样本（票面边界表，按「时间点所在 HKT 日期 D」表述）：
            // a=date-only 代表值 23:59:59；b=D 日 07:59:59.999；c=D 日 08:00:00；d=D 日 08:00:00.001
            insertItem(session, sourceId, "boundary", "a-representative",
                    PublishTimePrecision.dateOnlyRepresentative(d), PublishTimePrecision.DATE);
            insertItem(session, sourceId, "boundary", "b-075959999",
                    Date.from(d.atTime(7, 59, 59, 999_000_000).atZone(hkt).toInstant()), PublishTimePrecision.DATETIME);
            insertItem(session, sourceId, "boundary", "c-080000", hktAt(d, 8), PublishTimePrecision.DATETIME);
            insertItem(session, sourceId, "boundary", "d-080000001",
                    Date.from(d.atTime(8, 0, 0, 1_000_000).atZone(hkt).toInstant()), PublishTimePrecision.DATETIME);

            // D 刊窗口 [D-1 08:00, D 08:00)：只有 D 日 07:59:59.999
            assertEquals(1, windowHits(session, hktAt(d.minusDays(1), 8), hktAt(d, 8), "boundary"),
                    "D 07:59:59.999 属 D 刊；代表值与 08:00:00* 不属");
            // D+1 刊窗口 [D 08:00, D+1 08:00)：08:00:00、08:00:00.001、date-only 代表值 23:59:59
            assertEquals(3, windowHits(session, hktAt(d, 8), hktAt(d.plusDays(1), 8), "boundary"),
                    "D 08:00:00（含 .001）属 D+1 刊；date-only 代表值 23:59:59 亦归 D+1 刊");

            // 精度列往返：写入什么读回什么（真实 DDL 列）
            NewsItemDO read = session.getMapper(NewsItemMapper.class).selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<NewsItemDO>()
                            .eq(NewsItemDO::getUrlHash, "boundary-a-representative"));
            assertEquals(PublishTimePrecision.DATE, read.getPublishTimePrecision());
        }
    }

    @Test
    void windowMembershipIsJvmTimezoneInvariant() {
        LocalDate d = LocalDate.of(2026, 10, 3);
        for (String zoneId : new String[]{"UTC", "Asia/Hong_Kong"}) {
            TimeZone.setDefault(TimeZone.getTimeZone(zoneId));
            String scope = "tz-" + zoneId.replace('/', '-').toLowerCase();
            try (SqlSession session = sqlSessionFactory.openSession(true)) {
                long sourceId = sourceId(session);
                // 本时区闭环：date-only 代表值 + 08:00:00 边界两行
                insertItem(session, sourceId, scope, "representative",
                        PublishTimePrecision.dateOnlyRepresentative(d), PublishTimePrecision.DATE);
                insertItem(session, sourceId, scope, "edge", hktAt(d, 8), PublishTimePrecision.DATETIME);
                // 两行都落 D+1 刊窗口 [D 08:00, D+1 08:00)；不落 D 刊窗口
                assertEquals(2, windowHits(session, hktAt(d, 8), hktAt(d.plusDays(1), 8), scope),
                        "JVM=" + zoneId + " 下代表值/边界样本落 D+1 刊窗口（写入-查询闭环）");
                assertEquals(0, windowHits(session, hktAt(d.minusDays(1), 8), hktAt(d, 8), scope),
                        "JVM=" + zoneId + " 下样本不落 D 刊窗口");
            }
        }
        // UTC 与 HKT 两种 JVM 默认时区下，同一 Instant 集的窗口归属断言结果一致
        //（两个闭环的期望值同为 2/0——TIMESTAMP 列写读同会话自洽，生产 JVM 时区恒定）
    }
}

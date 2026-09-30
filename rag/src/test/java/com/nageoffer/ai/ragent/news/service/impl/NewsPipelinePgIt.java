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
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemStatus;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemTopicDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceHealthEventDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsTopicDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemTopicMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsSourceHealthEventMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsSourceMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsTopicMapper;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchProperties;
import com.nageoffer.ai.ragent.news.fetch.NewsUrlNormalizer;
import com.nageoffer.ai.ragent.news.fetch.RawNewsItem;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 资讯管线状态合同真库实证（#185）：mock 单测证明不了的部分——日准入计数聚合
 * SQL（fetch_time≥HKT 日切 AND status&lt;&gt;archived GROUP BY source_id）、
 * 旧文归档/准入入库、TTL 收尾 UPDATE、统一公开资格 SQL（wrapper + 主题面
 * 注解 SQL 三形状）、admin 六口径聚合（PG FILTER/DISTINCT 语法）——在本地
 * polyu-pg 端到端跑真 SQL。
 *
 * <p>门控：CI 无 PG 不跑（默认跳过）；本地执行=
 * {@code docker 侧 polyu-pg 起着后 ./mvnw -pl rag test
 * -Dtest=NewsPipelinePgIt -Dpolyu.pg.it=1}（-Dpolyu.pg.it.url/user/pass
 * 可覆盖默认连接）。行以 it-pipe- 前缀隔离，收尾自清。
 */
@EnabledIfSystemProperty(named = "polyu.pg.it", matches = "1")
class NewsPipelinePgIt {

    private static final String PREFIX = "it-pipe-";
    private static final long HOUR = 3600L * 1000;

    private static SqlSessionFactory sqlSessionFactory;
    private static final AtomicReference<Long> clockRef = new AtomicReference<>();

    @BeforeAll
    static void setUpDatabase() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(System.getProperty("polyu.pg.it.url", "jdbc:postgresql://127.0.0.1:5434/ragent"));
        dataSource.setUser(System.getProperty("polyu.pg.it.user", "postgres"));
        dataSource.setPassword(System.getProperty("polyu.pg.it.pass", "postgres"));
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            // 与 260929_02_news_item_pipeline_status.sql 同构（幂等自愈；本地库随迁移管道补齐后共存）
            statement.execute("ALTER TABLE t_news_item ADD COLUMN IF NOT EXISTS eligible_time TIMESTAMP");
            statement.execute("ALTER TABLE t_news_item ADD COLUMN IF NOT EXISTS summary_source VARCHAR(8)");
            statement.execute("ALTER TABLE t_news_item ADD COLUMN IF NOT EXISTS prompt_version VARCHAR(16)");
            statement.execute(
                    "CREATE INDEX IF NOT EXISTS idx_news_item_pending_ttl ON t_news_item(fetch_time) WHERE status = 'pending'");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_news_item_fetch_day ON t_news_item(fetch_time)");
            // 与 260930_news_source_governance.sql 同构（幂等自愈；#186 源治理列+事件表）
            statement.execute("ALTER TABLE t_news_source ADD COLUMN IF NOT EXISTS disabled_reason VARCHAR(16)");
            statement.execute("ALTER TABLE t_news_source ADD COLUMN IF NOT EXISTS isolated_time TIMESTAMP");
            statement.execute("ALTER TABLE t_news_source ADD COLUMN IF NOT EXISTS recovered_time TIMESTAMP");
            statement.execute("ALTER TABLE t_news_source ADD COLUMN IF NOT EXISTS probe_successes INT");
            statement.execute("ALTER TABLE t_news_source ADD COLUMN IF NOT EXISTS probe_time TIMESTAMP");
            statement.execute("ALTER TABLE t_news_source ADD COLUMN IF NOT EXISTS last_outcome VARCHAR(32)");
            statement.execute("ALTER TABLE t_news_source ADD COLUMN IF NOT EXISTS last_outcome_time TIMESTAMP");
            statement.execute("CREATE TABLE IF NOT EXISTS t_news_source_health_event ("
                    + "id BIGSERIAL PRIMARY KEY, source_id BIGINT NOT NULL REFERENCES t_news_source(id), "
                    + "event_type VARCHAR(32) NOT NULL, outcome VARCHAR(32), detail VARCHAR(512), "
                    + "event_time TIMESTAMP NOT NULL, create_time TIMESTAMP NOT NULL DEFAULT now())");
        }
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("pipeline-it", new JdbcTransactionFactory(), dataSource));
        // 主链 MyMetaObjectHandler 的等价物：裸配置下 fill=INSERT 字段（create_time 等）须填充
        com.baomidou.mybatisplus.core.config.GlobalConfig globalConfig =
                com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(configuration);
        globalConfig.setMetaObjectHandler(new com.nageoffer.ai.ragent.framework.database.MyMetaObjectHandler());
        MybatisPlusInterceptor pagination = new MybatisPlusInterceptor();
        pagination.addInnerInterceptor(new PaginationInnerInterceptor());
        configuration.addInterceptor(pagination);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, NewsItemDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsItemTopicDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsSourceHealthEventDO.class);
        configuration.addMapper(NewsItemMapper.class);
        configuration.addMapper(NewsItemTopicMapper.class);
        configuration.addMapper(com.nageoffer.ai.ragent.news.dao.mapper.NewsLlmReceiptMapper.class);
        configuration.addMapper(NewsSourceMapper.class);
        configuration.addMapper(NewsSourceHealthEventMapper.class);
        configuration.addMapper(NewsTopicMapper.class);
        sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @AfterAll
    static void cleanUpSeededRows() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            NewsItemMapper itemMapper = session.getMapper(NewsItemMapper.class);
            itemMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(NewsItemDO.class)
                            .likeRight(NewsItemDO::getUrl, "https://it-pipe.invalid/"))
                    .forEach(row -> itemMapper.deleteById(row.getId()));
            NewsSourceMapper sourceMapper = session.getMapper(NewsSourceMapper.class);
            sourceMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(NewsSourceDO.class)
                            .likeRight(NewsSourceDO::getSourceKey, PREFIX))
                    .forEach(row -> sourceMapper.deleteById(row.getId()));
        }
    }

    private static long[] insertProbeSourcePair(SqlSession session, String suffix) {
        NewsSourceMapper sourceMapper = session.getMapper(NewsSourceMapper.class);
        NewsSourceDO big = NewsSourceDO.builder()
                .sourceKey(PREFIX + "big-" + suffix).platform("official")
                .displayName("IT 大源").fetchEndpoint("https://it-pipe.invalid/big-" + suffix)
                .fetchStrategy("RSS").enabled(true).consecutiveFailures(0).build();
        NewsSourceDO campus = NewsSourceDO.builder()
                .sourceKey(PREFIX + "campus-" + suffix).platform("official")
                .displayName("IT 校园源").fetchEndpoint("https://it-pipe.invalid/campus-" + suffix)
                .fetchStrategy("SITEMAP").enabled(true).consecutiveFailures(0).build();
        sourceMapper.insert(big);
        sourceMapper.insert(campus);
        return new long[]{big.getId(), campus.getId()};
    }

    /**
     * 全链真库回放：公平准入（大源不饿死校园源+日计数 SQL）→ 归档 → 富化落资格 →
     * TTL 收尾 → 统一公开资格（列表/详情/主题三形状）→ admin 六口径聚合
     */
    @Test
    void pipelineContractHoldsOnRealPg() {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        long fixedNow = System.currentTimeMillis();
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            NewsItemMapper itemMapper = session.getMapper(NewsItemMapper.class);
            NewsSourceMapper sourceMapper = session.getMapper(NewsSourceMapper.class);
            long[] sourceIds = insertProbeSourcePair(session, runId);
            NewsSourceDO big = sourceMapper.selectById(sourceIds[0]);
            NewsSourceDO campus = sourceMapper.selectById(sourceIds[1]);

            NewsFetchProperties properties = new NewsFetchProperties();
            properties.setAdmissionDailySiteCap(12);
            properties.getAdmissionSourceDailyCaps().put(big.getSourceKey(), 20);
            NewsFetchService fetchService = new NewsFetchService(List.of(), sourceMapper, itemMapper,
                    properties, () -> new Date(clockRef.get()));

            // ── 准入：大源 30 新鲜候选 + 校园源 2 条 + 旧文 1 条，全站上限 12 ──
            clockRef.set(fixedNow);
            List<RawNewsItem> bigItems = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                bigItems.add(rawItem(big.getSourceKey(), runId + "-big-" + i, fixedNow - (i + 1) * HOUR));
            }
            List<RawNewsItem> campusItems = List.of(
                    rawItem(campus.getSourceKey(), runId + "-campus-0", fixedNow - HOUR),
                    rawItem(campus.getSourceKey(), runId + "-campus-1", fixedNow - 2 * HOUR));
            List<RawNewsItem> stale = List.of(
                    rawItem(campus.getSourceKey(), runId + "-stale", fixedNow - 3 * 24 * HOUR));
            // 共享本地库可能有其它当日数据：期望值按库内当日已准入现值推导（全站 12 是硬顶）
            java.time.ZonedDateTime dayStartHkt = java.time.ZonedDateTime.ofInstant(
                    new Date(fixedNow).toInstant(), java.time.ZoneId.of("Asia/Hong_Kong")).toLocalDate().atStartOfDay(java.time.ZoneId.of("Asia/Hong_Kong"));
            Long alreadyToday = itemMapper.selectCount(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(NewsItemDO.class)
                            .ge(NewsItemDO::getFetchTime, Date.from(dayStartHkt.toInstant()))
                            .ne(NewsItemDO::getStatus, NewsItemStatus.ARCHIVED));
            int expectedAdmit = (int) Math.max(0, 12 - (alreadyToday == null ? 0 : alreadyToday));
            NewsFetchService.AdmissionResult result = fetchService.admitAll(List.of(
                    new NewsFetchService.SourceCandidates(big, bigItems),
                    new NewsFetchService.SourceCandidates(campus, campusItems),
                    new NewsFetchService.SourceCandidates(campus, stale)));
            assertEquals(expectedAdmit, result.admitted(), "全站上限 12 生效（真库计数 SQL 含库内既有当日数据）");
            assertEquals(1, result.archivedStale(), "旧文 48h 归档（不计准入）");
            Long campusPending = countByStatus(itemMapper, sourceIds[1], NewsItemStatus.PENDING);
            Long bigPending = countByStatus(itemMapper, sourceIds[0], NewsItemStatus.PENDING);
            assertEquals(2L, campusPending, "校园源 2 条全部准入——大源不饿死校园源（真库）");
            assertEquals(10L, bigPending, "大源取剩余份额 10");

            // ── 重跑（重启语义）：计数从库现推 → 不重复不超额 ──
            NewsFetchService.AdmissionResult rerun = fetchService.admitAll(List.of(
                    new NewsFetchService.SourceCandidates(big, bigItems),
                    new NewsFetchService.SourceCandidates(campus, campusItems)));
            assertEquals(0, rerun.admitted(), "同日重跑零新增（真库 url_hash 幂等+日计数持久）");
            assertEquals((long) expectedAdmit, countAll(itemMapper, sourceIds, NewsItemStatus.PENDING), "总额不变");

            // ── 富化落资格（applyPayload 真库 UPDATE）+ TTL 收尾（真库 UPDATE）──
            NewsEnrichService enrichService = new NewsEnrichService(itemMapper, sourceMapper,
                    session.getMapper(NewsTopicMapper.class), session.getMapper(NewsItemTopicMapper.class),
                    mock(com.nageoffer.ai.ragent.news.fetch.NewsHttpFetchClient.class),
                    new com.nageoffer.ai.ragent.core.parser.HtmlDocumentParser(),
                    mock(NewsLlmBudgetService.class),
                    new com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader(
                            new org.springframework.core.io.DefaultResourceLoader()),
                    new com.fasterxml.jackson.databind.ObjectMapper(), properties, () -> new Date(clockRef.get()));
            List<NewsItemDO> pending = itemMapper.selectList(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(NewsItemDO.class)
                            .eq(NewsItemDO::getSourceId, sourceIds[1])
                            .eq(NewsItemDO::getStatus, NewsItemStatus.PENDING));
            assertEquals(2, pending.size(), "FIFO 选题 SQL（pending+缺摘要+TTL 内+id 升序）真库可用");
            Date eligibleAt = new Date(fixedNow);
            for (NewsItemDO item : pending) {
                enrichService.applyPayload(item, new NewsEnrichService.NewsSummaryPayload(
                        "标题", "Title", "摘要。\n\n段落。", "Summary.\n\nPara.", "campus", List.of()), null);
            }
            // 大源一条走零调用回退
            List<NewsItemDO> bigPendingRows = itemMapper.selectList(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(NewsItemDO.class)
                            .eq(NewsItemDO::getSourceId, sourceIds[0])
                            .eq(NewsItemDO::getStatus, NewsItemStatus.PENDING)
                            .last("LIMIT 1"));
            enrichService.applyFallback(bigPendingRows.get(0), "IT 回退注入");
            // 时钟前移 49h：剩余 pending 转 expired
            clockRef.set(fixedNow + 49 * HOUR);
            int expired = fetchService.expireOverduePending();
            assertTrue(expired >= expectedAdmit - 3,
                    "剩余待富化超 TTL 转 expired（真库 UPDATE），实际=" + expired);

            // ── 统一公开资格（真库 SQL）：详情/列表/主题三形状 ──
            NewsQueryServiceImpl queryService = queryService(itemMapper,
                    session.getMapper(NewsItemTopicMapper.class), session.getMapper(NewsTopicMapper.class));
            clockRef.set(fixedNow + 180_000L); // 恰过门：campus 两条（llm）+ big 一条（fallback）可见
            Long campusItemId = pending.get(0).getId();
            assertNotNull(queryService.getPublishedDetail(campusItemId), "过门 published 详情可见（真库 SQL）");
            Long fallbackId = bigPendingRows.get(0).getId();
            assertNotNull(queryService.getPublishedDetail(fallbackId), "零调用回退条目同样过门可见");
            clockRef.set(fixedNow + 60_000L); // 门内：同一行不可见（详情面下架/未过门/不存在同形 404）
            assertThrows(com.nageoffer.ai.ragent.framework.exception.ClientException.class,
                    () -> queryService.getPublishedDetail(campusItemId),
                    "资格就绪 60s＜180s：未过门不可见（真库 SQL）");
            // expired 行永不可见
            List<NewsItemDO> expiredRows = itemMapper.selectList(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(NewsItemDO.class)
                            .eq(NewsItemDO::getSourceId, sourceIds[0])
                            .eq(NewsItemDO::getStatus, NewsItemStatus.EXPIRED)
                            .last("LIMIT 1"));
            assertThrows(com.nageoffer.ai.ragent.framework.exception.ClientException.class,
                    () -> queryService.getPublishedDetail(expiredRows.get(0).getId()),
                    "expired 终态不可见（真库 SQL）");

            // ── admin 六口径聚合（PG FILTER/DISTINCT 语法真库实证）──
            NewsAdminServiceImpl admin = new NewsAdminServiceImpl(itemMapper,
                    session.getMapper(com.nageoffer.ai.ragent.news.dao.mapper.NewsLlmReceiptMapper.class),
                    sourceMapper,
                    session.getMapper(com.nageoffer.ai.ragent.news.dao.mapper.NewsSourceHealthEventMapper.class),
                    properties);
            com.nageoffer.ai.ragent.news.controller.vo.NewsPipelineStatusVO status =
                    admin.pipelineStatus(LocalDate.now());
            assertTrue(status.getDiscovered() >= 13, "六口径·发现（真库聚合，含归档行），实际=" + status.getDiscovered());
            assertTrue(status.getAdmitted() >= 12, "六口径·准入");
            assertTrue(status.getEnrichedLlm() >= 2, "六口径·富化成功（campus 2 条 llm），实际=" + status.getEnrichedLlm());
            assertTrue(status.getFallbackToday() >= 1, "六口径·回退单列，实际=" + status.getFallbackToday());
            assertEquals(0L, status.getEventCount(), "六口径·事件数=0 占位（#187）");
            assertTrue(status.getUniqueContent() >= 1, "唯一内容标题面代理 SQL（DISTINCT/COALESCE）可用");
        }
    }

    /**
     * 主题面统一公开资格（注解 SQL 三形状真库）：关联主题的过门条目计数/最近时刻/分页
     */
    @Test
    void topicSurfacesApplyGateOnRealPg() {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        long fixedNow = System.currentTimeMillis();
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            NewsItemMapper itemMapper = session.getMapper(NewsItemMapper.class);
            NewsItemTopicMapper itemTopicMapper = session.getMapper(NewsItemTopicMapper.class);
            NewsTopicMapper topicMapper = session.getMapper(NewsTopicMapper.class);
            long[] sourceIds = insertProbeSourcePair(session, runId);
            NewsSourceDO campus = session.getMapper(NewsSourceMapper.class).selectById(sourceIds[1]);

            NewsTopicDO topic = NewsTopicDO.builder().slug(PREFIX + "topic-" + runId)
                    .nameZh("IT 主题").topicGroup("RESEARCH").curated(true).status("active").build();
            topicMapper.insert(topic);
            clockRef.set(fixedNow);
            NewsItemDO gated = NewsItemDO.builder()
                    .sourceId(sourceIds[1]).url("https://it-pipe.invalid/" + runId + "-gated")
                    .urlHash(NewsUrlNormalizer.urlHash("https://it-pipe.invalid/" + runId + "-gated"))
                    .titleEn("gated").langRaw("en").category("other").status(NewsItemStatus.PUBLISHED)
                    .publishTime(new Date(fixedNow - HOUR))
                    .eligibleTime(new Date(fixedNow)).summarySource(NewsItemStatus.SUMMARY_SOURCE_LLM)
                    // fetch_time 放历史日：不污染另一用例的全站日准入计数（本用例只验可见性形状）
                    .fetchTime(new Date(fixedNow - 60L * 24 * HOUR)).heat(0).build();
            NewsItemDO legacyVisible = NewsItemDO.builder()
                    .sourceId(sourceIds[1]).url("https://it-pipe.invalid/" + runId + "-legacy")
                    .urlHash(NewsUrlNormalizer.urlHash("https://it-pipe.invalid/" + runId + "-legacy"))
                    .titleEn("legacy").langRaw("en").category("other").status(NewsItemStatus.PUBLISHED)
                    .publishTime(new Date(fixedNow - 2 * HOUR))
                    .fetchTime(new Date(fixedNow - 60L * 24 * HOUR)).heat(0).build();
            itemMapper.insert(gated);
            itemMapper.insert(legacyVisible);
            itemTopicMapper.insert(NewsItemTopicDO.builder().itemId(gated.getId()).topicId(topic.getId()).build());
            itemTopicMapper.insert(NewsItemTopicDO.builder().itemId(legacyVisible.getId()).topicId(topic.getId()).build());

            Date withinGate = new Date(fixedNow - 60_000L);
            Date pastGate = new Date(fixedNow + 60_000L);
            assertEquals(1L, countOf(itemTopicMapper.countVisibleByTopic(withinGate), topic.getId()),
                    "门内：仅历史行（eligible_time NULL）计入主题计数");
            assertEquals(2L, countOf(itemTopicMapper.countVisibleByTopic(pastGate), topic.getId()),
                    "过门：历史行+资格行都计入");
            assertNotNull(itemTopicMapper.selectLastVisiblePublishTime(topic.getId(), pastGate));
            // 历史行（eligible_time NULL）恒可见：即使门下界早于资格行就绪时刻，最近可见时刻仍非空
            assertNotNull(itemTopicMapper.selectLastVisiblePublishTime(topic.getId(), new Date(fixedNow - 10 * 60_000L)),
                    "历史行 NULL 资格视同早已过门（不重算）");
            com.baomidou.mybatisplus.core.metadata.IPage<NewsItemDO> page =
                    itemTopicMapper.selectVisiblePageByTopic(
                            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10),
                            topic.getId(), withinGate);
            assertEquals(1, page.getRecords().size(), "主题分页（门内）：仅历史行返回");
            assertEquals("legacy", page.getRecords().get(0).getTitleEn());
        }
    }

    private NewsQueryServiceImpl queryService(NewsItemMapper itemMapper,
                                              NewsItemTopicMapper itemTopicMapper,
                                              NewsTopicMapper topicMapper) {
        com.nageoffer.ai.ragent.news.heat.NewsStoryAssembler assembler =
                mock(com.nageoffer.ai.ragent.news.heat.NewsStoryAssembler.class);
        when(assembler.loadVisible(any(), any())).thenReturn(
                new com.nageoffer.ai.ragent.news.heat.NewsStoryAssembler.NewsStoryWindow(List.of(), Map.of()));
        return new NewsQueryServiceImpl(itemMapper, mock(NewsSourceMapper.class), topicMapper,
                itemTopicMapper, assembler, new com.nageoffer.ai.ragent.news.heat.NewsStoryClusterer(),
                new com.nageoffer.ai.ragent.news.heat.NewsHeatProperties(),
                new NewsFetchProperties(), () -> new Date(clockRef.get()));
    }

    private static RawNewsItem rawItem(String key, String slug, long publishTime) {
        String url = "https://it-pipe.invalid/" + slug;
        return new RawNewsItem(url, NewsUrlNormalizer.urlHash(url),
                "it " + slug, null, "en", new Date(publishTime), null, key);
    }

    private static Long countByStatus(NewsItemMapper mapper, long sourceId, String status) {
        return mapper.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(NewsItemDO.class)
                .eq(NewsItemDO::getSourceId, sourceId)
                .eq(NewsItemDO::getStatus, status));
    }

    private static Long countAll(NewsItemMapper mapper, long[] sourceIds, String status) {
        return mapper.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(NewsItemDO.class)
                .in(NewsItemDO::getSourceId, List.of(sourceIds[0], sourceIds[1]))
                .eq(NewsItemDO::getStatus, status));
    }

    private static long countOf(List<com.nageoffer.ai.ragent.news.dao.dto.TopicPublishedCountDTO> rows, Long topicId) {
        return rows.stream().filter(row -> topicId.equals(row.getTopicId()))
                .mapToLong(com.nageoffer.ai.ragent.news.dao.dto.TopicPublishedCountDTO::getCnt).sum();
    }
}

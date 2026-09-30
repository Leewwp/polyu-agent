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

package com.nageoffer.ai.ragent.news.heat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.news.dao.entity.NewsEventDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsEventItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsEventMigrationDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsEventSourceVoteDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemStatus;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemTopicDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsTopicDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsEventItemMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsEventMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsEventMigrationMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsEventSourceVoteMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemTopicMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsSourceMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsTopicMapper;
import com.nageoffer.ai.ragent.news.fetch.NewsUrlNormalizer;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件最小模型真库实证（#187）：真实样本词面（本地 33 行 fixture，同
 * {@link NewsClusterHardGateTests}）在本地 polyu-pg 上端到端跑事件重归组——
 * 聚类合并落持久身份、独立来源投票账（同机构多 feed 一票，UNIQUE 约束真库证明）、
 * 合并/分裂/下架迁移账、同库重跑幂等（同数据第二轮零迁移、ID 稳定）。
 *
 * <p>时间轴声明：fixture 真实发布跨度 4 个月，按发布日<b>保序压缩</b>进 4 日热度窗
 * （80h 起逐日 +1h）——聚类判定只依赖词面锚定日（E4 用文本日期，不用 publish_time），
 * 时间轴压缩不影响合并语义；真实跨度下的 48h 证据窗/半衰数值断言归
 * {@link NewsEventServiceTests} 单测。
 *
 * <p>门控：CI 无 PG 不跑（默认跳过）；本地执行=polyu-pg 起着后
 * {@code ./mvnw -pl rag test -Dtest=NewsEventIdentityPgIt -Dpolyu.pg.it=1}
 * （-Dpolyu.pg.it.url/user/pass 可覆盖默认连接）。@BeforeEach 全表清种（it-187-
 * 前缀）到已知态——治理 IT 判例：断言涉全表计数必须先清种，同库连跑两次绿才算过。
 */
@EnabledIfSystemProperty(named = "polyu.pg.it", matches = "1")
class NewsEventIdentityPgIt {

    private static final String PREFIX = "it-187-";
    private static final long HOUR = 3600L * 1000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static SqlSessionFactory sqlSessionFactory;
    private static JsonNode fixture;
    private static long fixedNow;

    private SqlSession session;
    private NewsItemMapper itemMapper;
    private NewsEventService eventService;
    private NewsEventMapper eventMapper;
    private NewsEventItemMapper eventItemMapper;
    private NewsEventSourceVoteMapper voteMapper;
    private NewsEventMigrationMapper migrationMapper;
    private final Map<Long, Long> insertedIdByFixtureId = new LinkedHashMap<>();
    private final Map<Long, String> originalSummaryByFixtureId = new HashMap<>();

    @BeforeAll
    static void setUpDatabase() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(System.getProperty("polyu.pg.it.url", "jdbc:postgresql://127.0.0.1:5434/ragent"));
        dataSource.setUser(System.getProperty("polyu.pg.it.user", "postgres"));
        dataSource.setPassword(System.getProperty("polyu.pg.it.pass", "postgres"));
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            // 与 upgrades/v2.0.0/260930_02_news_event_identity.sql 同构（幂等自愈；本地库随迁移管道补齐后共存）
            statement.execute("ALTER TABLE t_news_item ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_news_item_content_hash"
                    + " ON t_news_item(content_hash) WHERE content_hash IS NOT NULL");
            statement.execute("ALTER TABLE t_news_source ADD COLUMN IF NOT EXISTS independence_group VARCHAR(64)");
            statement.execute("CREATE TABLE IF NOT EXISTS t_news_event ("
                    + "id BIGSERIAL PRIMARY KEY, status VARCHAR(16) NOT NULL DEFAULT 'active', "
                    + "heat INT NOT NULL DEFAULT 0, first_report_time TIMESTAMP, last_activity_time TIMESTAMP, "
                    + "create_time TIMESTAMP NOT NULL DEFAULT now(), update_time TIMESTAMP NOT NULL DEFAULT now())");
            statement.execute("CREATE TABLE IF NOT EXISTS t_news_event_item ("
                    + "id BIGSERIAL PRIMARY KEY, event_id BIGINT NOT NULL REFERENCES t_news_event(id), "
                    + "item_id BIGINT NOT NULL REFERENCES t_news_item(id) ON DELETE CASCADE, source_id BIGINT, "
                    + "independence_group VARCHAR(64) NOT NULL, publish_time TIMESTAMP, "
                    + "joined_time TIMESTAMP NOT NULL DEFAULT now(), CONSTRAINT uq_news_event_item UNIQUE (item_id))");
            statement.execute("CREATE TABLE IF NOT EXISTS t_news_event_source_vote ("
                    + "id BIGSERIAL PRIMARY KEY, event_id BIGINT NOT NULL REFERENCES t_news_event(id), "
                    + "independence_group VARCHAR(64) NOT NULL, vote_count INT NOT NULL DEFAULT 0, "
                    + "first_vote_time TIMESTAMP, last_vote_time TIMESTAMP, "
                    + "update_time TIMESTAMP NOT NULL DEFAULT now(), CONSTRAINT uq_news_event_vote UNIQUE (event_id, independence_group))");
            statement.execute("CREATE TABLE IF NOT EXISTS t_news_event_migration ("
                    + "id BIGSERIAL PRIMARY KEY, old_event_id BIGINT NOT NULL, new_event_id BIGINT, "
                    + "kind VARCHAR(16) NOT NULL, item_id BIGINT, reason VARCHAR(512), "
                    + "create_time TIMESTAMP NOT NULL DEFAULT now())");
        }
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("event-it", new JdbcTransactionFactory(), dataSource));
        com.baomidou.mybatisplus.core.config.GlobalConfig globalConfig =
                com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(configuration);
        globalConfig.setMetaObjectHandler(new com.nageoffer.ai.ragent.framework.database.MyMetaObjectHandler());
        MybatisPlusInterceptor pagination = new MybatisPlusInterceptor();
        pagination.addInnerInterceptor(new PaginationInnerInterceptor());
        configuration.addInterceptor(pagination);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, NewsItemDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsItemTopicDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsTopicDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsSourceDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsEventDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsEventItemDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsEventSourceVoteDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsEventMigrationDO.class);
        configuration.addMapper(NewsItemMapper.class);
        configuration.addMapper(NewsItemTopicMapper.class);
        configuration.addMapper(NewsSourceMapper.class);
        configuration.addMapper(NewsTopicMapper.class);
        configuration.addMapper(NewsEventMapper.class);
        configuration.addMapper(NewsEventItemMapper.class);
        configuration.addMapper(NewsEventSourceVoteMapper.class);
        configuration.addMapper(NewsEventMigrationMapper.class);
        sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        try (InputStream in = NewsEventIdentityPgIt.class
                .getResourceAsStream("/fixtures/news/cluster-samples-187.json")) {
            fixture = MAPPER.readTree(in);
        }
        // 固定业务时钟（时间旅行先例）取 2027-01-01：本地库真实数据（2026 年内）全部早于
        // 热度窗下界（fixedNow-4d=2026-12-28），窗口内只有本测试 it-187- 种子——真实库残留
        // 不影响断言（治理 IT 判例：环境自净+与库内数据隔离）
        fixedNow = java.time.LocalDate.of(2027, 1, 1)
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    @AfterAll
    static void cleanUpSeededRows() {
        // 收尾自清（NewsPipelinePgIt 先例）：it-187- 种子出库，防 fetch_time=固定时钟
        // 的残留行污染其它 IT 的「当日准入计数」现推口径
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            NewsEventMigrationMapper migrationMapper = session.getMapper(NewsEventMigrationMapper.class);
            NewsEventSourceVoteMapper voteMapper = session.getMapper(NewsEventSourceVoteMapper.class);
            NewsEventItemMapper eventItemMapper = session.getMapper(NewsEventItemMapper.class);
            NewsEventMapper eventMapper = session.getMapper(NewsEventMapper.class);
            migrationMapper.delete(Wrappers.lambdaQuery(NewsEventMigrationDO.class));
            voteMapper.delete(Wrappers.lambdaQuery(NewsEventSourceVoteDO.class));
            eventItemMapper.delete(Wrappers.lambdaQuery(NewsEventItemDO.class));
            eventMapper.delete(Wrappers.lambdaQuery(NewsEventDO.class));
            NewsItemMapper itemMapper = session.getMapper(NewsItemMapper.class);
            itemMapper.selectList(Wrappers.lambdaQuery(NewsItemDO.class)
                            .likeRight(NewsItemDO::getUrl, "https://" + PREFIX + "invalid/"))
                    .forEach(row -> itemMapper.deleteById(row.getId()));
            NewsTopicMapper topicMapper = session.getMapper(NewsTopicMapper.class);
            topicMapper.selectList(Wrappers.lambdaQuery(NewsTopicDO.class)
                            .likeRight(NewsTopicDO::getSlug, PREFIX))
                    .forEach(row -> topicMapper.deleteById(row.getId()));
            NewsSourceMapper sourceMapper = session.getMapper(NewsSourceMapper.class);
            sourceMapper.selectList(Wrappers.lambdaQuery(NewsSourceDO.class)
                            .likeRight(NewsSourceDO::getSourceKey, PREFIX))
                    .forEach(row -> sourceMapper.deleteById(row.getId()));
        }
    }

    @BeforeEach
    void cleanSeedAndReplant() {
        session = sqlSessionFactory.openSession(true);
        itemMapper = session.getMapper(NewsItemMapper.class);
        eventMapper = session.getMapper(NewsEventMapper.class);
        eventItemMapper = session.getMapper(NewsEventItemMapper.class);
        voteMapper = session.getMapper(NewsEventSourceVoteMapper.class);
        migrationMapper = session.getMapper(NewsEventMigrationMapper.class);
        // 清种到已知态（判例：涉全表断言必须先清；it-187- 前缀只动本测试数据）
        migrationMapper.delete(Wrappers.lambdaQuery(NewsEventMigrationDO.class));
        voteMapper.delete(Wrappers.lambdaQuery(NewsEventSourceVoteDO.class));
        eventItemMapper.delete(Wrappers.lambdaQuery(NewsEventItemDO.class));
        eventMapper.delete(Wrappers.lambdaQuery(NewsEventDO.class));
        NewsItemTopicMapper itemTopicMapper = session.getMapper(NewsItemTopicMapper.class);
        NewsItemMapper items = itemMapper;
        items.selectList(Wrappers.lambdaQuery(NewsItemDO.class)
                        .likeRight(NewsItemDO::getUrl, "https://" + PREFIX + "invalid/"))
                .forEach(row -> items.deleteById(row.getId()));
        NewsTopicMapper topicMapper = session.getMapper(NewsTopicMapper.class);
        topicMapper.selectList(Wrappers.lambdaQuery(NewsTopicDO.class)
                        .likeRight(NewsTopicDO::getSlug, PREFIX))
                .forEach(row -> topicMapper.deleteById(row.getId()));
        NewsSourceMapper sourceMapper = session.getMapper(NewsSourceMapper.class);
        sourceMapper.selectList(Wrappers.lambdaQuery(NewsSourceDO.class)
                        .likeRight(NewsSourceDO::getSourceKey, PREFIX))
                .forEach(row -> sourceMapper.deleteById(row.getId()));

        // 重建种子：11 源两独立组（官方 10 源一组+PRN 一组）+主题+33 真实词面条目
        Map<String, Long> sourceIdByKey = new LinkedHashMap<>();
        Map<String, NewsSourceDO> distinctSources = new LinkedHashMap<>();
        for (JsonNode row : fixture.get("rows")) {
            String key = row.get("source_key").asText();
            if (!distinctSources.containsKey(key)) {
                String group = key.equals("prn") ? PREFIX + "prn-wire" : PREFIX + "polyu-official";
                NewsSourceDO source = NewsSourceDO.builder()
                        .sourceKey(PREFIX + key).platform(key.equals("prn") ? "prn" : "official")
                        .displayName("IT 源 " + key).displayNameEn(key)
                        .homeUrl("https://" + PREFIX + "invalid/" + key)
                        .fetchEndpoint("https://" + PREFIX + "invalid/" + key)
                        .fetchStrategy("RSS").official(!key.equals("prn"))
                        .enabled(true).consecutiveFailures(0)
                        .independenceGroup(group).build();
                sourceMapper.insert(source);
                distinctSources.put(key, source);
            }
            sourceIdByKey.put(key, distinctSources.get(key).getId());
        }
        Map<String, Long> topicIdBySlug = new LinkedHashMap<>();
        // 发布日保序压缩进 4 日窗：最早发布日=NOW-80h，其后每个不同日 +1h
        List<LocalDate> orderedDates = new ArrayList<>();
        for (JsonNode row : fixture.get("rows")) {
            LocalDate date = LocalDate.parse(row.get("publish_date").asText());
            if (!orderedDates.contains(date)) {
                orderedDates.add(date);
            }
        }
        java.util.Collections.sort(orderedDates);
        Map<LocalDate, Date> publishByDate = new TreeMap<>();
        for (int i = 0; i < orderedDates.size(); i++) {
            publishByDate.put(orderedDates.get(i), new Date(fixedNow - (80 - i) * HOUR));
        }
        for (JsonNode row : fixture.get("rows")) {
            long fixtureId = row.get("db_id").asLong();
            String url = "https://" + PREFIX + "invalid/item/" + fixtureId;
            NewsItemDO item = NewsItemDO.builder()
                    .sourceId(sourceIdByKey.get(row.get("source_key").asText()))
                    .url(url).urlHash(NewsUrlNormalizer.urlHash(url))
                    .titleZh(row.get("title_zh").asText())
                    .titleEn(row.hasNonNull("title_en") ? row.get("title_en").asText() : null)
                    .summaryZh(row.hasNonNull("summary_zh") ? row.get("summary_zh").asText() : null)
                    .category(row.get("category").asText())
                    .langRaw("zh-Hans").publishTime(publishByDate.get(LocalDate.parse(row.get("publish_date").asText())))
                    .fetchTime(new Date(fixedNow)).status(NewsItemStatus.PUBLISHED).heat(0)
                    .build();
            itemMapper.insert(item);
            insertedIdByFixtureId.put(fixtureId, item.getId());
            originalSummaryByFixtureId.put(fixtureId, item.getSummaryZh());
            for (JsonNode slug : row.get("topics")) {
                Long topicId = topicIdBySlug.computeIfAbsent(slug.asText(), s -> {
                    NewsTopicDO topic = NewsTopicDO.builder()
                            .slug(PREFIX + s).nameZh("IT " + s).nameEn(s).topicGroup("FACULTY")
                            .curated(true).status("active").build();
                    session.getMapper(NewsTopicMapper.class).insert(topic);
                    return topic.getId();
                });
                itemTopicMapper.insert(NewsItemTopicDO.builder()
                        .itemId(item.getId()).topicId(topicId).build());
            }
        }
        NewsStoryAssembler assembler = new NewsStoryAssembler(itemMapper, itemTopicMapper,
                session.getMapper(NewsSourceMapper.class));
        NewsHeatProperties heatProperties = new NewsHeatProperties();
        heatProperties.getSourceWeights().put(PREFIX + "media-releases", 3);
        eventService = new NewsEventService(itemMapper, eventMapper, eventItemMapper,
                voteMapper, migrationMapper, assembler, new NewsStoryClusterer(),
                new NewsHeatService(heatProperties), heatProperties, (Supplier<Date>) () -> new Date(fixedNow));
    }

    private Long eventIdOfItem(long fixtureId) {
        Long itemId = insertedIdByFixtureId.get(fixtureId);
        NewsEventItemDO row = eventItemMapper.selectOne(Wrappers.lambdaQuery(NewsEventItemDO.class)
                .eq(NewsEventItemDO::getItemId, itemId).last("LIMIT 1"));
        assertNotNull(row, "条目 fixture-" + fixtureId + " 应有参与者证据行");
        return row.getEventId();
    }

    private List<NewsEventMigrationDO> migrationsOf(String kind) {
        return migrationMapper.selectList(Wrappers.lambdaQuery(NewsEventMigrationDO.class)
                .eq(NewsEventMigrationDO::getKind, kind));
    }

    @Test
    void realSampleIdentityContractHoldsOnRealPg() {
        // ── 第 1 轮：真实词面聚类落持久身份（33 条目→29 簇：4 组正例合并+25 单例） ──
        NewsEventService.RegroupResult first = eventService.regroupEvents();
        assertEquals(29, first.created(), "33 条目=29 事件（4 组类1 正例合并）");
        assertEquals(29L, eventMapper.selectCount(Wrappers.lambdaQuery(NewsEventDO.class)
                .eq(NewsEventDO::getStatus, NewsEventDO.STATUS_ACTIVE)));
        assertEquals(33, eventItemMapper.selectCount(Wrappers.lambdaQuery(NewsEventItemDO.class)));
        assertEquals(eventIdOfItem(119), eventIdOfItem(271), "CLU-001 跨源同事件合并（media-releases×sao-news）");
        assertEquals(eventIdOfItem(47), eventIdOfItem(270), "CLU-002 合并");
        assertEquals(eventIdOfItem(770), eventIdOfItem(776), "CLU-003 合并（15 天跨度日历×通稿，锚定日同）");
        assertEquals(eventIdOfItem(152), eventIdOfItem(153), "CLU-004 PRN 双语 wire 合并");
        assertNotEquals(eventIdOfItem(275), eventIdOfItem(276), "CLU-026 标题全同负例不合并（真库）");
        assertNotEquals(eventIdOfItem(119), eventIdOfItem(48), "CLU-046 链式终对不合并（真库）");
        assertTrue(migrationsOf(NewsEventMigrationDO.KIND_MERGE).isEmpty(), "首轮无合并迁移");

        // ── 独立来源投票账（真库 UNIQUE 约束）：同机构多 feed 一票 ──
        Long clu001Event = eventIdOfItem(119);
        List<NewsEventSourceVoteDO> clu001Votes = voteMapper.selectList(
                Wrappers.lambdaQuery(NewsEventSourceVoteDO.class)
                        .eq(NewsEventSourceVoteDO::getEventId, clu001Event));
        assertEquals(1, clu001Votes.size(), "CLU-001 两源同独立组（官方）=1 票");
        assertEquals(PREFIX + "polyu-official", clu001Votes.get(0).getIndependenceGroup());
        assertEquals(2, clu001Votes.get(0).getVoteCount(), "组内条目数=2（计数不放大票权）");
        Long clu004Event = eventIdOfItem(152);
        List<NewsEventSourceVoteDO> clu004Votes = voteMapper.selectList(
                Wrappers.lambdaQuery(NewsEventSourceVoteDO.class)
                        .eq(NewsEventSourceVoteDO::getEventId, clu004Event));
        assertEquals(1, clu004Votes.size(), "CLU-004 PRN 双 wire 同组=1 票");

        // ── 第 2 轮（合并）：条目 135 词面改为与 133 同构（同锚定日/分类/主题共享）→ 两事件合一 ──
        itemMapper.update(Wrappers.lambdaUpdate(NewsItemDO.class)
                .eq(NewsItemDO::getId, insertedIdByFixtureId.get(135L))
                .set(NewsItemDO::getSummaryZh, originalSummaryByFixtureId.get(133L)));
        NewsEventService.RegroupResult mergeRound = eventService.regroupEvents();
        assertEquals(1, mergeRound.merges(), "133/135 两事件并入一簇");
        assertEquals(eventIdOfItem(133), eventIdOfItem(135));
        List<NewsEventMigrationDO> mergeRows = migrationsOf(NewsEventMigrationDO.KIND_MERGE);
        assertEquals(1, mergeRows.size());
        assertEquals(eventIdOfItem(133), mergeRows.get(0).getNewEventId(), "迁移账记录旧→存续");
        assertEquals(28L, eventMapper.selectCount(Wrappers.lambdaQuery(NewsEventDO.class)
                .eq(NewsEventDO::getStatus, NewsEventDO.STATUS_ACTIVE)));

        // ── 第 3 轮（分裂）：条目 135 词面还原 → 原簇分裂，原 ID 留确定原组（含最早成员） ──
        Long mergedEvent = eventIdOfItem(135);
        itemMapper.update(Wrappers.lambdaUpdate(NewsItemDO.class)
                .eq(NewsItemDO::getId, insertedIdByFixtureId.get(135L))
                .set(NewsItemDO::getSummaryZh, originalSummaryByFixtureId.get(135L)));
        NewsEventService.RegroupResult splitRound = eventService.regroupEvents();
        assertEquals(1, splitRound.splits());
        assertNotEquals(mergedEvent, eventIdOfItem(133), "133 迁出到新事件");
        List<NewsEventMigrationDO> splitRows = migrationsOf(NewsEventMigrationDO.KIND_SPLIT);
        assertEquals(1, splitRows.size());
        assertEquals(mergedEvent, splitRows.get(0).getOldEventId());
        assertEquals(insertedIdByFixtureId.get(133L), splitRows.get(0).getItemId());
        assertEquals(mergedEvent, eventIdOfItem(135), "原 ID 留给含最早成员（135 首报更早）的确定原组");

        // ── 第 4 轮（下架）：条目 119 转 hidden → 参与者证据摘除+撤票+detach 迁移 ──
        itemMapper.update(Wrappers.lambdaUpdate(NewsItemDO.class)
                .eq(NewsItemDO::getId, insertedIdByFixtureId.get(119L))
                .set(NewsItemDO::getStatus, NewsItemStatus.HIDDEN));
        NewsEventService.RegroupResult detachRound = eventService.regroupEvents();
        assertEquals(1, detachRound.detached());
        List<NewsEventMigrationDO> detachRows = migrationsOf(NewsEventMigrationDO.KIND_DETACH);
        assertEquals(1, detachRows.size());
        assertEquals(insertedIdByFixtureId.get(119L), detachRows.get(0).getItemId());
        Long flagEvent = eventIdOfItem(271);
        List<NewsEventSourceVoteDO> flagVotes = voteMapper.selectList(
                Wrappers.lambdaQuery(NewsEventSourceVoteDO.class)
                        .eq(NewsEventSourceVoteDO::getEventId, flagEvent));
        assertEquals(1, flagVotes.size(), "升旗礼簇剩 sao-news 一源一票");
        assertEquals(1, flagVotes.get(0).getVoteCount());

        // ── 第 5 轮（同库重跑幂等）：数据不变 → 零新建/零迁移、ID 稳定 ──
        Map<Long, Long> eventIdBefore = new HashMap<>();
        insertedIdByFixtureId.keySet().forEach(fid -> {
            Long itemId = insertedIdByFixtureId.get(fid);
            NewsEventItemDO row = eventItemMapper.selectOne(Wrappers.lambdaQuery(NewsEventItemDO.class)
                    .eq(NewsEventItemDO::getItemId, itemId).last("LIMIT 1"));
            if (row != null) {
                eventIdBefore.put(fid, row.getEventId());
            }
        });
        Long migrationsBefore = migrationMapper.selectCount(Wrappers.lambdaQuery(NewsEventMigrationDO.class));
        NewsEventService.RegroupResult rerun = eventService.regroupEvents();
        assertEquals(0, rerun.created() + rerun.merges() + rerun.splits() + rerun.regroups() + rerun.detached(),
                "同库重跑零身份变动");
        assertEquals(migrationsBefore,
                migrationMapper.selectCount(Wrappers.lambdaQuery(NewsEventMigrationDO.class)));
        eventIdBefore.forEach((fid, eventId) -> assertEquals(eventId, eventIdOfItem(fid),
                "fixture-" + fid + " 事件 ID 跨轮稳定"));
    }
}

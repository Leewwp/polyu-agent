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
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.news.controller.request.NewsTopicGovernanceApplyRequest;
import com.nageoffer.ai.ragent.news.controller.vo.NewsTopicGovernanceApplyResultVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsTopicProposalVO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemTopicDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsTopicAliasDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsTopicDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsTopicGovernanceEventDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemTopicMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsSourceMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsTopicAliasMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsTopicGovernanceEventMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsTopicMapper;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchProperties;
import com.nageoffer.ai.ragent.news.fetch.NewsHttpFetchClient;
import com.nageoffer.ai.ragent.news.fetch.NewsUrlNormalizer;
import com.nageoffer.ai.ragent.news.governance.NewsTopicGovernanceProperties;
import com.nageoffer.ai.ragent.news.governance.NewsTopicGovernanceServiceImpl;
import com.nageoffer.ai.ragent.core.parser.HtmlDocumentParser;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
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

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 主题提案治理真库实证（#202）：
 * <ol>
 *   <li><b>merge 关联迁移不丢</b>：去重（同挂两主题）+整批改写 topic_id 的真库
 *       复合主键行为，目标引用计数连续；</li>
 *   <li><b>promote 阈值/目录出现</b>：refs&lt;10 拒、≥10 转正后 curated+active 的
 *       公开目录口径出现且引用计数连续（slug 替换不伤 topic_id 关联）；</li>
 *   <li><b>reject 摘除留痕 + 别名再提不新增行</b>：真富化消费路径
 *       （NewsEnrichService#linkTopics 真库 mappers）命中 rejected 别名零新增、
 *       merged 别名回链目标——幂等拦截的端到端证据；</li>
 *   <li><b>批量端点幂等</b>：同批重跑全 SKIPPED、改判冲突拒绝；</li>
 *   <li><b>首轮 14 条处置回放</b>：按 #202 票面生产底账（refs 形态）在本地真库
 *       执行逐条处置，终态断言+处置表输出（票面证据包素材）。</li>
 * </ol>
 * 门控：CI 无 PG 不跑；本地={@code mvn -pl rag test -Dtest=NewsTopicGovernancePgIt
 * -Dpolyu.pg.it=1}。行以前缀隔离（prop-itgov-/itgov- 提案与目标、it-gov-topic- 源、
 * it-gov-topic.invalid 条目），<b>每用例前环境自清洁基线重置</b>（@BeforeEach 三步，
 * 沿 NewsSourceGovernancePgIt 判例：共享库不做有外键引用表的全表 DELETE，
 * 全局计数断言按前缀过滤防残留污染）。
 */
@EnabledIfSystemProperty(named = "polyu.pg.it", matches = "1")
class NewsTopicGovernancePgIt {

    private static final String PROPOSAL_PREFIX = "prop-itgov-";
    private static final String TARGET_PREFIX = "itgov-";
    private static final String SOURCE_KEY_PREFIX = "it-gov-topic-";
    private static final String ITEM_URL_PREFIX = "https://it-gov-topic.invalid/";

    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(System.getProperty("polyu.pg.it.url", "jdbc:postgresql://127.0.0.1:5434/ragent"));
        dataSource.setUser(System.getProperty("polyu.pg.it.user", "postgres"));
        dataSource.setPassword(System.getProperty("polyu.pg.it.pass", "postgres"));
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            // 与 260930_04_news_topic_governance.sql 同构（幂等自愈；本地库随迁移管道补齐后共存）
            statement.execute("CREATE TABLE IF NOT EXISTS t_news_topic_alias ("
                    + "id BIGSERIAL PRIMARY KEY, alias_key VARCHAR(128) NOT NULL UNIQUE, "
                    + "alias_display VARCHAR(128), action VARCHAR(16) NOT NULL, "
                    + "source_topic_id BIGINT NOT NULL REFERENCES t_news_topic(id), "
                    + "target_topic_id BIGINT REFERENCES t_news_topic(id), "
                    + "operator VARCHAR(64), reason VARCHAR(512), "
                    + "create_time TIMESTAMP NOT NULL DEFAULT now(), update_time TIMESTAMP NOT NULL DEFAULT now())");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_news_topic_alias_source "
                    + "ON t_news_topic_alias(source_topic_id)");
            statement.execute("CREATE TABLE IF NOT EXISTS t_news_topic_governance_event ("
                    + "id BIGSERIAL PRIMARY KEY, topic_id BIGINT NOT NULL REFERENCES t_news_topic(id), "
                    + "action VARCHAR(16) NOT NULL, target_topic_id BIGINT, detail VARCHAR(512), "
                    + "operator VARCHAR(64), event_time TIMESTAMP NOT NULL, "
                    + "create_time TIMESTAMP NOT NULL DEFAULT now())");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_news_topic_gov_event_topic "
                    + "ON t_news_topic_governance_event(topic_id)");
        }
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("topic-gov-it", new JdbcTransactionFactory(), dataSource));
        com.baomidou.mybatisplus.core.config.GlobalConfig globalConfig =
                com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(configuration);
        globalConfig.setMetaObjectHandler(new com.nageoffer.ai.ragent.framework.database.MyMetaObjectHandler());
        MybatisPlusInterceptor pagination = new MybatisPlusInterceptor();
        pagination.addInnerInterceptor(new PaginationInnerInterceptor());
        configuration.addInterceptor(pagination);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, NewsItemDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsSourceDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsTopicDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsItemTopicDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsTopicAliasDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsTopicGovernanceEventDO.class);
        configuration.addMapper(NewsItemMapper.class);
        configuration.addMapper(NewsSourceMapper.class);
        configuration.addMapper(NewsTopicMapper.class);
        configuration.addMapper(NewsItemTopicMapper.class);
        configuration.addMapper(NewsTopicAliasMapper.class);
        configuration.addMapper(NewsTopicGovernanceEventMapper.class);
        sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @AfterAll
    static void cleanUpSeededRows() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            // 先清治理两表（其 FK 引用主题行），再清前缀行——外键安全序
            session.getMapper(NewsTopicAliasMapper.class).delete(null);
            session.getMapper(NewsTopicGovernanceEventMapper.class).delete(null);
            cleanGovernedRows(session);
        }
    }

    /**
     * 每用例前环境自清洁基线重置（三步，沿 NewsSourceGovernancePgIt 判例）：
     * <ol>
     *   <li><b>治理两表全清</b>（alias + governance event：append-only 审计表，
     *       只有出向外键引用，全表 DELETE 可行）——全局计数断言只认自插行；</li>
     *   <li><b>本类前缀残留行清除</b>：@AfterAll 只在整类收尾清一次，方法间也不互污
     *       （links→topics→items→source 的外键安全序）；</li>
     *   <li><b>断言按前缀过滤</b>：库内既有 t_news_topic 行（种子 20+可能的历史提案
     *       残留）不动不删——curated=false 的 pending 面全局查询在断言侧按
     *       prop-itgov-/itgov- 前缀过滤，不依赖库内环境。</li>
     * </ol>
     */
    @BeforeEach
    void resetGovernanceStateToKnownBaseline() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            session.getMapper(NewsTopicAliasMapper.class).delete(null);
            session.getMapper(NewsTopicGovernanceEventMapper.class).delete(null);
            cleanGovernedRows(session);
        }
    }

    /**
     * 前缀行清除（外键安全序：链接→主题→条目（其链接级联）→源）
     */
    private static void cleanGovernedRows(SqlSession session) {
        NewsTopicMapper topicMapper = session.getMapper(NewsTopicMapper.class);
        NewsItemTopicMapper itemTopicMapper = session.getMapper(NewsItemTopicMapper.class);
        NewsItemMapper itemMapper = session.getMapper(NewsItemMapper.class);
        NewsSourceMapper sourceMapper = session.getMapper(NewsSourceMapper.class);
        List<NewsTopicDO> topics = topicMapper.selectList(Wrappers.lambdaQuery(NewsTopicDO.class)
                .likeRight(NewsTopicDO::getSlug, PROPOSAL_PREFIX)
                .or()
                .likeRight(NewsTopicDO::getSlug, TARGET_PREFIX));
        if (!topics.isEmpty()) {
            itemTopicMapper.delete(Wrappers.lambdaQuery(NewsItemTopicDO.class)
                    .in(NewsItemTopicDO::getTopicId, topics.stream().map(NewsTopicDO::getId).toList()));
            topicMapper.delete(Wrappers.lambdaQuery(NewsTopicDO.class)
                    .in(NewsTopicDO::getId, topics.stream().map(NewsTopicDO::getId).toList()));
        }
        itemMapper.delete(Wrappers.lambdaQuery(NewsItemDO.class)
                .likeRight(NewsItemDO::getUrl, ITEM_URL_PREFIX));
        sourceMapper.delete(Wrappers.lambdaQuery(NewsSourceDO.class)
                .likeRight(NewsSourceDO::getSourceKey, SOURCE_KEY_PREFIX));
    }

    // ==================== 造数与工具 ====================

    private static NewsSourceDO insertSource(SqlSession session, String runId) {
        NewsSourceDO source = NewsSourceDO.builder()
                .sourceKey(SOURCE_KEY_PREFIX + runId).platform("official")
                .displayName("IT Gov Topic " + runId)
                .fetchEndpoint("https://it-gov-topic.invalid/list")
                .fetchStrategy("HTML_LIST").enabled(true).build();
        session.getMapper(NewsSourceMapper.class).insert(source);
        return source;
    }

    private static NewsItemDO insertItem(SqlSession session, Long sourceId, String slug) {
        String url = ITEM_URL_PREFIX + slug;
        NewsItemDO item = NewsItemDO.builder()
                .sourceId(sourceId).url(url).urlHash(NewsUrlNormalizer.urlHash(url))
                .titleEn("IT gov item " + slug).langRaw("en")
                .status("published").category("other").heat(0).build();
        session.getMapper(NewsItemMapper.class).insert(item);
        return item;
    }

    private static NewsTopicDO insertTopic(SqlSession session, String slug, String nameZh, String nameEn,
                                           String group, boolean curated) {
        NewsTopicDO topic = NewsTopicDO.builder()
                .slug(slug).nameZh(nameZh).nameEn(nameEn).topicGroup(group)
                .curated(curated).status("active").build();
        session.getMapper(NewsTopicMapper.class).insert(topic);
        return topic;
    }

    /** 提案形态（对齐 NewsEnrichService#createProposal：英文 token 双名列同串占位） */
    private static NewsTopicDO insertProposal(SqlSession session, String token) {
        return insertTopic(session, PROPOSAL_PREFIX + token, token, token, "PROPOSED", false);
    }

    private static void link(SqlSession session, Long itemId, Long topicId) {
        session.getMapper(NewsItemTopicMapper.class).insert(
                NewsItemTopicDO.builder().itemId(itemId).topicId(topicId).build());
    }

    private static NewsTopicGovernanceServiceImpl governanceService(SqlSession session) {
        return new NewsTopicGovernanceServiceImpl(session.getMapper(NewsTopicMapper.class),
                session.getMapper(NewsItemTopicMapper.class), session.getMapper(NewsTopicAliasMapper.class),
                session.getMapper(NewsTopicGovernanceEventMapper.class), new NewsTopicGovernanceProperties());
    }

    /** 真库 mappers 接线的富化服务（linkTopics 消费路径实证别名拦截） */
    private static NewsEnrichService enrichService(SqlSession session) {
        PromptTemplateLoader loader = mock(PromptTemplateLoader.class);
        when(loader.load(anyString())).thenReturn("# 模板\n");
        return new NewsEnrichService(session.getMapper(NewsItemMapper.class),
                session.getMapper(NewsSourceMapper.class), session.getMapper(NewsTopicMapper.class),
                session.getMapper(NewsItemTopicMapper.class), session.getMapper(NewsTopicAliasMapper.class),
                mock(NewsHttpFetchClient.class), new HtmlDocumentParser(), mock(NewsLlmBudgetService.class),
                loader, new com.fasterxml.jackson.databind.ObjectMapper(), new NewsFetchProperties(),
                java.util.Date::new);
    }

    private static NewsTopicGovernanceApplyRequest.NewsTopicDisposition disposition(
            Long topicId, String action, Long mergeTarget, String promoteSlug, String promoteGroup, String reason) {
        NewsTopicGovernanceApplyRequest.NewsTopicDisposition d =
                new NewsTopicGovernanceApplyRequest.NewsTopicDisposition();
        d.setTopicId(topicId);
        d.setAction(action);
        d.setMergeTargetTopicId(mergeTarget);
        d.setPromoteSlug(promoteSlug);
        d.setPromoteGroup(promoteGroup);
        d.setReason(reason);
        return d;
    }

    private static long linksOf(SqlSession session, Long topicId) {
        Long count = session.getMapper(NewsItemTopicMapper.class).selectCount(
                Wrappers.lambdaQuery(NewsItemTopicDO.class).eq(NewsItemTopicDO::getTopicId, topicId));
        return count == null ? 0L : count;
    }

    private static long topicRowCount(SqlSession session) {
        Long count = session.getMapper(NewsTopicMapper.class).selectCount(
                Wrappers.lambdaQuery(NewsTopicDO.class)
                        .likeRight(NewsTopicDO::getSlug, PROPOSAL_PREFIX)
                        .or()
                        .likeRight(NewsTopicDO::getSlug, TARGET_PREFIX));
        return count == null ? 0L : count;
    }

    // ==================== 用例 ====================

    /**
     * merge 关联迁移不丢（真库复合主键行为）：同挂两主题的源侧行去重（目标行保留）、
     * 其余整批改写 topic_id——目标引用计数连续；merged 别名在真富化消费路径再提
     * 不新增行且回链目标
     */
    @Test
    void mergeMigratesLinksNoLossAndAliasReproposalLinksTargetOnRealPg() {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            NewsSourceDO source = insertSource(session, runId);
            NewsTopicDO target = insertTopic(session, TARGET_PREFIX + "campus-" + runId,
                    "校园生活", "Campus Life", "STUDENT_AFFAIRS", true);
            NewsTopicDO proposal = insertProposal(session, "culture-" + runId);
            NewsItemDO item1 = insertItem(session, source.getId(), runId + "-1");
            NewsItemDO item2 = insertItem(session, source.getId(), runId + "-2");
            NewsItemDO item3 = insertItem(session, source.getId(), runId + "-3");
            NewsItemDO item4 = insertItem(session, source.getId(), runId + "-4");
            link(session, item1.getId(), proposal.getId());
            link(session, item2.getId(), proposal.getId());
            link(session, item2.getId(), target.getId());
            link(session, item3.getId(), target.getId());
            assertEquals(2L, linksOf(session, target.getId()), "迁移前目标 2 关联（item2 双挂+item3）");
            assertEquals(2L, linksOf(session, proposal.getId()), "迁移前源提案 2 关联");

            NewsTopicGovernanceApplyResultVO result = governanceService(session).applyBatch(
                    List.of(disposition(proposal.getId(), "MERGE", target.getId(), null, null,
                            "文化活动≈校园生活（campus 24 引用）")), "it-admin");

            assertEquals(1, result.getAppliedCount());
            NewsTopicDO merged = session.getMapper(NewsTopicMapper.class).selectById(proposal.getId());
            assertEquals(NewsTopicDO.STATUS_MERGED, merged.getStatus(), "提案行软状态 merged");
            assertFalse(merged.getCurated(), "curated 保持 false（不进目录）");
            assertEquals(3L, linksOf(session, target.getId()), "迁移后目标 3 关联=2 既有+1 迁移，不丢不重");
            assertEquals(0L, linksOf(session, proposal.getId()), "源提案零残留关联");
            List<NewsItemTopicDO> item2Links = session.getMapper(NewsItemTopicMapper.class).selectList(
                    Wrappers.lambdaQuery(NewsItemTopicDO.class).eq(NewsItemTopicDO::getItemId, item2.getId()));
            assertEquals(1, item2Links.size(), "同挂两主题的条目去重后单行（复合主键不撞）");
            assertEquals(target.getId(), item2Links.get(0).getTopicId());
            NewsTopicAliasDO alias = session.getMapper(NewsTopicAliasMapper.class).selectOne(
                    Wrappers.lambdaQuery(NewsTopicAliasDO.class)
                            .eq(NewsTopicAliasDO::getAliasKey, "culture-" + runId));
            assertNotNull(alias, "merged 别名入账（规范化键）");
            assertEquals(NewsTopicAliasDO.ACTION_MERGED, alias.getAction());
            assertEquals(target.getId(), alias.getTargetTopicId());
            List<NewsTopicGovernanceEventDO> events = session.getMapper(NewsTopicGovernanceEventMapper.class)
                    .selectList(Wrappers.lambdaQuery(NewsTopicGovernanceEventDO.class)
                            .eq(NewsTopicGovernanceEventDO::getTopicId, proposal.getId()));
            assertEquals(1, events.size());
            assertTrue(events.get(0).getDetail().contains("migrated=1")
                    && events.get(0).getDetail().contains("deduped=1"), events.get(0).getDetail());

            // ── 别名再提（真富化消费路径）：「culture-<runId>」新提案不落行，直接回链目标 ──
            long topicsBefore = topicRowCount(session);
            enrichService(session).linkTopics(item4.getId(), List.of("culture-" + runId));
            assertEquals(topicsBefore, topicRowCount(session), "命中 merged 别名再提不新增主题行（幂等）");
            List<NewsItemTopicDO> item4Links = session.getMapper(NewsItemTopicMapper.class).selectList(
                    Wrappers.lambdaQuery(NewsItemTopicDO.class).eq(NewsItemTopicDO::getItemId, item4.getId()));
            assertEquals(1, item4Links.size());
            assertEquals(target.getId(), item4Links.get(0).getTopicId(), "merged 别名回链目标 curated 主题");
        }
    }

    /**
     * promote 阈值门与目录出现（真库）：9 引用拒绝、10 引用转正——curated=true+active
     * 的公开目录口径出现且引用计数连续（稳定 slug 替换 prop- 哈希不伤 topic_id 关联）
     */
    @Test
    void promoteThresholdGateAndCatalogAppearanceOnRealPg() {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            NewsSourceDO source = insertSource(session, runId);
            NewsTopicDO proposal = insertProposal(session, "research-" + runId);
            for (int i = 0; i < 9; i++) {
                link(session, insertItem(session, source.getId(), runId + "-r" + i).getId(), proposal.getId());
            }
            NewsTopicGovernanceServiceImpl service = governanceService(session);
            ClientException below = assertThrows(ClientException.class, () -> service.applyBatch(
                    List.of(disposition(proposal.getId(), "PROMOTE", null,
                            TARGET_PREFIX + "research-" + runId, "RESEARCH", "x")), "it-admin"),
                    "9<10 未达阈值");
            assertTrue(below.getMessage().contains("阈值"), below.getMessage());

            link(session, insertItem(session, source.getId(), runId + "-r9").getId(), proposal.getId());
            NewsTopicGovernanceApplyResultVO result = service.applyBatch(
                    List.of(disposition(proposal.getId(), "PROMOTE", null,
                            TARGET_PREFIX + "research-" + runId, "RESEARCH", "唯一过阈值行")), "it-admin");
            assertEquals(1, result.getAppliedCount());

            NewsTopicDO promoted = session.getMapper(NewsTopicMapper.class).selectById(proposal.getId());
            assertTrue(promoted.getCurated(), "curated=true 进公开目录");
            assertEquals(NewsTopicDO.STATUS_ACTIVE, promoted.getStatus());
            assertEquals("RESEARCH", promoted.getTopicGroup(), "归入正式组");
            assertEquals(TARGET_PREFIX + "research-" + runId, promoted.getSlug(), "稳定 slug 替换 prop- 哈希");
            assertEquals(10L, linksOf(session, promoted.getId()), "slug 变更不伤 topic_id 关联（引用连续）");

            // 公开目录口径（listCuratedTopics 同判据）：curated+active 出现且计数=10
            List<NewsTopicDO> catalog = session.getMapper(NewsTopicMapper.class).selectList(
                    Wrappers.lambdaQuery(NewsTopicDO.class)
                            .eq(NewsTopicDO::getStatus, NewsTopicDO.STATUS_ACTIVE)
                            .eq(NewsTopicDO::getCurated, true)
                            .eq(NewsTopicDO::getSlug, TARGET_PREFIX + "research-" + runId));
            assertEquals(1, catalog.size(), "公开目录出现（同 listCuratedTopics 过滤口径）");
            assertEquals(10L, linksOf(session, catalog.get(0).getId()));

            // pending 面不再含该行（前缀过滤）
            List<NewsTopicProposalVO> pending = service.listPendingProposals().stream()
                    .filter(p -> p.getSlug().startsWith(PROPOSAL_PREFIX)).toList();
            assertTrue(pending.stream().noneMatch(p -> p.getId().equals(proposal.getId())));

            // ── 收口票 #206：幂等先于阈值——转正后关联被保留期清理摘空，同批重放仍 SKIPPED ──
            session.getMapper(NewsItemTopicMapper.class).delete(Wrappers.lambdaQuery(NewsItemTopicDO.class)
                    .eq(NewsItemTopicDO::getTopicId, promoted.getId()));
            assertEquals(0L, linksOf(session, promoted.getId()));
            NewsTopicGovernanceApplyResultVO rerun = service.applyBatch(
                    List.of(disposition(proposal.getId(), "PROMOTE", null,
                            TARGET_PREFIX + "research-" + runId, "RESEARCH", "重跑")), "it-admin");
            assertEquals(0, rerun.getAppliedCount(), "引用回落不阻断幂等重放");
            assertEquals("SKIPPED", rerun.getResults().get(0).getOutcome());
        }
    }

    /**
     * reject 摘除留痕 + rejected 别名在真富化消费路径再提零新增零关联（幂等核心断言）
     */
    @Test
    void rejectDetachesLinksAndAliasBlocksReproposalOnRealPg() {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            NewsSourceDO source = insertSource(session, runId);
            NewsTopicDO proposal = insertProposal(session, "polyu-" + runId);
            NewsItemDO linked = insertItem(session, source.getId(), runId + "-p1");
            NewsItemDO fresh = insertItem(session, source.getId(), runId + "-p2");
            link(session, linked.getId(), proposal.getId());

            NewsTopicGovernanceApplyResultVO result = governanceService(session).applyBatch(
                    List.of(disposition(proposal.getId(), "REJECT", null, null, null,
                            "全站皆 PolyU 零区分度（09-30 再提=别名账必要性证据）")), "it-admin");
            assertEquals(1, result.getAppliedCount());

            NewsTopicDO rejected = session.getMapper(NewsTopicMapper.class).selectById(proposal.getId());
            assertEquals(NewsTopicDO.STATUS_REJECTED, rejected.getStatus(), "软状态 rejected（行保留不硬删）");
            assertFalse(rejected.getCurated());
            assertEquals(0L, linksOf(session, proposal.getId()), "残留关联已摘除");
            NewsTopicAliasDO alias = session.getMapper(NewsTopicAliasMapper.class).selectOne(
                    Wrappers.lambdaQuery(NewsTopicAliasDO.class)
                            .eq(NewsTopicAliasDO::getAliasKey, "polyu-" + runId));
            assertNotNull(alias);
            assertEquals(NewsTopicAliasDO.ACTION_REJECTED, alias.getAction());
            List<NewsTopicGovernanceEventDO> events = session.getMapper(NewsTopicGovernanceEventMapper.class)
                    .selectList(Wrappers.lambdaQuery(NewsTopicGovernanceEventDO.class)
                            .eq(NewsTopicGovernanceEventDO::getTopicId, proposal.getId()));
            assertEquals(1, events.size());
            assertTrue(events.get(0).getDetail().contains("detachedLinks=1"), events.get(0).getDetail());

            // ── rejected 别名再提（真富化消费路径）：不新增主题行、不挂关联 ──
            long topicsBefore = topicRowCount(session);
            enrichService(session).linkTopics(fresh.getId(), List.of("polyu-" + runId));
            assertEquals(topicsBefore, topicRowCount(session), "命中 rejected 别名再提不新增主题行（幂等）");
            Long freshLinks = session.getMapper(NewsItemTopicMapper.class).selectCount(
                    Wrappers.lambdaQuery(NewsItemTopicDO.class).eq(NewsItemTopicDO::getItemId, fresh.getId()));
            assertEquals(0L, freshLinks, "rejected 别名不挂关联");
        }
    }

    /**
     * 批量端点幂等（真库）：同批重跑全 SKIPPED 零重复留痕；改判冲突整批拒绝
     */
    @Test
    void batchRerunIdempotentAndConflictingTrackRejectedOnRealPg() {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            NewsTopicDO target = insertTopic(session, TARGET_PREFIX + "admin-" + runId,
                    "校务公告", "Official Notices", "STUDENT_AFFAIRS", true);
            NewsTopicDO toMerge = insertProposal(session, "service-" + runId);
            NewsTopicDO toReject = insertProposal(session, "network-" + runId);
            NewsTopicGovernanceServiceImpl service = governanceService(session);
            List<NewsTopicGovernanceApplyRequest.NewsTopicDisposition> batch = List.of(
                    disposition(toMerge.getId(), "MERGE", target.getId(), null, null, "服务公告≈校务公告"),
                    disposition(toReject.getId(), "REJECT", null, null, null, "语义模糊泛化"));

            NewsTopicGovernanceApplyResultVO first = service.applyBatch(batch, "it-admin");
            assertEquals(2, first.getAppliedCount());
            NewsTopicGovernanceApplyResultVO rerun = service.applyBatch(batch, "it-admin");
            assertEquals(0, rerun.getAppliedCount(), "同批重跑幂等");
            assertEquals(2, rerun.getSkippedCount());
            assertEquals("SKIPPED", rerun.getResults().get(0).getOutcome());

            Long eventCount = session.getMapper(NewsTopicGovernanceEventMapper.class).selectCount(null);
            assertEquals(2L, eventCount, "重跑不重复留痕");

            assertThrows(ClientException.class, () -> service.applyBatch(
                            List.of(disposition(toMerge.getId(), "REJECT", null, null, null, "改判")), "it-admin"),
                    "已 merged 再 reject=改判冲突拒绝（软状态不自动反转）");

            // ── 收口票 #206：promote 轨终态防线——已 merged 行提升=冲突拒绝且零新留痕 ──
            assertThrows(ClientException.class, () -> service.applyBatch(
                            List.of(disposition(toMerge.getId(), "PROMOTE", null,
                                    TARGET_PREFIX + "promote-" + runId, "RESEARCH", "终态提升")), "it-admin"),
                    "已 merged 再 promote=改判冲突拒绝（不得假留痕）");
            assertEquals(2L, session.getMapper(NewsTopicGovernanceEventMapper.class).selectCount(null),
                    "两次冲突拒绝均零新留痕");
        }
    }

    /**
     * 首轮 14 条处置回放（#202 票面建议表的本地真库执行）：按生产底账 refs 形态
     * 造 14 提案（research 19/alumni 2/culture 2/service 1/leadership 1/teaching 1/
     * network 1/polyu 1/其余 0），逐条处置（建议表微调见各 reason），终态断言+
     * 处置表打印（票面证据包素材）。merge 目标为本用例自建前缀 curated 行
     * （itgov-campus/admin/event 对应票面 campus/admin/event，语义等价、外键安全）
     */
    @Test
    void firstRoundFourteenDispositionReplayOnRealPg() {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            NewsSourceDO source = insertSource(session, runId);
            NewsTopicDO campus = insertTopic(session, TARGET_PREFIX + "campus-" + runId,
                    "校园生活", "Campus Life", "STUDENT_AFFAIRS", true);
            NewsTopicDO admin = insertTopic(session, TARGET_PREFIX + "admin-" + runId,
                    "校务公告", "Official Notices", "STUDENT_AFFAIRS", true);
            NewsTopicDO event = insertTopic(session, TARGET_PREFIX + "event-" + runId,
                    "活动讲座", "Events & Lectures", "STUDENT_AFFAIRS", true);

            // 生产底账形态：票面 14 行（id 21-34 的名称与引用数）
            record Row(String token, int refs) {}
            List<Row> rows = List.of(
                    new Row("research", 19), new Row("education", 0), new Row("fashion", 0),
                    new Row("design", 0), new Row("graduation", 0), new Row("alumni", 2),
                    new Row("efficiency", 0), new Row("culture", 2), new Row("service", 1),
                    new Row("leadership", 1), new Row("teaching", 1), new Row("network", 1),
                    new Row("language", 0), new Row("polyu", 1));
            java.util.Map<String, NewsTopicDO> proposals = new java.util.LinkedHashMap<>();
            for (Row row : rows) {
                NewsTopicDO proposal = insertProposal(session, row.token());
                proposals.put(row.token(), proposal);
                for (int i = 0; i < row.refs(); i++) {
                    link(session, insertItem(session, source.getId(), runId + "-" + row.token() + "-" + i)
                            .getId(), proposal.getId());
                }
            }
            assertEquals(14, proposals.size());

            NewsTopicGovernanceServiceImpl service = governanceService(session);
            // pending 面建议：research=promote（19≥10）、alumni=review（2）、零引用=reject
            List<NewsTopicProposalVO> pending = service.listPendingProposals().stream()
                    .filter(p -> p.getSlug().startsWith(PROPOSAL_PREFIX)).toList();
            assertEquals(14, pending.size());
            assertEquals("promote", pending.get(0).getSuggestedAction());
            assertEquals("review", pending.stream().filter(p -> p.getSlug().endsWith("alumni"))
                    .findFirst().orElseThrow().getSuggestedAction());
            assertEquals("reject", pending.stream().filter(p -> p.getSlug().endsWith("efficiency"))
                    .findFirst().orElseThrow().getSuggestedAction());

            // 逐条处置（票面建议表+微调理由；alumni pending 不处置）
            List<NewsTopicGovernanceApplyRequest.NewsTopicDisposition> batch = List.of(
                    disposition(proposals.get("research").getId(), "PROMOTE", null,
                            TARGET_PREFIX + "research-" + runId, "RESEARCH",
                            "RESEARCH 组无通用「科研」位；唯一过阈值行（19≥10）；策展名拟定"),
                    disposition(proposals.get("graduation").getId(), "MERGE", event.getId(), null, null,
                            "毕业活动≈活动讲座（event 44 引用）"),
                    disposition(proposals.get("culture").getId(), "MERGE", campus.getId(), null, null,
                            "文化活动≈校园生活（campus 24 引用）"),
                    disposition(proposals.get("service").getId(), "MERGE", admin.getId(), null, null,
                            "服务公告≈校务公告（admin 描述含「政策与服务调整」）"),
                    disposition(proposals.get("leadership").getId(), "MERGE", admin.getId(), null, null,
                            "人事/领导动态≈校务公告"),
                    disposition(proposals.get("teaching").getId(), "MERGE", campus.getId(), null, null,
                            "教学动态（课程与教学活动报道）≈校园生活"),
                    disposition(proposals.get("education").getId(), "REJECT", null, null, null,
                            "零引用泛化；教育≠招生（admission），近义不成立"),
                    disposition(proposals.get("fashion").getId(), "REJECT", null, null, null,
                            "零引用弃；注记：FACULTY 组缺 SFT（时装及纺织）学院位=人工策展种子缺口，非本票范围"),
                    disposition(proposals.get("design").getId(), "REJECT", null, null, null,
                            "零引用弃；注记：FACULTY 组缺 School of Design 学院位=人工策展种子缺口，非本票范围"),
                    disposition(proposals.get("efficiency").getId(), "REJECT", null, null, null,
                            "典型泛化零值"),
                    disposition(proposals.get("network").getId(), "REJECT", null, null, null,
                            "语义模糊泛化（人际/IT/合作网络三义）"),
                    disposition(proposals.get("language").getId(), "REJECT", null, null, null,
                            "泛化（语言中心活动可归 campus/event，词本身零区分度）"),
                    disposition(proposals.get("polyu").getId(), "REJECT", null, null, null,
                            "全站皆 PolyU 零区分度（09-30 再提=别名账必要性证据）"));

            NewsTopicGovernanceApplyResultVO result = service.applyBatch(batch, "replay");
            assertEquals(13, result.getAppliedCount(), "13 条处置全落（alumni pending 观察不处置）");
            assertEquals(0, result.getSkippedCount());

            // ── 终态断言 ──
            NewsTopicMapper topicMapper = session.getMapper(NewsTopicMapper.class);
            NewsTopicDO research = topicMapper.selectById(proposals.get("research").getId());
            assertTrue(research.getCurated() && NewsTopicDO.STATUS_ACTIVE.equals(research.getStatus())
                    && "RESEARCH".equals(research.getTopicGroup()));
            assertEquals(19L, linksOf(session, research.getId()), "promote 引用连续（19）");
            for (String merged : List.of("graduation", "culture", "service", "leadership", "teaching")) {
                assertEquals(NewsTopicDO.STATUS_MERGED, topicMapper.selectById(proposals.get(merged).getId())
                        .getStatus(), merged + " → merged");
                assertEquals(0L, linksOf(session, proposals.get(merged).getId()), merged + " 关联全迁走");
            }
            for (String rejectedToken : List.of("education", "fashion", "design", "efficiency",
                    "network", "language", "polyu")) {
                NewsTopicDO rejectedRow = topicMapper.selectById(proposals.get(rejectedToken).getId());
                assertEquals(NewsTopicDO.STATUS_REJECTED, rejectedRow.getStatus(), rejectedToken + " → rejected");
                assertEquals(0L, linksOf(session, rejectedRow.getId()));
            }
            // merge 目标计数连续：campus=culture 2+teaching 1=3；admin=service 1+leadership 1=2；event=graduation 0
            assertEquals(3L, linksOf(session, campus.getId()));
            assertEquals(2L, linksOf(session, admin.getId()));
            assertEquals(0L, linksOf(session, event.getId()));
            // alumni 仍 pending：active+curated=false+2 关联
            NewsTopicDO alumni = topicMapper.selectById(proposals.get("alumni").getId());
            assertEquals(NewsTopicDO.STATUS_ACTIVE, alumni.getStatus());
            assertFalse(alumni.getCurated());
            assertEquals(2L, linksOf(session, alumni.getId()));
            // 留痕 13 行 + 别名 12 键（merged 5+rejected 7；英文名双列同串单键）
            assertEquals(13L, session.getMapper(NewsTopicGovernanceEventMapper.class).selectCount(null));
            assertEquals(12L, session.getMapper(NewsTopicAliasMapper.class).selectCount(null));
            // 公开目录出现 research（curated+active 同判据）
            assertEquals(1L, topicMapper.selectCount(Wrappers.lambdaQuery(NewsTopicDO.class)
                    .eq(NewsTopicDO::getCurated, true)
                    .eq(NewsTopicDO::getStatus, NewsTopicDO.STATUS_ACTIVE)
                    .eq(NewsTopicDO::getSlug, TARGET_PREFIX + "research-" + runId)));

            // ── 处置表输出（票面证据包素材）──
            List<String> table = new ArrayList<>();
            table.add("| 提案 | 轨 | 目标/理由 | 结果 |");
            table.add("|---|---|---|---|");
            table.add("| research (19 refs) | PROMOTE | RESEARCH 组无通用位，19≥10 唯一过线 | "
                    + result.getResults().get(0).getOutcome() + " |");
            String[] mergeNames = {"graduation", "culture", "service", "leadership", "teaching"};
            String[] mergeTargets = {"event", "campus", "admin", "admin", "campus"};
            for (int i = 0; i < mergeNames.length; i++) {
                table.add("| " + mergeNames[i] + " | MERGE | →" + mergeTargets[i] + " | "
                        + result.getResults().get(i + 1).getOutcome() + " |");
            }
            String[] rejectNames = {"education", "fashion", "design", "efficiency", "network", "language", "polyu"};
            for (int i = 0; i < rejectNames.length; i++) {
                table.add("| " + rejectNames[i] + " | REJECT | 泛化弃（fashion/design 注 FACULTY 策展缺口） | "
                        + result.getResults().get(i + 6).getOutcome() + " |");
            }
            table.add("| alumni (2 refs) | PENDING | 未达阈值；批 2 alumni-news 源 ~10-02 启用后观察 | 不处置 |");
            System.out.println("[first-round-replay] " + runId + "\n" + String.join("\n", table));
        }
    }
}

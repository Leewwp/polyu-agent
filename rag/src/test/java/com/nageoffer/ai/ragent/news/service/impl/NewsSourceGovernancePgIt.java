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
import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceHealthEventDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsSourceHealthEventMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsSourceMapper;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchOutcome;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchProperties;
import com.nageoffer.ai.ragent.news.fetch.NewsSourceFetcher;
import com.nageoffer.ai.ragent.news.fetch.NewsUrlNormalizer;
import com.nageoffer.ai.ragent.news.fetch.RawNewsItem;
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
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 源治理真库实证（#186）：mock 单测证明不了的部分——
 * <ol>
 *   <li><b>迁移判据</b>：260930_news_source_governance.sql 的存量禁用行 UPDATE 判据
 *       （consecutive_failures≥3 → auto，其余保守 manual）+ 幂等守卫（已分类行不重判）；</li>
 *   <li><b>三分探活查询</b>：probeSweep 的真库 WHERE（enabled=false AND
 *       disabled_reason='auto'）——manual/policy 行天然不进探活（互不误复活）；</li>
 *   <li><b>跨轮探活复归回放</b>：真库跨两日两次有效完整成功 → enabled=true +
 *       disabled_reason=NULL + recovered_time 落行，事件流水 probe_pass/recovered
 *       可查；探活候选即弃——t_news_item 零新增（复归入 #185/#184 统一门）。</li>
 * </ol>
 * 门控：CI 无 PG 不跑（默认跳过）；本地执行={@code mvn -pl rag test
 * -Dtest=NewsSourceGovernancePgIt -Dpolyu.pg.it=1}（-Dpolyu.pg.it.url/user/pass
 * 可覆盖默认连接）。行以 it-gov- 前缀隔离，收尾自清；<b>每用例前清种自净</b>
 * （{@code @BeforeEach}，沿 KeyDateSyncPgIt 先例）——本地 polyu-pg 为门控 IT
 * 共享库，probeSweep 又是全局扫描，用例不得依赖库内既有行（详见 reset 方法注释）。
 */
@EnabledIfSystemProperty(named = "polyu.pg.it", matches = "1")
class NewsSourceGovernancePgIt {

    private static final String PREFIX = "it-gov-";
    private static final long HOUR = 3600L * 1000;

    private static SqlSessionFactory sqlSessionFactory;
    private static final AtomicReference<Date> clockRef = new AtomicReference<>();

    @BeforeAll
    static void setUpDatabase() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(System.getProperty("polyu.pg.it.url", "jdbc:postgresql://127.0.0.1:5434/ragent"));
        dataSource.setUser(System.getProperty("polyu.pg.it.user", "postgres"));
        dataSource.setPassword(System.getProperty("polyu.pg.it.pass", "postgres"));
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            // 与 260930_news_source_governance.sql 同构（幂等自愈；本地库随迁移管道补齐后共存）
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
        configuration.setEnvironment(new Environment("gov-it", new JdbcTransactionFactory(), dataSource));
        com.baomidou.mybatisplus.core.config.GlobalConfig globalConfig =
                com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(configuration);
        globalConfig.setMetaObjectHandler(new com.nageoffer.ai.ragent.framework.database.MyMetaObjectHandler());
        MybatisPlusInterceptor pagination = new MybatisPlusInterceptor();
        pagination.addInnerInterceptor(new PaginationInnerInterceptor());
        configuration.addInterceptor(pagination);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, NewsItemDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsSourceDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsSourceHealthEventDO.class);
        configuration.addMapper(NewsItemMapper.class);
        configuration.addMapper(NewsSourceMapper.class);
        configuration.addMapper(NewsSourceHealthEventMapper.class);
        sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @AfterAll
    static void cleanUpSeededRows() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            NewsSourceHealthEventMapper eventMapper = session.getMapper(NewsSourceHealthEventMapper.class);
            NewsSourceMapper sourceMapper = session.getMapper(NewsSourceMapper.class);
            sourceMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(NewsSourceDO.class)
                            .likeRight(NewsSourceDO::getSourceKey, PREFIX))
                    .forEach(row -> {
                        eventMapper.delete(com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(
                                        NewsSourceHealthEventDO.class)
                                .eq(NewsSourceHealthEventDO::getSourceId, row.getId()));
                        sourceMapper.deleteById(row.getId());
                    });
        }
    }

    /**
     * 每用例前清种自净（审核修正 [P1-测试隔离]，沿 KeyDateSyncPgIt @BeforeEach 先例）：
     * 修前 probeSweep 全局扫描会把库内既有 auto 行（如 260930 迁移在共享库回填的
     * youtube/gnews 9 行）连探——「day1.probed()==1」类全表计数断言在已应用迁移的库上
     * 必红（审核者复现 expected 1 but was 10）。已知态三步：
     * <ul>
     *   <li><b>事件流水全清</b>（append-only 审计表，无入向外键引用，全表 DELETE 可行）：
     *       事件计数/顺序断言只认自插行，且顺带清掉共享库残留事件；</li>
     *   <li><b>本类前缀残留行清除</b>：@AfterAll 只在整类收尾清一次——方法间也不互污
     *       （如迁移用例留下的已分类 it-gov 行会混进下一用例的探活面）；</li>
     *   <li><b>探活面归零</b>：把库内既有 enabled=false AND disabled_reason='auto' 行
     *       回置 NULL（=迁移前「未判定」态，重跑 260930 迁移可再分类）。t_news_source
     *       不做全表 DELETE——t_news_item.source_id 外键引用既有源行；此后每用例自造
     *       全部前置（迁移用例自插未分类禁用行），不依赖任何库内环境。</li>
     * </ul>
     */
    @BeforeEach
    void resetGovernanceStateToKnownBaseline() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            NewsSourceHealthEventMapper eventMapper = session.getMapper(NewsSourceHealthEventMapper.class);
            eventMapper.delete(null);
            NewsSourceMapper sourceMapper = session.getMapper(NewsSourceMapper.class);
            sourceMapper.delete(com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(NewsSourceDO.class)
                    .likeRight(NewsSourceDO::getSourceKey, PREFIX));
            sourceMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaUpdate(NewsSourceDO.class)
                    .eq(NewsSourceDO::getEnabled, false)
                    .eq(NewsSourceDO::getDisabledReason, NewsSourceDO.DISABLED_REASON_AUTO)
                    .set(NewsSourceDO::getDisabledReason, null));
        }
    }

    private static NewsSourceDO insertSource(SqlSession session, String key, boolean enabled,
                                             Integer failures, String disabledReason) {
        NewsSourceDO source = NewsSourceDO.builder()
                .sourceKey(key).platform("official").displayName("IT " + key)
                .fetchEndpoint("https://it-gov.invalid/" + key)
                .fetchStrategy("HTML_LIST").enabled(enabled)
                .consecutiveFailures(failures == null ? 0 : failures)
                .disabledReason(disabledReason).build();
        session.getMapper(NewsSourceMapper.class).insert(source);
        return source;
    }

    /**
     * 迁移判据实证：≥3 败禁用行 → auto（恢复探活资格）；&lt;3 败禁用行 → manual
     * （保守不自动复活）；已分类行不被重判（幂等守卫——人工改判不被覆盖）。
     * UPDATE 与 260930_news_source_governance.sql 同构。
     */
    @Test
    void migrationCriteriaClassifyLegacyDisabledRowsOnRealPg() throws Exception {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            // 存量形态：#186 前的禁用行只有 enabled=false + consecutive_failures
            NewsSourceDO autoLike = insertSource(session, PREFIX + "auto-" + runId, false, 3, null);
            NewsSourceDO manualLikeLowCount = insertSource(session, PREFIX + "manual-low-" + runId, false, 0, null);
            NewsSourceDO manualLikeMidCount = insertSource(session, PREFIX + "manual-mid-" + runId, false, 2, null);
            NewsSourceDO enabledRow = insertSource(session, PREFIX + "enabled-" + runId, true, 2, null);

            try (Connection connection = sqlSessionFactory.getConfiguration().getEnvironment()
                    .getDataSource().getConnection();
                 Statement statement = connection.createStatement()) {
                // 与 260930 迁移 SQL 同构：一次性守卫迁移（@BeforeEach 已清本类前缀残留
                // 与共享库 auto 行——判定的行集=本用例自插的 3 行，计数确定）
                int classified = statement.executeUpdate(
                        "UPDATE t_news_source SET disabled_reason = "
                                + "CASE WHEN consecutive_failures >= 3 THEN 'auto' ELSE 'manual' END "
                                + "WHERE enabled = false AND disabled_reason IS NULL "
                                + "AND source_key LIKE '" + PREFIX + "%'");
                assertEquals(3, classified, "被判定的=本用例自插的三条未分类禁用行（环境隔离后确定）");
            }

            NewsSourceMapper sourceMapper = session.getMapper(NewsSourceMapper.class);
            assertEquals(NewsSourceDO.DISABLED_REASON_AUTO,
                    sourceMapper.selectById(autoLike.getId()).getDisabledReason(),
                    "3 败禁用行=自动隔离痕迹 → auto（探活对象）");
            assertEquals(NewsSourceDO.DISABLED_REASON_MANUAL,
                    sourceMapper.selectById(manualLikeLowCount.getId()).getDisabledReason(),
                    "0 败禁用行=人工停用嫌疑 → manual（保守不自动复活）");
            assertEquals(NewsSourceDO.DISABLED_REASON_MANUAL,
                    sourceMapper.selectById(manualLikeMidCount.getId()).getDisabledReason(),
                    "2 败禁用行=未达自动禁用阈值 → manual（未知原因不得一律当自动故障复活）");
            assertNull(sourceMapper.selectById(enabledRow.getId()).getDisabledReason(),
                    "启用行不参与判据迁移（NULL=启用中）");

            // 幂等守卫：人工把 auto 行改判 manual 后，重跑迁移不得覆盖（disabled_reason IS NULL 守卫）
            try (Connection connection = sqlSessionFactory.getConfiguration().getEnvironment()
                    .getDataSource().getConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE t_news_source SET disabled_reason = 'manual' WHERE id = "
                        + autoLike.getId());
                statement.executeUpdate(
                        "UPDATE t_news_source SET disabled_reason = "
                                + "CASE WHEN consecutive_failures >= 3 THEN 'auto' ELSE 'manual' END "
                                + "WHERE enabled = false AND disabled_reason IS NULL "
                                + "AND source_key LIKE '" + PREFIX + "%'");
            }
            // 原生 JDBC 改库不经 MyBatis——清一级缓存防 selectById 读回旧值
            session.clearCache();
            assertEquals(NewsSourceDO.DISABLED_REASON_MANUAL,
                    sourceMapper.selectById(autoLike.getId()).getDisabledReason(),
                    "已分类行不被重跑迁移覆盖（人工改判保留）");
        }
    }

    /**
     * 跨轮探活复归回放（真库）：三分查询只取 auto 行 → 两日两次有效完整成功 →
     * 复归落行（enabled=true/reason=NULL/recovered_time）+ 事件流水可查；
     * 探活候选即弃——t_news_item 零新增（复归入 #185 准入与 #184 预算统一门）。
     */
    @Test
    void probeRecoveryReplayAcrossDaysOnRealPg() {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        long fixedNow = System.currentTimeMillis();
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            NewsSourceMapper sourceMapper = session.getMapper(NewsSourceMapper.class);
            NewsItemMapper itemMapper = session.getMapper(NewsItemMapper.class);
            // 三类停用各一行：manual/policy 必须不进探活（真库 WHERE 形状）
            NewsSourceDO autoSource = insertSource(session, PREFIX + "iso-" + runId, false, 3,
                    NewsSourceDO.DISABLED_REASON_AUTO);
            insertSource(session, PREFIX + "manual-" + runId, false, 0, NewsSourceDO.DISABLED_REASON_MANUAL);
            insertSource(session, PREFIX + "policy-" + runId, false, 0, NewsSourceDO.DISABLED_REASON_POLICY);

            clockRef.set(new Date(fixedNow));
            ProbeStubFetcher stubFetcher = new ProbeStubFetcher();
            NewsFetchProperties properties = new NewsFetchProperties();
            NewsFetchService fetchService = new NewsFetchService(List.of(stubFetcher), sourceMapper,
                    itemMapper, properties, clockRef::get);
            NewsSourceHealthService healthService = new NewsSourceHealthService(sourceMapper,
                    session.getMapper(NewsSourceHealthEventMapper.class), fetchService, properties,
                    clockRef::get);
            stubFetcher.items = List.of(rawItem(autoSource.getSourceKey(), runId + "-probe"));

            // ── 第 1 日探活：真库查询只命中 auto 行（manual/policy 互不误复活）──
            NewsSourceHealthService.ProbeSweepResult day1 = healthService.probeSweep();
            assertEquals(1, day1.probed(), "真库探活查询只取 disabled_reason=auto 行");
            assertEquals(0, day1.recovered(), "一次有效成功未达两次阈值");
            NewsSourceDO afterDay1 = sourceMapper.selectById(autoSource.getId());
            assertFalse(afterDay1.getEnabled());
            assertEquals(1, afterDay1.getProbeSuccesses(), "真库 streak=1");
            assertNotNull(afterDay1.getProbeTime(), "真库 probe_time 落行");

            // ── 第 2 日（+25h 窗口内）探活：第二次有效完整成功 → 复归落行 ──
            clockRef.set(new Date(fixedNow + 25 * HOUR));
            NewsSourceHealthService.ProbeSweepResult day2 = healthService.probeSweep();
            assertEquals(1, day2.probed(), "跨日后日级节拍允许再探（前次 probe_time 已是昨日）");
            assertEquals(1, day2.recovered(), "两次有效完整成功（≤48h）自动复归（真库）");

            NewsSourceDO recovered = sourceMapper.selectById(autoSource.getId());
            assertTrue(recovered.getEnabled(), "复归：enabled=true");
            assertNull(recovered.getDisabledReason(), "复归：停用原因清空");
            assertEquals(2, recovered.getProbeSuccesses());
            assertNotNull(recovered.getRecoveredTime(), "复归时刻落行（admin 复归记录可查）");
            assertEquals(NewsFetchOutcome.VALID_WITH_CONTENT.code(), recovered.getLastOutcome(),
                    "最近结果=有效有内容");

            // ── 事件流水（真库）：probe_pass + recovered 可查 ──
            NewsSourceHealthEventMapper eventMapper = session.getMapper(NewsSourceHealthEventMapper.class);
            List<NewsSourceHealthEventDO> events = eventMapper.selectList(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(NewsSourceHealthEventDO.class)
                            .eq(NewsSourceHealthEventDO::getSourceId, autoSource.getId())
                            .orderByAsc(NewsSourceHealthEventDO::getId));
            assertEquals(2, events.size(), "停止/复归记录可查：probe_pass + recovered");
            assertEquals(NewsSourceHealthEventDO.TYPE_PROBE_PASS, events.get(0).getEventType());
            assertEquals(NewsFetchOutcome.VALID_WITH_CONTENT.code(), events.get(0).getOutcome());
            assertEquals(NewsSourceHealthEventDO.TYPE_RECOVERED, events.get(1).getEventType());
            assertEquals(NewsFetchOutcome.VALID_WITH_CONTENT.code(), events.get(1).getOutcome());
            assertTrue(events.get(1).getDetail().contains("2"), "复归依据含连续成功计数");

            // ── 复归入统一门：两轮探活共 2 条候选，t_news_item 零新增 ──
            Long probeItems = itemMapper.selectCount(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(NewsItemDO.class)
                            .likeRight(NewsItemDO::getUrl, "https://it-gov.invalid/"));
            assertEquals(0L, probeItems, "探活候选即弃不入库——复归内容只经下一常规轮 #185 准入与 #184 预算");
        }
    }

    /**
     * defer 豁免真库实证：两连败源 defer 一轮 → consecutive_failures 不增不清零、
     * enabled 不变、last_outcome=defer 落行（零计数豁免的库行为）。
     */
    @Test
    void deferExemptionKeepsHysteresisUntouchedOnRealPg() {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        long fixedNow = System.currentTimeMillis();
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            NewsSourceMapper sourceMapper = session.getMapper(NewsSourceMapper.class);
            NewsSourceDO source = insertSource(session, PREFIX + "defer-" + runId, true, 2, null);

            clockRef.set(new Date(fixedNow));
            NewsFetchProperties properties = new NewsFetchProperties();
            NewsSourceHealthService healthService = new NewsSourceHealthService(sourceMapper,
                    session.getMapper(NewsSourceHealthEventMapper.class),
                    new NewsFetchService(List.of(new ProbeStubFetcher()), sourceMapper,
                            session.getMapper(NewsItemMapper.class), properties, clockRef::get),
                    properties, clockRef::get);

            healthService.recordFetchOutcome(source, new NewsFetchService.SourceFetchResult(
                    source, List.of(), NewsFetchOutcome.DEFER, "Crawl-delay 超单次等待上限"));

            NewsSourceDO after = sourceMapper.selectById(source.getId());
            assertEquals(2, after.getConsecutiveFailures(), "defer 不增不清零滞回（真库）");
            assertTrue(after.getEnabled(), "defer 不禁源");
            assertEquals(NewsFetchOutcome.DEFER.code(), after.getLastOutcome(), "defer 结果留痕");
            assertEquals(0L, session.getMapper(NewsSourceHealthEventMapper.class).selectCount(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(NewsSourceHealthEventDO.class)
                            .eq(NewsSourceHealthEventDO::getSourceId, source.getId())),
                    "defer 不落健康事件");
        }
    }

    private static RawNewsItem rawItem(String key, String slug) {
        String url = "https://it-gov.invalid/news/" + slug;
        return new RawNewsItem(url, NewsUrlNormalizer.urlHash(url),
                "it " + slug, null, "en", new Date(), null, key);
    }

    /**
     * 可编程假抓取器（探活走 NewsFetchService 同一条纪律路径，绕开 HTTP 层）
     */
    private static final class ProbeStubFetcher implements NewsSourceFetcher {

        private List<RawNewsItem> items = List.of();

        @Override
        public String supportedStrategy() {
            return "HTML_LIST";
        }

        @Override
        public List<RawNewsItem> fetch(NewsSourceDO source) {
            return items;
        }
    }
}

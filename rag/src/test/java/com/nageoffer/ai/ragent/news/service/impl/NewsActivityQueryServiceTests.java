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

import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchProperties;
import com.nageoffer.ai.ragent.news.fetch.NewsUrlNormalizer;
import com.nageoffer.ai.ragent.news.service.NewsActivityQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校园活动模型读取口测试（#323；fake store 注入先例=NewsDailyDigestServiceTests）：
 * 装配层经本投影读「未来滚动窗口内重叠」的活动实体——进行中（已开始未结束）/
 * 即将来临（未开始）覆盖、过期自动移出、窗口尾可达、无起止的新闻条目不混入、
 * 统一公开资格（published+过发布门）同判据。固定 as-of=2026-10-09、窗口 56 天
 * （[2026-10-09, 2026-12-03] 含端，与抓取面 8 周同口径）。
 */
class NewsActivityQueryServiceTests {

    private static final ZoneId HKT = ZoneId.of("Asia/Hong_Kong");
    private static final LocalDate AS_OF = LocalDate.parse("2026-10-09");
    private static final int WINDOW_DAYS = 56;
    /** 含端窗口尾=asOf+55 历日（KeyDate 栏目同口径：[D, D+N-1] 共 N 个历日） */
    private static final LocalDate WINDOW_END = LocalDate.parse("2026-12-03");
    /** 生成期=2026-10-09 09:00 HKT（日报 08:40 调度口径邻域） */
    private static final Date NOW =
            Date.from(ZonedDateTime.of(2026, 10, 9, 9, 0, 0, 0, HKT).toInstant());

    private FakeDailyDigestNewsItemStore store;
    private NewsActivityQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        store = new FakeDailyDigestNewsItemStore();
        NewsFetchProperties properties = new NewsFetchProperties();
        service = new NewsActivityQueryServiceImpl(store.mapper, properties, () -> NOW);
    }

    /**
     * 行工厂：published+已过门（eligible=生成期前 1h）基准，测试按参数覆写；
     * end=null 即纯新闻条目（activity_end_time 空）
     */
    private void seed(long id, String slug, LocalDate start, LocalDate end, String status,
                      Date eligibleTime, String titleZh) {
        String url = "https://www.polyu.edu.hk/en/events/" + slug;
        store.seed(NewsItemDO.builder()
                .id(id).sourceId(1L).url(url).urlHash(NewsUrlNormalizer.urlHash(url))
                .titleEn("PolyU Event " + slug).titleZh(titleZh).langRaw("en")
                .category("event")
                .publishTime(atHktStartOfDay(start))
                .activityEndTime(end == null ? null : atHktEndOfDay(end))
                .fetchTime(new Date(NOW.getTime() - 3600_000L))
                .status(status).heat(0)
                .eligibleTime(eligibleTime)
                .build());
    }

    private void seedPublished(long id, String slug, LocalDate start, LocalDate end) {
        seed(id, slug, start, end, "published", new Date(NOW.getTime() - 3600_000L), null);
    }

    private static Date atHktStartOfDay(LocalDate date) {
        return Date.from(date.atStartOfDay(HKT).toInstant());
    }

    private static Date atHktEndOfDay(LocalDate date) {
        return Date.from(date.atTime(23, 59, 59).atZone(HKT).toInstant());
    }

    private List<NewsActivityQueryService.CampusActivity> query() {
        return service.campusActivities(AS_OF, WINDOW_DAYS);
    }

    @Test
    void ongoingActivityStartedBeforeAsOfIsIncludedAndSortedFirst() {
        // 32nd Congregation 形状：10-01 开始、11-21 结束（真实端点实证的跨月活动形态）
        seedPublished(1L, "congregation", LocalDate.parse("2026-10-01"),
                LocalDate.parse("2026-11-21"));
        seedPublished(2L, "infoday", LocalDate.parse("2026-10-10"),
                LocalDate.parse("2026-10-10"));

        List<NewsActivityQueryService.CampusActivity> activities = query();

        assertEquals(2, activities.size());
        NewsActivityQueryService.CampusActivity ongoing = activities.get(0);
        assertEquals(LocalDate.parse("2026-10-01"), ongoing.startDate(), "进行中活动开始日早，升序在前");
        assertEquals(LocalDate.parse("2026-11-21"), ongoing.endDate());
        assertEquals(LocalDate.parse("2026-10-10"), activities.get(1).startDate());
    }

    @Test
    void windowTailIsReachable() {
        // 窗口尾（12-03，恰含端）开始的活动可达——扩窗到窗口尾部的读取面证据；
        // 窗口外（12-05 开始，首出窗日=12-04）不入选
        seedPublished(1L, "tail", WINDOW_END, WINDOW_END);
        seedPublished(2L, "beyond", LocalDate.parse("2026-12-05"),
                LocalDate.parse("2026-12-06"));

        List<NewsActivityQueryService.CampusActivity> activities = query();

        assertEquals(1, activities.size());
        assertEquals(WINDOW_END, activities.get(0).startDate());
    }

    @Test
    void expiredActivityEndingBeforeAsOfIsExcluded() {
        // 过期活动自动从版面消失（#317 用户故事 4）：结束日早于 as-of → 窗外
        seedPublished(1L, "past", LocalDate.parse("2026-09-01"),
                LocalDate.parse("2026-10-08"));

        assertTrue(query().isEmpty());
    }

    @Test
    void newsItemWithoutActivityEndNeverLeaksIn() {
        // 无明确起止的新闻条目（activity_end_time=null）不构成活动实体——即使发布在未来
        seedPublished(1L, "plain-news", LocalDate.parse("2026-11-01"), null);

        assertTrue(query().isEmpty(), "活动实体=起止成对（activity_end_time 非空），纯资讯流不混入");
    }

    @Test
    void onlyUnifiedPublicEligibilityRowsVisible() {
        // pending（未富化）/expired（超龄）不可见——与日报候选同「不抢读未完成行」纪律
        seed(1L, "pending-one", LocalDate.parse("2026-10-20"),
                LocalDate.parse("2026-10-21"), "pending",
                new Date(NOW.getTime() - 3600_000L), null);
        seed(2L, "expired-one", LocalDate.parse("2026-10-20"),
                LocalDate.parse("2026-10-21"), "expired",
                new Date(NOW.getTime() - 3600_000L), null);
        seedPublished(3L, "published-one", LocalDate.parse("2026-10-20"),
                LocalDate.parse("2026-10-21"));

        List<NewsActivityQueryService.CampusActivity> activities = query();

        assertEquals(1, activities.size());
        assertEquals("PolyU Event published-one", activities.get(0).titleEn());
    }

    @Test
    void rowStillWithinPublishGateIsInvisible() {
        // 统一公开资格：eligible_time=生成期（未过 180s 门）→ 不可见
        seed(1L, "gated", LocalDate.parse("2026-10-20"), LocalDate.parse("2026-10-21"),
                "published", NOW, null);

        assertTrue(query().isEmpty());
    }

    @Test
    void modelCarriesTitleDatesAndLinkFieldsComplete() {
        // 票面字段合同：起止日期/标题/链接完整（含中文标题透出与溯源 id）
        seed(7L, "forum", LocalDate.parse("2026-12-02"), LocalDate.parse("2026-12-03"),
                "published", new Date(NOW.getTime() - 3600_000L), "前沿科技论坛");

        List<NewsActivityQueryService.CampusActivity> activities = query();

        assertEquals(1, activities.size());
        NewsActivityQueryService.CampusActivity activity = activities.get(0);
        assertEquals(7L, activity.itemId());
        assertEquals("前沿科技论坛", activity.titleZh());
        assertEquals("PolyU Event forum", activity.titleEn());
        assertTrue(activity.url().endsWith("/forum"), "永久外链（卡片外链语义）");
        assertEquals(LocalDate.parse("2026-12-02"), activity.startDate());
        assertEquals(LocalDate.parse("2026-12-03"), activity.endDate());
    }

    @Test
    void sameStartDaySortedByItemId() {
        seedPublished(2L, "b-second", LocalDate.parse("2026-11-01"),
                LocalDate.parse("2026-11-02"));
        seedPublished(1L, "a-first", LocalDate.parse("2026-11-01"),
                LocalDate.parse("2026-11-05"));

        List<NewsActivityQueryService.CampusActivity> activities = query();

        assertEquals(List.of(1L, 2L), activities.stream()
                        .map(NewsActivityQueryService.CampusActivity::itemId).toList(),
                "同开始日按 itemId 升序（确定性）");
    }

    @Test
    void emptyWindowYieldsEmptyList() {
        assertTrue(query().isEmpty(), "窗口零条目=空列表（读取面不渲染空壳）");
    }
}

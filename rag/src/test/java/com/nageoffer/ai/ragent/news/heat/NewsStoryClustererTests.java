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

import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件聚类器测试（#187 词面合同 E1–E6，真实样本硬门见
 * {@link NewsClusterHardGateTests}——本类覆盖六条件的合成边界与标签窗口语义）。
 * 纯内存逻辑，不触库不触 Spring。
 */
class NewsStoryClustererTests {

    private static final long HOUR = 3600L * 1000;
    private static final Date NOW = new Date(1757548800000L);   // 固定时钟（时间旅行先例）

    private final NewsStoryClusterer clusterer = new NewsStoryClusterer();

    /**
     * 同事件双源正例形状：同分类+共享主题+同锚定日+有效摘要+词面达标 → 合并
     */
    private NewsStoryItem sameEventItem(long id, long sourceId, long ageHours, String summary) {
        return new NewsStoryItem(id, "理大公布最新研究进展", "PolyU announces research progress",
                "research", sourceId, new Date(NOW.getTime() - ageHours * HOUR), Set.of(9L), 0,
                summary, null);
    }

    private String summary(String text) {
        return text;
    }

    @Test
    void mergesSameEventAcrossSourcesOnSixConditions() {
        NewsStoryItem a = sameEventItem(1, 11L, 1,
                summary("理大团队于10月10日公布钙钛矿太阳能电池稳定性突破，效率提升显著。"));
        NewsStoryItem b = sameEventItem(2, 22L, 2,
                summary("香港理工大学10月10日发布钙钛矿电池稳定性研究成果，团队称效率明显提高。"));

        List<NewsStoryCluster> clusters = clusterer.cluster(List.of(a, b), true);

        assertEquals(1, clusters.size());
        assertEquals(2, clusters.get(0).members().size());
        assertEquals(2, clusters.get(0).distinctSourceCount());
        assertEquals(b, clusters.get(0).representative(), "代表=最早发布成员（b 为 2h 前首报）");
    }

    @Test
    void doesNotMergeWithoutSharedTopic() {
        NewsStoryItem a = sameEventItem(1, 11L, 1, summary("理大于10月10日公布研究突破。"));
        NewsStoryItem b = new NewsStoryItem(2L, null, "PolyU team cracks solar cell stability",
                "research", 22L, new Date(NOW.getTime() - 2 * HOUR), Set.of(8L), 0,
                "理大于10月10日公布研究突破。", null);

        assertEquals(2, clusterer.cluster(List.of(a, b), true).size());
    }

    @Test
    void doesNotMergeAcrossCategories() {
        NewsStoryItem a = sameEventItem(1, 11L, 1, summary("理大于10月10日公布研究突破。"));
        NewsStoryItem b = new NewsStoryItem(2L, null, "PolyU team cracks solar cell stability",
                "campus", 22L, new Date(NOW.getTime() - 2 * HOUR), Set.of(9L), 0,
                "理大于10月10日公布研究突破。", null);

        assertEquals(2, clusterer.cluster(List.of(a, b), true).size());
    }

    @Test
    void doesNotMergeWhenEitherSideLacksValidSummary() {
        // E1 门：一侧无摘要（新源上线首日词面）不猜测合并——即使其余五条件全满足
        NewsStoryItem enriched = sameEventItem(1, 11L, 1, summary("理大团队于10月10日公布钙钛矿稳定性突破。"));
        NewsStoryItem notEnriched = new NewsStoryItem(2L, null, "PolyU team cracks perovskite stability",
                "research", 22L, new Date(NOW.getTime() - 2 * HOUR), Set.of(9L), 0, null, null);

        assertEquals(2, clusterer.cluster(List.of(enriched, notEnriched), true).size());
    }

    @Test
    void doesNotMergeWithDifferentAnchorDates() {
        // E4 门：型号/日期差异负例形状——同系列不同场次（类3）锚定日必不同
        NewsStoryItem regional = sameEventItem(1, 11L, 1, summary("国际未来挑战赛香港区决赛于8月24日决出四强。"));
        NewsStoryItem qianhai = sameEventItem(2, 22L, 2, summary("国际未来挑战赛前海区域决赛于8月20日决出四强，晋级总决赛。"));

        assertEquals(2, clusterer.cluster(List.of(regional, qianhai), true).size());
    }

    @Test
    void doesNotMergeWithoutAnyAnchorDate() {
        // E4 门：模板通知词面（无具体日期）不猜测合并——CLU-026/027 语义
        NewsStoryItem a = sameEventItem(1, 11L, 1, summary("香港理工大学公布学年加退选安排，具体时间另行通知。"));
        NewsStoryItem b = sameEventItem(2, 22L, 2, summary("香港理工大学提醒学生加退选安排已公布，请参阅官方通知。"));

        assertEquals(2, clusterer.cluster(List.of(a, b), true).size());
    }

    @Test
    void doesNotMergeRoundupEntriesWithFourOrMoreDates() {
        // E5 门：多事件日程/综述条目（≥4 个日期词）不发起边——CLU-037/039 语义
        NewsStoryItem program = sameEventItem(1, 11L, 1, summary(
                "文化节于10月1日开幕，10月2日举办论坛，10月6日及10月7日举行工作坊，欢迎参与。"));
        NewsStoryItem single = sameEventItem(2, 22L, 2, summary(
                "理大于10月1日举行文化节开幕活动，展出多项文化遗产内容。"));

        assertEquals(2, clusterer.cluster(List.of(program, single), true).size());
        assertEquals(4, NewsStoryClusterer.distinctDateTokens(
                "文化节于10月1日开幕，10月2日举办论坛，10月6日及10月7日举行工作坊。"));
        assertEquals(1, NewsStoryClusterer.distinctDateTokens("活动于10月1日举行。"));
    }

    @Test
    void doesNotMergeBelowSummaryJaccardFloor() {
        // E6 门：词面下限 0.10 兜底（非标题 0.5——不把标题阈值搬长摘要）
        // 两摘要仅共享锚定日 token（10月10日→拉丁 token "10"），词面距离拉满
        NewsStoryItem a = sameEventItem(1, 11L, 1, summary("理大团队于10月10日发布钙钛矿太阳能电池稳定性突破成果。"));
        NewsStoryItem b = sameEventItem(2, 22L, 2, summary("学校社团于10月10日在社区举办文化遗产志愿导览活动。"));

        assertEquals(2, clusterer.cluster(List.of(a, b), true).size());
    }

    @Test
    void degradeSwitchDisablesMerge() {
        NewsStoryItem a = sameEventItem(1, 11L, 1, summary("理大团队于10月10日公布钙钛矿稳定性突破。"));
        NewsStoryItem b = sameEventItem(2, 22L, 2, summary("香港理工大学10月10日发布同一研究成果。"));

        List<NewsStoryCluster> clusters = clusterer.cluster(List.of(a, b), false);

        assertEquals(2, clusters.size());
        assertEquals(1, clusters.get(0).distinctSourceCount());
    }

    @Test
    void anchorDatePrefersTitleThenSummaryAndIsDeterministic() {
        NewsStoryItem titleDate = new NewsStoryItem(1L, "资讯日将于10月10日举行", null, "event",
                1L, new Date(), Set.of(9L), 0, "活动另有9月1日报名开放安排。", null);
        NewsStoryItem summaryDate = new NewsStoryItem(2L, "本科招生资讯日", null, "event",
                2L, new Date(), Set.of(9L), 0, "活动将于10月10日举行，9月1日起报名。", null);
        NewsStoryItem none = new NewsStoryItem(3L, "标题与摘要均无日期", null, "event",
                3L, new Date(), Set.of(9L), 0, "本文不含任何具体日期词。", null);
        assertEquals("10月10日", NewsStoryClusterer.anchorDate(titleDate), "标题日期优先");
        assertEquals("10月10日", NewsStoryClusterer.anchorDate(summaryDate), "标题无日期回落摘要");
        assertNull(NewsStoryClusterer.anchorDate(none), "无日期词面锚定日为 null（不猜测）");
    }

    @Test
    void summaryTokensTokenizeLatinAndCjkBigrams() {
        Set<String> tokensA = NewsStoryClusterer.summaryTokens(new NewsStoryItem(1L, null, null,
                "research", 11L, new Date(), Set.of(), 0,
                "理大团队破解钙钛矿太阳能电池稳定性难题", null));
        Set<String> tokensB = NewsStoryClusterer.summaryTokens(new NewsStoryItem(2L, null, null,
                "research", 22L, new Date(), Set.of(), 0,
                "理大团队破解钙钛矿太阳能电池稳定性", null));
        Set<String> tokensC = NewsStoryClusterer.summaryTokens(new NewsStoryItem(3L, null, null,
                "campus", 33L, new Date(), Set.of(), 0,
                "学生会文化遗产节招募志愿者", null));
        assertTrue(NewsStoryClusterer.jaccard(tokensA, tokensB) >= 0.5);
        assertTrue(NewsStoryClusterer.jaccard(tokensA, tokensC) < 0.1);
    }

    @Test
    void freshTagForFirstReportWithinSixHours() {
        NewsStoryCluster single = new NewsStoryCluster(List.of(
                new NewsStoryItem(1L, null, "Brand new announcement", "campus", 11L,
                        new Date(NOW.getTime() - 3 * HOUR), Set.of(), 0, "活动于10月10日举行。", null)));

        assertEquals(List.of("fresh"), clusterer.tags(single, NOW));
    }

    @Test
    void boomTagForThreeSourcesActiveInWindow() {
        NewsStoryCluster story = new NewsStoryCluster(List.of(
                item(1, "PolyU wins national research grant", 11L,
                        new Date(NOW.getTime() - 5 * HOUR), Set.of(9L)),
                item(2, "PolyU wins national research grant today", 22L,
                        new Date(NOW.getTime() - 2 * HOUR), Set.of(9L)),
                item(3, "PolyU wins national research grant again", 33L,
                        new Date(NOW.getTime() - HOUR), Set.of(9L))));

        List<String> tags = clusterer.tags(story, NOW);

        assertTrue(tags.contains("boom"));
        assertTrue(tags.contains("fresh"));
    }

    @Test
    void riseTagForGrowingCoverageWithoutBoom() {
        NewsStoryCluster story = new NewsStoryCluster(List.of(
                item(1, "Alumni week opens on campus", 11L,
                        new Date(NOW.getTime() - 3L * 24 * HOUR), Set.of(9L)),
                item(2, "Alumni week opens on campus Monday", 22L,
                        new Date(NOW.getTime() - 2 * HOUR), Set.of(9L))));

        assertEquals(List.of("rise"), clusterer.tags(story, NOW));
    }

    @Test
    void noTagsForStoryOutsideWindow() {
        NewsStoryCluster story = new NewsStoryCluster(List.of(
                item(1, "Old story one", 11L, new Date(NOW.getTime() - 5L * 24 * HOUR), Set.of(9L)),
                item(2, "Old story one too", 22L, new Date(NOW.getTime() - 4L * 24 * HOUR), Set.of(9L))));

        assertTrue(clusterer.tags(story, NOW).isEmpty());
    }

    @Test
    void clusterTimeAnchorsSpanMembers() {
        Date earliest = new Date(NOW.getTime() - 10 * HOUR);
        Date latest = new Date(NOW.getTime() - HOUR);
        NewsStoryCluster story = new NewsStoryCluster(List.of(
                item(2, "late", 22L, latest, Set.of()),
                item(1, "early", 11L, earliest, Set.of())));

        assertEquals(earliest, story.earliestPublish());
        assertEquals(latest, story.latestPublish());
        assertEquals("early", story.representative().titleEn());
        assertNotEquals(story.representative().id(), story.members().get(0).id());
    }

    private NewsStoryItem item(long id, String titleEn, long sourceId, Date publishTime, Set<Long> topics) {
        return new NewsStoryItem(id, null, titleEn, "campus", sourceId, publishTime, topics, 0, null, null);
    }
}

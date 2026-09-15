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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 故事线聚类器测试：合并三条件（共享主题+同主分类+
 * 标题 Jaccard ≥0.5）、降级开关、爆/新/发酵中标签的 6h 窗口语义。
 * 纯内存逻辑，不触库不触 Spring。
 */
class NewsStoryClustererTests {

    private static final long HOUR = 3600L * 1000;
    private static final Date NOW = new Date(1757548800000L);   // 固定时钟（时间旅行先例）

    private final NewsStoryClusterer clusterer = new NewsStoryClusterer();

    private NewsStoryItem item(long id, String titleEn, String category, long sourceId,
                               Date publishTime, Set<Long> topics) {
        return new NewsStoryItem(id, null, titleEn, category, sourceId, publishTime, topics, 0);
    }

    private NewsStoryItem perovskite(long id, long sourceId, Date publishTime) {
        return item(id, "PolyU team cracks perovskite solar cell stability problem",
                "research", sourceId, publishTime, Set.of(9L));
    }

    @Test
    void mergesSameStoryAcrossSourcesOnThreeConditions() {
        NewsStoryItem a = perovskite(1, 11L, new Date(NOW.getTime() - HOUR));
        NewsStoryItem b = item(2, "PolyU team cracks perovskite solar cell stability",
                "research", 22L, new Date(NOW.getTime() - 2 * HOUR), Set.of(9L));

        List<NewsStoryCluster> clusters = clusterer.cluster(List.of(a, b), true);

        assertEquals(1, clusters.size());
        assertEquals(2, clusters.get(0).members().size());
        assertEquals(2, clusters.get(0).distinctSourceCount());
        assertEquals(b, clusters.get(0).representative(), "代表=最早发布成员（b 为 2h 前首报）");
    }

    @Test
    void doesNotMergeWithoutSharedTopic() {
        NewsStoryItem a = perovskite(1, 11L, new Date(NOW.getTime() - HOUR));
        NewsStoryItem b = item(2, "PolyU team cracks perovskite solar cell stability",
                "research", 22L, new Date(NOW.getTime() - 2 * HOUR), Set.of(8L));

        List<NewsStoryCluster> clusters = clusterer.cluster(List.of(a, b), true);

        assertEquals(2, clusters.size());
    }

    @Test
    void doesNotMergeAcrossCategories() {
        NewsStoryItem a = perovskite(1, 11L, new Date(NOW.getTime() - HOUR));
        NewsStoryItem b = item(2, "PolyU team cracks perovskite solar cell stability",
                "campus", 22L, new Date(NOW.getTime() - 2 * HOUR), Set.of(9L));

        assertEquals(2, clusterer.cluster(List.of(a, b), true).size());
    }

    @Test
    void doesNotMergeBelowJaccardThreshold() {
        NewsStoryItem a = perovskite(1, 11L, new Date(NOW.getTime() - HOUR));
        NewsStoryItem b = item(2, "Students volunteer at district heritage festival",
                "research", 22L, new Date(NOW.getTime() - 2 * HOUR), Set.of(9L));

        assertEquals(2, clusterer.cluster(List.of(a, b), true).size());
    }

    @Test
    void degradeSwitchDisablesMerge() {
        NewsStoryItem a = perovskite(1, 11L, new Date(NOW.getTime() - HOUR));
        NewsStoryItem b = item(2, "PolyU team cracks perovskite solar cell stability",
                "research", 22L, new Date(NOW.getTime() - 2 * HOUR), Set.of(9L));

        List<NewsStoryCluster> clusters = clusterer.cluster(List.of(a, b), false);

        assertEquals(2, clusters.size());
        assertEquals(1, clusters.get(0).distinctSourceCount());
    }

    @Test
    void chineseTitlesTokenizeIntoComparableSets() {
        // 汉字二元组分词：同题异后缀仍高重叠；异题低重叠
        Set<String> tokensA = NewsStoryClusterer.titleTokens(
                new NewsStoryItem(1L, "理大团队破解钙钛矿太阳能电池稳定性难题", null, "research", 11L,
                        new Date(), Set.of(), 0));
        Set<String> tokensB = NewsStoryClusterer.titleTokens(
                new NewsStoryItem(2L, "理大团队破解钙钛矿太阳能电池稳定性", null, "research", 22L,
                        new Date(), Set.of(), 0));
        Set<String> tokensC = NewsStoryClusterer.titleTokens(
                new NewsStoryItem(3L, "学生会文化遗产节招募志愿者", null, "campus", 33L,
                        new Date(), Set.of(), 0));
        assertTrue(NewsStoryClusterer.jaccard(tokensA, tokensB) >= 0.5);
        assertTrue(NewsStoryClusterer.jaccard(tokensA, tokensC) < 0.5);
        NewsStoryItem a = new NewsStoryItem(1L, "理大团队破解钙钛矿太阳能电池稳定性难题", null,
                "research", 11L, new Date(NOW.getTime() - HOUR), Set.of(9L), 0);
        NewsStoryItem b = new NewsStoryItem(2L, "理大团队破解钙钛矿太阳能电池稳定性", null,
                "research", 22L, new Date(NOW.getTime() - 2 * HOUR), Set.of(9L), 0);
        assertEquals(1, clusterer.cluster(List.of(a, b), true).size());
    }

    @Test
    void freshTagForFirstReportWithinSixHours() {
        NewsStoryCluster single = new NewsStoryCluster(List.of(
                item(1, "Brand new announcement", "campus", 11L, new Date(NOW.getTime() - 3 * HOUR), Set.of())));

        assertEquals(List.of("fresh"), clusterer.tags(single, NOW));
    }

    @Test
    void boomTagForThreeSourcesActiveInWindow() {
        NewsStoryCluster story = new NewsStoryCluster(List.of(
                item(1, "PolyU wins national research grant", "research", 11L,
                        new Date(NOW.getTime() - 5 * HOUR), Set.of(9L)),
                item(2, "PolyU wins national research grant today", "research", 22L,
                        new Date(NOW.getTime() - 2 * HOUR), Set.of(9L)),
                item(3, "PolyU wins national research grant again", "research", 33L,
                        new Date(NOW.getTime() - HOUR), Set.of(9L))));

        List<String> tags = clusterer.tags(story, NOW);

        assertTrue(tags.contains("boom"));
        assertTrue(tags.contains("fresh"));
    }

    @Test
    void riseTagForGrowingCoverageWithoutBoom() {
        NewsStoryCluster story = new NewsStoryCluster(List.of(
                item(1, "Alumni week opens on campus", "campus", 11L,
                        new Date(NOW.getTime() - 3L * 24 * HOUR), Set.of(9L)),
                item(2, "Alumni week opens on campus Monday", "campus", 22L,
                        new Date(NOW.getTime() - 2 * HOUR), Set.of(9L))));

        assertEquals(List.of("rise"), clusterer.tags(story, NOW));
    }

    @Test
    void noTagsForStoryOutsideWindow() {
        NewsStoryCluster story = new NewsStoryCluster(List.of(
                item(1, "Old story one", "campus", 11L, new Date(NOW.getTime() - 5L * 24 * HOUR), Set.of(9L)),
                item(2, "Old story one too", "campus", 22L, new Date(NOW.getTime() - 4L * 24 * HOUR), Set.of(9L))));

        assertTrue(clusterer.tags(story, NOW).isEmpty());
    }

    @Test
    void clusterTimeAnchorsSpanMembers() {
        Date earliest = new Date(NOW.getTime() - 10 * HOUR);
        Date latest = new Date(NOW.getTime() - HOUR);
        NewsStoryCluster story = new NewsStoryCluster(List.of(
                item(2, "late", "campus", 22L, latest, Set.of()),
                item(1, "early", "campus", 11L, earliest, Set.of())));

        assertEquals(earliest, story.earliestPublish());
        assertEquals(latest, story.latestPublish());
        assertEquals("early", story.representative().titleEn());
        assertNotEquals(story.representative().id(), story.members().get(0).id());
    }
}

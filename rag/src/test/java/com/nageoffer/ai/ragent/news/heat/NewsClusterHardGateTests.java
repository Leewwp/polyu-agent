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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 聚簇真实样本硬门（#187 验收：判据=research/cluster-samples 样本账本 48 组）
 *
 * <p>fixture=本地库真实词面 33 行（fixtures/news/cluster-samples-187.json，出处与
 * 溯源见其 provenance 字段——本地 t_news_item 真实标题/摘要/分类/主题，样本账本
 * 2026-09-29 经维护者抽验修正后成立）。断言口径（票面）：
 * <ul>
 * <li>类1 正例（同事件异措辞）必须合并——全部单例不算通过；</li>
 * <li>类2/类3 明确不同事件负例零误合并（标题 Jaccard=1.0 的 CLU-026 极端负例在内）；</li>
 * <li>类4 歧义保守分开；类6 链式终对不合并（真边保留：CLU-046 桥接场景 119~271
 *     仍必须合并——闭包不被假边污染、也不牺牲真边）；</li>
 * <li>类5 摘要缺失（公开对双侧无中文摘要）不猜测合并——E1 门；同对补上有效摘要
 *     且词面/锚定日一致后门开放（类1 公开对「富化后兑现」的合同终态语义）；</li>
 * <li>fallback 摘要（标题派生零调用回退）不构成合并证据——用它合并=变相标题猜测；</li>
 * <li>误合并/漏合并分报：两类计数分开输出与断言（明确负例误合并=0、正例漏合并=0）。</li>
 * </ul>
 */
class NewsClusterHardGateTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonNode fixture;
    private static List<NewsStoryItem> realItems;
    private static Map<Long, String> sourceKeyById;
    private static Map<Long, Set<Long>> topicsByDbId;

    @BeforeAll
    static void loadFixture() throws Exception {
        try (InputStream in = NewsClusterHardGateTests.class
                .getResourceAsStream("/fixtures/news/cluster-samples-187.json")) {
            fixture = MAPPER.readTree(in);
        }
        Map<String, Long> sourceIdByKey = new HashMap<>();
        Map<String, Long> topicIdBySlug = new HashMap<>();
        realItems = new ArrayList<>();
        sourceKeyById = new LinkedHashMap<>();
        topicsByDbId = new HashMap<>();
        for (JsonNode row : fixture.get("rows")) {
            String sourceKey = row.get("source_key").asText();
            long sourceId = sourceIdByKey.computeIfAbsent(sourceKey, k -> (long) (sourceIdByKey.size() + 1));
            sourceKeyById.put(sourceId, sourceKey);
            Set<Long> topics = new HashSet<>();
            for (JsonNode slug : row.get("topics")) {
                topics.add(topicIdBySlug.computeIfAbsent(slug.asText(), k -> (long) (topicIdBySlug.size() + 1)));
            }
            LocalDate publishDate = LocalDate.parse(row.get("publish_date").asText());
            String summarySource = row.hasNonNull("summary_source") ? row.get("summary_source").asText() : null;
            long dbId = row.get("db_id").asLong();
            topicsByDbId.put(dbId, topics);
            realItems.add(new NewsStoryItem(dbId, row.get("title_zh").asText(),
                    row.hasNonNull("title_en") ? row.get("title_en").asText() : null,
                    row.get("category").asText(), sourceId,
                    Date.from(publishDate.atStartOfDay(ZoneOffset.UTC).toInstant()),
                    topics, 0, row.hasNonNull("summary_zh") ? row.get("summary_zh").asText() : null,
                    summarySource));
        }
    }

    /**
     * 同簇判定：聚类结果里两 id 是否落入同一簇
     */
    private static Map<Long, Integer> clusterRootByItem(List<NewsStoryCluster> clusters) {
        Map<Long, Integer> rootByItem = new HashMap<>();
        for (int i = 0; i < clusters.size(); i++) {
            for (NewsStoryItem member : clusters.get(i).members()) {
                rootByItem.put(member.id(), i);
            }
        }
        return rootByItem;
    }

    /**
     * 六类硬门主断言：23 组本地真实词面对（类1-4+类6）逐对判定 + 误合并/漏合并分报
     */
    @Test
    void sixCategoryRealSampleHardGate() {
        List<NewsStoryCluster> clusters = new NewsStoryClusterer().cluster(realItems, true);
        Map<Long, Integer> rootByItem = clusterRootByItem(clusters);
        long nonSingleton = clusters.stream().filter(c -> c.members().size() > 1).count();
        assertTrue(nonSingleton >= 4, "正例必须形成合并——全部单例不算通过（实际非单例簇 " + nonSingleton + "）");

        int falseMerges = 0;
        int missedMerges = 0;
        List<String> failures = new ArrayList<>();
        for (JsonNode pair : fixture.get("local_pairs")) {
            String sample = pair.get("sample").asText();
            long a = pair.get("a").asLong();
            long b = pair.get("b").asLong();
            boolean merged = rootByItem.get(a).intValue() == rootByItem.get(b).intValue();
            boolean expectMerge = "merge".equals(pair.get("expect").asText());
            int klass = pair.get("klass").asInt();
            if (expectMerge && !merged) {
                missedMerges++;
                failures.add(sample + "（类" + klass + "）漏合并");
            } else if (!expectMerge && merged) {
                falseMerges++;
                failures.add(sample + "（类" + klass + "）误合并");
            }
        }
        assertTrue(failures.isEmpty(),
                "真实样本硬门失败：" + failures + "（误合并 " + falseMerges + " / 漏合并 " + missedMerges + "）");
        assertEquals(0, falseMerges, "明确负例/歧义/链式误合并=0");
        assertEquals(0, missedMerges, "类1 正例漏合并=0");
        // 分报输出（证据包口径：误合并/漏合并分报，本地 23 组判定逐类计数）
        System.out.printf("[news][hard-gate] 本地 23 组真实词面：误合并 0 / 漏合并 0；"
                        + "非单例簇 %d（=类1 正例 4 组），总簇 %d%n",
                nonSingleton, clusters.size());
    }

    /**
     * 类6 链式：桥接项在场时终对不合并，且真边（桥接项与原组）保留——
     * CLU-046 真边=119~271（CLU-001 正例）、假边=271~48、终对=119~48；
     * CLU-047 三场签约 pairwise 皆不同事件（774~46、46~775、774~775 全分开）；
     * CLU-045 e-Bulletin 桥接 294：终对 292~269 分开
     */
    @Test
    void chainSamplesKeepTrueEdgesAndSeparateTerminals() {
        List<NewsStoryCluster> clusters = new NewsStoryClusterer().cluster(realItems, true);
        Map<Long, Integer> rootByItem = clusterRootByItem(clusters);
        assertEquals(rootByItem.get(119L), rootByItem.get(271L), "CLU-046 真边 119~271 必须保留（CLU-001）");
        assertNotEquals(rootByItem.get(119L), rootByItem.get(48L), "CLU-046 终对 119~48 不合并");
        assertNotEquals(rootByItem.get(271L), rootByItem.get(48L), "CLU-046 假边 271~48 不成立");
        assertNotEquals(rootByItem.get(774L), rootByItem.get(775L), "CLU-047 终对 774~775 不合并");
        assertNotEquals(rootByItem.get(46L), rootByItem.get(774L), "CLU-047 假边 46~774 不成立");
        assertNotEquals(rootByItem.get(46L), rootByItem.get(775L), "CLU-047 假边 46~775 不成立");
        assertNotEquals(rootByItem.get(292L), rootByItem.get(269L), "CLU-045 终对 292~269 不合并（桥接 294 在场）");
    }

    /**
     * 类5 门：公开对（真实 feed 标题、双侧无中文摘要）在词面缺失态保守分开——
     * 25 组全部分开；这是账本「富化后兑现合并」前的合同行为（判定语义 §3）。
     * 同形状补上有效摘要+同锚定日+高词面重叠后门开放（正例可合并性证明）
     */
    @Test
    void missingSummaryGateSeparatesPublicPairsUntilEnriched() {
        NewsStoryClusterer clusterer = new NewsStoryClusterer();
        List<NewsStoryItem> publicItems = new ArrayList<>();
        Map<String, Long> sourceIdByKey = new HashMap<>();
        long seq = 1;
        for (JsonNode pair : fixture.get("public_pairs")) {
            for (String side : List.of("a", "b")) {
                String key = pair.get(side + "_source").asText();
                long sourceId = sourceIdByKey.computeIfAbsent(key, k -> (long) (sourceIdByKey.size() + 1));
                publicItems.add(new NewsStoryItem(seq++, pair.get(side + "_title").asText(), null,
                        "other", sourceId, new Date(), Set.of(1L), 0, null, null));
            }
        }
        List<NewsStoryCluster> clusters = clusterer.cluster(publicItems, true);
        assertEquals(publicItems.size(), clusters.size(),
                "词面缺失（无中文摘要）25 组公开对全部保守分开——不猜测合并");

        // 门开放性证明：同一对（真实标题形状不碰）补有效摘要+同锚定日+高词面重叠 → 合并
        NewsStoryItem enrichedA = new NewsStoryItem(101L, "甲源报道标题", null, "event", 1L,
                new Date(), Set.of(1L), 0,
                "香港理工大学于10月10日举行本科招生资讯日，上午10时开始，欢迎公众参与。", null);
        NewsStoryItem enrichedB = new NewsStoryItem(102L, "乙源同事件另一措辞标题", null, "event", 2L,
                new Date(), Set.of(1L), 0,
                "理大10月10日办本科招生资讯日，上午10时起对公众开放，多场活动同期举行。", null);
        assertEquals(1, clusterer.cluster(List.of(enrichedA, enrichedB), true).size(),
                "补上有效摘要且锚定日一致后，正例可合并（类1 公开对的合同终态路径开放）");
    }

    /**
     * fallback 摘要（#185 零调用回退=标题派生）不构成合并证据——CLU-026 语义：
     * 两份受众不同的通知标题全同，fallback 摘要同样由标题派生，用它合并=标题猜测
     */
    @Test
    void fallbackSummaryIsNotMergeEvidence() {
        NewsStoryClusterer clusterer = new NewsStoryClusterer();
        NewsStoryItem a = new NewsStoryItem(1L, "2026/27 学年第一学期加退选安排（含开学前调整）", null,
                "admin", 1L, new Date(), Set.of(1L), 0,
                "原文标题（AI 摘要暂缺）：2026/27 学年第一学期加退选安排", "fallback");
        NewsStoryItem b = new NewsStoryItem(2L, "2026/27 学年第一学期加退选安排（含开学前调整）", null,
                "admin", 1L, new Date(), Set.of(1L), 0,
                "原文标题（AI 摘要暂缺）：2026/27 学年第一学期加退选安排", "fallback");
        assertEquals(2, clusterer.cluster(List.of(a, b), true).size(),
                "双侧 fallback（标题派生）词面高度重叠也不得合并——E1 门对回退摘要同样生效");
    }

    /**
     * 标题阈值不搬长摘要的两个实证方向（账本 §4.4）：
     * CLU-026 标题 Jaccard≈1.0 的极端负例分开；CLU-004 正例标题 Jaccard≈0.31 仍合并
     * （合并判据在摘要词面+锚定日，标题相似度不参与边判定）
     */
    @Test
    void titleSimilarityAloneDoesNotDecideMerging() {
        List<NewsStoryCluster> clusters = new NewsStoryClusterer().cluster(realItems, true);
        Map<Long, Integer> rootByItem = clusterRootByItem(clusters);
        assertNotEquals(rootByItem.get(275L), rootByItem.get(276L),
                "CLU-026：标题几乎全同（Jaccard≈1.0）仍分开——标题判重失效下界");
        assertEquals(rootByItem.get(152L), rootByItem.get(153L),
                "CLU-004：标题词面低重叠（Jaccard≈0.31）的同事件正例仍合并");
    }

    /**
     * 类5 门判定辅助断言：真实 33 行里的公开源语义行（summary 为空的形状）不参与合并——
     * 与公开对门同源的防回归锚（fixture rows 全部有摘要，此断言留给门函数本身）
     */
    @Test
    void validSummaryGateFunctionSemantics() {
        NewsStoryItem missing = new NewsStoryItem(1L, "t", null, "event", 1L, new Date(),
                Set.of(1L), 0, null, null);
        NewsStoryItem fallback = new NewsStoryItem(2L, "t", null, "event", 1L, new Date(),
                Set.of(1L), 0, "原文标题（AI 摘要暂缺）：t", "fallback");
        NewsStoryItem legacy = new NewsStoryItem(3L, "t", null, "event", 1L, new Date(),
                Set.of(1L), 0, "真实富化摘要", null);
        NewsStoryItem llm = new NewsStoryItem(4L, "t", null, "event", 1L, new Date(),
                Set.of(1L), 0, "真实富化摘要", "llm");
        assertTrue(!NewsStoryClusterer.hasValidSummary(missing), "无摘要=无效证据");
        assertTrue(!NewsStoryClusterer.hasValidSummary(fallback), "fallback=无效证据（标题派生）");
        assertTrue(NewsStoryClusterer.hasValidSummary(legacy), "NULL 历史富化行=有效（#185 前口径）");
        assertTrue(NewsStoryClusterer.hasValidSummary(llm), "llm=有效");
    }

    /**
     * 证据包输出：全图边统计（33 行真实词面上的非单例簇成员明细）
     */
    @Test
    void reportNonSingletonClustersForEvidencePack() {
        List<NewsStoryCluster> clusters = new NewsStoryClusterer().cluster(realItems, true);
        String report = clusters.stream()
                .filter(c -> c.members().size() > 1)
                .map(c -> c.members().stream().map(m -> m.id() + "@" + sourceKeyById.get(m.sourceId()))
                        .collect(Collectors.joining(",", "[", "]")))
                .collect(Collectors.joining(" "));
        System.out.println("[news][hard-gate] 非单例簇（=类1 正例 4 组）：" + report);
        assertEquals(4, clusters.stream().filter(c -> c.members().size() > 1).count(),
                "全图恰好 4 个非单例簇（正例 4 组），零额外误并");
    }
}

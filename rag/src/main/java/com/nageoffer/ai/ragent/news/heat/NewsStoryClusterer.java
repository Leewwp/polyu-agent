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

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 故事线启发式聚类器
 *
 * <p>合并判据（三项同时满足才并入同簇）：共享 ≥1 主题 + 同主分类 +
 * 标题 Jaccard ≥ 0.5。主题关联 LLM 管线落库后合并才实际生效——补全前
 * 条目 topicIds 恒空集，全库退化为单条目簇（heat=自身权重+1），属预期行为。
 *
 * <p>降级开关 rag.news.story-merge-enabled=false 时跳过合并：每条目独立成簇、
 * 热度只计自身信源；热点榜前端方法论注脚须随降级同步改稿，
 * 不虚假描述。
 *
 * <p>标签语义（原型榜单说明口径）：爆=短时间密集报道（≥3 源且最新报道在
 * 6h 内）· 新=首报 6 小时内 · 发酵中=信源仍在增加（≥2 源且最新报道在 6h 内）。
 */
@Component
public class NewsStoryClusterer {

    /**
     * 标题 Jaccard 合并阈值
     */
    static final double JACCARD_THRESHOLD = 0.5;

    /**
     * 「新/爆/发酵中」共用的时间窗（小时）
     */
    static final long TAG_WINDOW_HOURS = 6;

    /**
     * 「爆」的覆盖信源数下限
     */
    static final int BOOM_SOURCE_COUNT = 3;

    /**
     * 全量聚类：返回簇列表（输入顺序无关；单条目簇=未合并的独立故事）
     */
    public List<NewsStoryCluster> cluster(List<NewsStoryItem> items, boolean mergeEnabled) {
        List<NewsStoryCluster> singletons = new ArrayList<>();
        for (NewsStoryItem item : items) {
            singletons.add(new NewsStoryCluster(List.of(item)));
        }
        if (!mergeEnabled || items.size() < 2) {
            return singletons;
        }
        int n = items.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        Set<String>[] tokens = new Set[n];
        for (int i = 0; i < n; i++) {
            tokens[i] = titleTokens(items.get(i));
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (sameRoot(parent, i, j)) {
                    continue;
                }
                if (!sharesStorySignal(items.get(i), items.get(j), tokens[i], tokens[j])) {
                    continue;
                }
                union(parent, i, j);
            }
        }
        Map<Integer, List<NewsStoryItem>> roots = new HashMap<>();
        for (int i = 0; i < n; i++) {
            roots.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(items.get(i));
        }
        List<NewsStoryCluster> clusters = new ArrayList<>(roots.values().stream()
                .map(NewsStoryCluster::new).toList());
        clusters.sort((x, y) -> {
            Date xe = x.earliestPublish();
            Date ye = y.earliestPublish();
            if (xe == null && ye == null) {
                return Long.compare(x.representative().id(), y.representative().id());
            }
            if (xe == null) {
                return 1;
            }
            if (ye == null) {
                return -1;
            }
            int byTime = xe.compareTo(ye);
            return byTime != 0 ? byTime
                    : Long.compare(x.representative().id(), y.representative().id());
        });
        return clusters;
    }

    /**
     * 簇标签（原型 RANK tags 同名枚举：boom/fresh/rise；展示序=爆→新→发酵中）
     */
    public List<String> tags(NewsStoryCluster cluster, Date now) {
        List<String> tags = new ArrayList<>(3);
        long windowFloor = now.getTime() - TAG_WINDOW_HOURS * 3600L * 1000L;
        Date latest = cluster.latestPublish();
        boolean activeInWindow = latest != null && latest.getTime() >= windowFloor;
        long sources = cluster.distinctSourceCount();
        boolean boom = sources >= BOOM_SOURCE_COUNT && activeInWindow;
        if (boom) {
            tags.add("boom");
        }
        Date earliest = cluster.earliestPublish();
        if (earliest != null && earliest.getTime() >= windowFloor) {
            tags.add("fresh");
        }
        if (!boom && sources >= 2 && activeInWindow) {
            tags.add("rise");
        }
        return tags;
    }

    /**
     * 标题分词集合：拉丁词（≥2 字符，小写化）+ CJK 字符二元组——双语标题统一进
     * 同一集合，Jaccard 直接可比（sitemap 条目天然双题，单语条目由补译前
     * 只有自己的语言）
     */
    static Set<String> titleTokens(NewsStoryItem item) {
        Set<String> tokens = new HashSet<>();
        collectTokens(item.titleEn(), tokens);
        collectTokens(item.titleZh(), tokens);
        return tokens;
    }

    private static void collectTokens(String text, Set<String> tokens) {
        if (text == null || text.isBlank()) {
            return;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder latin = new StringBuilder();
        List<Character> cjk = new ArrayList<>();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (isCjk(c)) {
                cjk.add(c);
                latin = flushLatin(latin, tokens);
            } else if (Character.isLetterOrDigit(c)) {
                latin.append(c);
            } else {
                latin = flushLatin(latin, tokens);
            }
        }
        flushLatin(latin, tokens);
        for (int i = 1; i < cjk.size(); i++) {
            tokens.add("" + cjk.get(i - 1) + cjk.get(i));
        }
    }

    private static StringBuilder flushLatin(StringBuilder latin, Set<String> tokens) {
        if (latin.length() >= 2) {
            tokens.add(latin.toString());
        }
        latin.setLength(0);
        return latin;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF);
    }

    /**
     * 合并判据三条件：同主分类 + 共享 ≥1 主题 + 标题 Jaccard ≥ 阈值。
     * 双题皆空（无任何可比分词）不合并。长度预筛：min/max < 阈值时 Jaccard
     * 必不达标，直接跳过逐 token 比较。
     */
    private boolean sharesStorySignal(NewsStoryItem a, NewsStoryItem b, Set<String> tokensA, Set<String> tokensB) {
        if (a.category() == null || !a.category().equals(b.category())) {
            return false;
        }
        if (!sharesTopic(a, b)) {
            return false;
        }
        if (tokensA.isEmpty() || tokensB.isEmpty()) {
            return false;
        }
        int min = Math.min(tokensA.size(), tokensB.size());
        int max = Math.max(tokensA.size(), tokensB.size());
        if (min < max * JACCARD_THRESHOLD) {
            return false;
        }
        return jaccard(tokensA, tokensB) >= JACCARD_THRESHOLD;
    }

    private static boolean sharesTopic(NewsStoryItem a, NewsStoryItem b) {
        if (a.topicIds() == null || b.topicIds() == null || a.topicIds().isEmpty() || b.topicIds().isEmpty()) {
            return false;
        }
        for (Long topicId : a.topicIds()) {
            if (topicId != null && b.topicIds().contains(topicId)) {
                return true;
            }
        }
        return false;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Set<String> small = a.size() <= b.size() ? a : b;
        Set<String> large = small == a ? b : a;
        int intersection = 0;
        for (String token : small) {
            if (large.contains(token)) {
                intersection++;
            }
        }
        int union = a.size() + b.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int i, int j) {
        int ri = find(parent, i);
        int rj = find(parent, j);
        if (ri != rj) {
            parent[Math.max(ri, rj)] = Math.min(ri, rj);
        }
    }

    private static boolean sameRoot(int[] parent, int i, int j) {
        return find(parent, i) == find(parent, j);
    }
}

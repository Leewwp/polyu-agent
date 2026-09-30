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

import com.nageoffer.ai.ragent.news.dao.entity.NewsItemStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 事件聚类器（#187 事件合并词面合同——判据=六类真实正负样本
 * research/cluster-samples 样本账本 48 组，本地 23 组以真实词面为硬门）
 *
 * <p><b>合并边判据（六条件同时满足才并簇，E1–E6）</b>——取代 #187 之前的
 * 「标题 Jaccard ≥0.5」边（该阈值只对标题成立，样本账本实证其两个失效方向：
 * CLU-026 受众不同的两份通知标题 Jaccard=1.0 仍须分开；CLU-012 同事件两源
 * 型号命名不同标题词面无交集仍须合并。不把标题 0.5 阈值直接搬长摘要）：
 * <ol>
 * <li><b>E1 有效摘要门</b>：双侧均有有效中文摘要（summary_zh 非空且
 *     summary_source≠fallback；NULL=#185 前历史富化行视同 llm）。摘要缺失
 *     （新源上线首日语义上即无中文摘要）或零调用回退（标题派生=变相标题猜测）
 *     一律不猜测合并（CLU-041 门；类1 公开正例须富化后兑现合并属合同终态）。</li>
 * <li><b>E2 同主分类</b>：分类不一致保守分开（CLU-035：同源两行 event vs
 *     scholarship 不得并簇）。</li>
 * <li><b>E3 共享 ≥1 主题</b>：沿用（弱必要条件，词面门之前先挡跨域词）。</li>
 * <li><b>E4 锚定日一致</b>：双侧锚定日（标题中文里最早出现的「M月D日」，
 *     标题无日期则取摘要）都非空且相等。同事件共享同一具体日期（CLU-001
 *     7月1日 / CLU-003 10月10日）；型号日期差异负例（类3）锚定日必不同
 *     （CLU-025 8月24日 vs 8月20日、CLU-046 假边 7月1日 vs 10月1日）；
 *     无日期词面（CLU-026/027 模板通知）不猜测合并。跨 15 天的日历条目×
 *     预告通稿（CLU-003）因锚定同一活动日仍可合并——时间差不是硬门。</li>
 * <li><b>E5 综述/多事日程守卫</b>：任一侧摘要含 ≥{@value #ROUNDUP_DATE_LIMIT}
 *     个不同日期词（M月D日+裸「D日」）视为多事件日程/综述条目，不发起合并边
 *     （CLU-037 捆绑日历条目 vs 展览通稿保守分开；CLU-039 综述不与单项互并；
 *     CLU-045 e-Bulletin 一稿多事件枢纽不再放大闭包）。</li>
 * <li><b>E6 摘要词面下限</b>：摘要分词（拉丁词+CJK 二元组）Jaccard ≥
 *     {@value #SUMMARY_JACCARD_FLOOR}。0.10 是按账本正例实测校准的下限
 *     （CLU-003 真实正例 J=0.137——门槛必须低于它），<b>刻意不是</b>标题的
 *     0.5：长摘要的正确判据是锚定日+分类+主题，词面只做防随机同日的兜底。</li>
 * </ol>
 *
 * <p><b>闭包语义</b>：沿用并查集传递闭包（单 linkage）。类6 链式样本（真边+
 * 假边）靠边谓词本身拒掉假边保护终对（CLU-046/047 经 33 行真实词面全图验证
 * 零误边）——闭包不再额外加互近邻等图形状约束（账本只考终对，最小模型不加面）。
 *
 * <p><b>硬门校准声明</b>：以上六条件在账本 23 组本地真实词面对上 23/23 通过
 * （正例 4 组全合并、负例/歧义/链式 19 组全分开、全图恰好 4 条边=4 组正例，
 * 零误合并零漏合并）；25 组公开对（双侧无中文摘要）按 E1 门保守分开，
 * 其类1 8 组的合并属「富化后兑现」的合同终态（采样方法说明 §3 判定语义）。
 *
 * <p>降级开关 rag.news.story-merge-enabled=false 时跳过合并：每条目独立成簇、
 * 热度只计自身信源；热点榜前端方法论注脚须随降级同步改稿，不虚假描述。
 *
 * <p>标签语义（原型榜单说明口径）：爆=短时间密集报道（≥3 源且最新报道在
 * 6h 内）· 新=首报 6 小时内 · 发酵中=信源仍在增加（≥2 源且最新报道在 6h 内）。
 */
@Component
public class NewsStoryClusterer {

    /**
     * E6 摘要词面 Jaccard 下限（校准自账本正例 CLU-003 实测 J=0.137——
     * 刻意远低于标题阈值 0.5，不把标题阈值搬长摘要）
     */
    static final double SUMMARY_JACCARD_FLOOR = 0.10;

    /**
     * E5 综述/多事日程守卫：摘要内不同日期词达到该数即视为日程/综述条目
     */
    static final int ROUNDUP_DATE_LIMIT = 4;

    /**
     * 「新/爆/发酵中」共用的时间窗（小时）
     */
    static final long TAG_WINDOW_HOURS = 6;

    /**
     * 「爆」的覆盖信源数下限
     */
    static final int BOOM_SOURCE_COUNT = 3;

    /**
     * E4 锚定日词形：「M月D日」（中文标题/摘要里的具体日期词）
     */
    private static final Pattern MONTH_DAY = Pattern.compile("(\\d{1,2})月(\\d{1,2})日");

    /**
     * E5 统计用裸日词形：「D日」（不含其前的数字/月，如「7日」「15日」）
     */
    private static final Pattern BARE_DAY = Pattern.compile("(?<![0-9月])(\\d{1,2})日");

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
            tokens[i] = summaryTokens(items.get(i));
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (sameRoot(parent, i, j)) {
                    continue;
                }
                if (!sameStory(items.get(i), items.get(j), tokens[i], tokens[j])) {
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
     * E1 有效中文摘要：非空且非 fallback（fallback=标题派生的零调用回退，
     * 用它合并等于标题猜测；NULL=#185 前历史富化行，视同 llm）
     */
    static boolean hasValidSummary(NewsStoryItem item) {
        return item.summaryZh() != null && !item.summaryZh().isBlank()
                && !NewsItemStatus.SUMMARY_SOURCE_FALLBACK.equals(item.summarySource());
    }

    /**
     * E4 锚定日：标题中文里出现的「M月D日」取字典序最小者（确定性、与出现
     * 顺序无关），标题无日期词则取摘要里的——都没有返回 null（无日期词面
     * 不猜测合并）。日期词形本身即事件具体日（同活动预告/日历/通稿共享），
     * 与 publish_time 无关（CLU-003 日历条目 10-10 发布、通稿 09-25 发布）
     */
    static String anchorDate(NewsStoryItem item) {
        String title = (item.titleZh() == null ? "" : item.titleZh())
                + " " + (item.titleEn() == null ? "" : item.titleEn());
        String inTitle = earliestMonthDay(title);
        if (inTitle != null) {
            return inTitle;
        }
        return earliestMonthDay(item.summaryZh());
    }

    private static String earliestMonthDay(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String min = null;
        Matcher matcher = MONTH_DAY.matcher(text);
        while (matcher.find()) {
            String token = matcher.group(1) + "月" + matcher.group(2) + "日";
            if (min == null || token.compareTo(min) < 0) {
                min = token;
            }
        }
        return min;
    }

    /**
     * E5 日期词计数：摘要内不同日期词（「M月D日」∪ 裸「D日」）去重计数——
     * ≥{@value #ROUNDUP_DATE_LIMIT} 判为多事件日程/综述条目
     */
    static int distinctDateTokens(String summaryZh) {
        if (summaryZh == null || summaryZh.isEmpty()) {
            return 0;
        }
        Set<String> tokens = new HashSet<>();
        Matcher monthDay = MONTH_DAY.matcher(summaryZh);
        while (monthDay.find()) {
            tokens.add(monthDay.group(1) + "月" + monthDay.group(2) + "日");
        }
        Matcher bareDay = BARE_DAY.matcher(summaryZh);
        while (bareDay.find()) {
            tokens.add(bareDay.group(1) + "日");
        }
        return tokens.size();
    }

    /**
     * E1–E6 六条件合并边（详见类 javadoc；E6 最后算——词面只做兜底）
     */
    private boolean sameStory(NewsStoryItem a, NewsStoryItem b, Set<String> tokensA, Set<String> tokensB) {
        if (a.category() == null || !a.category().equals(b.category())) {
            return false;
        }
        if (!sharesTopic(a, b)) {
            return false;
        }
        if (!hasValidSummary(a) || !hasValidSummary(b)) {
            return false;
        }
        String anchorA = anchorDate(a);
        String anchorB = anchorDate(b);
        if (anchorA == null || anchorB == null || !anchorA.equals(anchorB)) {
            return false;
        }
        if (distinctDateTokens(a.summaryZh()) >= ROUNDUP_DATE_LIMIT
                || distinctDateTokens(b.summaryZh()) >= ROUNDUP_DATE_LIMIT) {
            return false;
        }
        return jaccard(tokensA, tokensB) >= SUMMARY_JACCARD_FLOOR;
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

    /**
     * 摘要分词集合：拉丁词（≥2 字符，小写化）+ CJK 字符二元组——双语摘要统一进
     * 同一集合，Jaccard 直接可比（#175 实证：富化输出中文摘要 100% 覆盖，
     * 聚簇词面用中文摘要字段；跨语种语义合并不在本批）
     */
    static Set<String> summaryTokens(NewsStoryItem item) {
        Set<String> tokens = new HashSet<>();
        collectTokens(item.summaryZh(), tokens);
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

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

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemStatus;
import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsSourceMapper;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchDeferredException;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchOutcome;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchPolicyException;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchProperties;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchStructureException;
import com.nageoffer.ai.ragent.news.fetch.NewsSourceFetcher;
import com.nageoffer.ai.ragent.news.fetch.RawNewsItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 抓取编排服务（#185 改两阶段：逐源取候选 → 全局公平准入；#186 源治理改造）
 *
 * <p>阶段一 {@link #fetch(NewsSourceDO)}：策略分派 → 抓取解析 → 内存 url_hash 去重 →
 * <b>六类结果分类学</b>（{@link NewsFetchOutcome}：有效有内容/有效空或无新增/结构失配/
 * 网络失败/策略禁止/defer）。本方法<b>不写库不抛抓取异常</b>——失败滞回、停用原因三分、
 * 探活记账归 {@link NewsSourceHealthService}（defer 零计数豁免在那里落地）。
 *
 * <p>阶段二 {@link #admitAll(List)}：对整轮所有源的候选做一次全局准入——
 * <ul>
 * <li><b>旧文 48h 归档</b>：原文发布时间早于 now-stale-article-hours（默认 48h）
 * 且在回灌窗口内 → archived 终态入库（不进「今天」、跳过付费富化、不计日准入）；
 * 发布时间 null 一律不入库（lastmod 不冒充首发，解析侧空发布时间已过滤）。</li>
 * <li><b>确定性顺序</b>：每源候选按（发布时间倒序，urlHash 升序）排序——
 * 首灌限量（如 OpenAI 全史 1234 条）截的是最新一头，重启/重跑结果一致
 * （修掉旧实现截断遍历 HashMap 的无序问题）。</li>
 * <li><b>容量硬合同</b>：全站新准入 ≤ admission-daily-site-cap（默认 60，HKT 日切，
 * 含既有源）；每源 ≤ per-source 日上限（默认 50，sourceKey 覆盖表供 #188 启源配
 * 10/20——局部合计 200 不覆盖全站上限）。日计数从库内 fetch_time+status 现推
 * （status&lt;&gt;archived），重启不重置。</li>
 * <li><b>按源公平轮转</b>：源按 sourceKey 升序、每轮每源至多取一条，循环直到
 * 全站余量耗尽或所有源取空——大源（如 arXiv 20 条/日）不能饿死校园源；
 * 未准入候选不落库，下轮重发现（更旧后自然转归档路径）。</li>
 * </ul>
 * <b>复归入统一门（#186）</b>：探活复归只置 enabled=true——探活轮抓到的候选即弃、
 * 不入库；复归源的新内容只经下一常规轮的 admitAll（#185 全站/单源日准入）与
 * 富化预算（#184）进入管线，无任何绕门路径。
 *
 * <p>{@link #expireOverduePending()}：待富化 TTL（默认 48h，从 fetch_time 首次
 * 发现起算）届满的 pending 条目转 expired 终态，退出付费待办；同 URL 重现因
 * url_hash 唯一键天然不重建待办。
 *
 * <p>LLM 摘要/分类/topics 与热度模型归补全与热度模块，本类不碰。
 */
@Slf4j
@Service
public class NewsFetchService {

    /**
     * 资讯管线统一时区（HKT +08:00，「今天」切日=HKT 00:00，沿 NewsFetchJob/NewsLlmBudgetService 先例）
     */
    static final ZoneId HKT_ZONE = ZoneId.of("Asia/Hong_Kong");

    private final Map<String, NewsSourceFetcher> fetchersByStrategy = new HashMap<>();
    private final NewsSourceMapper sourceMapper;
    private final NewsItemMapper itemMapper;
    private final NewsFetchProperties properties;
    private final Supplier<Date> nowSupplier;

    @org.springframework.beans.factory.annotation.Autowired
    public NewsFetchService(List<NewsSourceFetcher> fetchers,
                            NewsSourceMapper sourceMapper,
                            NewsItemMapper itemMapper,
                            NewsFetchProperties properties) {
        this(fetchers, sourceMapper, itemMapper, properties, Date::new);
    }

    /**
     * 全参构造器（测试注入时钟）
     */
    NewsFetchService(List<NewsSourceFetcher> fetchers,
                     NewsSourceMapper sourceMapper,
                     NewsItemMapper itemMapper,
                     NewsFetchProperties properties,
                     Supplier<Date> nowSupplier) {
        fetchers.forEach(fetcher -> fetchersByStrategy.put(fetcher.supportedStrategy(), fetcher));
        this.sourceMapper = sourceMapper;
        this.itemMapper = itemMapper;
        this.properties = properties;
        this.nowSupplier = nowSupplier;
    }

    /**
     * 单源候选批次（阶段一产物；items 已做内存 url_hash 去重，未做窗口/库内判定）
     */
    public record SourceCandidates(NewsSourceDO source, List<RawNewsItem> items) {
    }

    /**
     * 阶段一结果：六类分类学 + 去重后候选（仅有效结果两类携带非空 items）
     *
     * @param source 源行
     * @param items  去重后候选（VALID_WITH_CONTENT 非空；其余为空列表）
     * @param outcome 六类结果（{@link NewsFetchOutcome}）
     * @param detail 判定依据摘要（日志/事件表留痕）
     */
    public record SourceFetchResult(NewsSourceDO source, List<RawNewsItem> items,
                                    NewsFetchOutcome outcome, String detail) {

        /**
         * 是否有效完整成功（第 1/2 类）——候选可进入阶段二准入
         */
        public boolean isValid() {
            return outcome.isValidSuccess();
        }
    }

    /**
     * 整轮准入结果（日志与验收口径计数）
     *
     * @param admitted        新准入 pending 条数（计入全站/单源日上限）
     * @param archivedStale   旧文归档条数（不计日准入）
     * @param skippedExisting 库内已存在 url_hash 跳过条数（幂等重发现）
     * @param siteRemaining   全站当日剩余准入额度
     * @param deferredSources 仍有未准入新鲜候选的源数（被全站/单源上限或批上限截留）
     */
    public record AdmissionResult(int admitted, int archivedStale, int skippedExisting,
                                  int siteRemaining, int deferredSources) {
    }

    /**
     * 阶段一：抓取单源并按六类分类学归类（#186）。不写库、不抛抓取异常（探活与常规轮
     * 共用同一条纪律路径：robots 校验/共享 host 节拍/瞬时重试都在 NewsHttpFetchClient 单点）。
     *
     * <p>分类规则（单点，详见 {@link NewsFetchOutcome}）：
     * <ul>
     * <li>解析产出 ≥1 条（去重后）→ VALID_WITH_CONTENT；</li>
     * <li>零条目且源在 allow-empty 配置 → VALID_EMPTY；零条目未配置 → STRUCTURE_MISMATCH
     *     （解析器层对 RSS/SITEMAP/EVENTS/HTML_LIST 已有 fail-closed 抛出，这里是
     *     「策略器过滤后归零」（如 GNews 条目全无原文链接）的兜底分类）；</li>
     * <li>NewsFetchDeferredException → DEFER（礼貌等待——零计数豁免在健康服务落地）；</li>
     * <li>NewsFetchPolicyException → POLICY_FORBIDDEN（robots/出站守卫拒绝）；</li>
     * <li>NewsFetchStructureException → STRUCTURE_MISMATCH；</li>
     * <li>其余异常（HTTP/IO/未知运行时故障）→ NETWORK_FAILURE。</li>
     * </ul>
     */
    public SourceFetchResult fetch(NewsSourceDO source) {
        try {
            NewsSourceFetcher fetcher = fetchersByStrategy.get(source.getFetchStrategy());
            if (fetcher == null) {
                // 配置错误也计滞回（结构失配类）：3 轮后自动禁源，防止坏配置每轮空转
                throw new NewsFetchStructureException("未知 fetch_strategy: " + source.getFetchStrategy());
            }
            List<RawNewsItem> items = fetcher.fetch(source);
            // 同源同轮内 url_hash 去重（首条优先）；跨源/库内判定归 admitAll
            Map<String, RawNewsItem> deduped = new HashMap<>();
            for (RawNewsItem item : items) {
                deduped.putIfAbsent(item.urlHash(), item);
            }
            List<RawNewsItem> candidates = new ArrayList<>(deduped.values());
            if (!candidates.isEmpty()) {
                log.info("[news] 源 {} 抓取成功：解析 {} 条，去重后候选 {} 条",
                        source.getSourceKey(), items.size(), candidates.size());
                return new SourceFetchResult(source, candidates, NewsFetchOutcome.VALID_WITH_CONTENT,
                        "解析 " + items.size() + " 条，去重后 " + candidates.size() + " 条");
            }
            if (properties.isAllowEmptySource(source.getSourceKey())) {
                log.info("[news] 源 {} 抓取成功（有效空）：解析零条目，allow-empty 源按健康处理",
                        source.getSourceKey());
                return new SourceFetchResult(source, List.of(), NewsFetchOutcome.VALID_EMPTY,
                        "解析有效但零条目（allow-empty 源）");
            }
            log.warn("[news] 源 {} 解析零条目（fail-closed：未配置 allow-empty，模板改版嫌疑）",
                    source.getSourceKey());
            return new SourceFetchResult(source, List.of(), NewsFetchOutcome.STRUCTURE_MISMATCH,
                    "解析零条目（未配置 allow-empty，fail-closed）");
        } catch (NewsFetchDeferredException e) {
            return new SourceFetchResult(source, List.of(), NewsFetchOutcome.DEFER, e.getMessage());
        } catch (NewsFetchPolicyException e) {
            return new SourceFetchResult(source, List.of(), NewsFetchOutcome.POLICY_FORBIDDEN, e.getMessage());
        } catch (NewsFetchStructureException e) {
            return new SourceFetchResult(source, List.of(), NewsFetchOutcome.STRUCTURE_MISMATCH, e.getMessage());
        } catch (Exception e) {
            return new SourceFetchResult(source, List.of(), NewsFetchOutcome.NETWORK_FAILURE, e.getMessage());
        }
    }

    /**
     * 阶段二：整轮全局公平准入（详见类 javadoc）。批间无隐式顺序依赖——
     * 源序与候选序均为确定性排序，输入顺序不影响结果。
     */
    public AdmissionResult admitAll(List<SourceCandidates> batches) {
        Date now = nowSupplier.get();
        long day = 24L * 3600 * 1000;
        Date windowFloor = new Date(now.getTime() - properties.getBackfillDays() * day);
        Date staleFloor = new Date(now.getTime() - properties.effectiveStaleArticleHours() * 3600L * 1000L);
        // 源确定性序：sourceKey 升序（理论非空，防御 null 归尾）；同源多批次合并成
        // 单源单队列（候选分类合并+按去重源迭代——防止同源重复批次在归档段重访同一队列重复入库）
        List<NewsSourceDO> orderedSources = batches.stream()
                .filter(batch -> batch != null && batch.source() != null && batch.source().getId() != null)
                .map(SourceCandidates::source)
                .distinct()
                .sorted(Comparator.comparing(NewsSourceDO::getSourceKey,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        // 候选分类：fresh（进准入）/ stale（归档）/ 丢弃（发布时间 null 或窗口外）
        Map<Long, Deque<RawNewsItem>> freshQueues = new HashMap<>();
        Map<Long, List<RawNewsItem>> staleQueues = new HashMap<>();
        Set<String> candidateHashes = new LinkedHashSet<>();
        for (SourceCandidates batch : batches) {
            if (batch == null || batch.source() == null || batch.source().getId() == null) {
                continue;
            }
            NewsSourceDO source = batch.source();
            Deque<RawNewsItem> fresh = freshQueues.computeIfAbsent(source.getId(), key -> new ArrayDeque<>());
            List<RawNewsItem> stale = staleQueues.computeIfAbsent(source.getId(), key -> new ArrayList<>());
            for (RawNewsItem item : sortedByRecency(batch.items())) {
                if (item.publishTime() == null || item.publishTime().before(windowFloor)) {
                    continue;
                }
                candidateHashes.add(item.urlHash());
                if (item.publishTime().before(staleFloor)) {
                    stale.add(item);
                } else {
                    fresh.add(item);
                }
            }
        }
        if (candidateHashes.isEmpty()) {
            return new AdmissionResult(0, 0, 0, properties.effectiveAdmissionDailySiteCap(), 0);
        }
        Set<String> existing = loadExistingHashes(candidateHashes);
        Map<Long, Long> admittedToday = countAdmittedTodayBySource(now);
        long siteAdmitted = admittedToday.values().stream().mapToLong(Long::longValue).sum();
        int siteCap = properties.effectiveAdmissionDailySiteCap();
        int siteRemaining = (int) Math.max(0, siteCap - siteAdmitted);
        int admitted = 0;
        int archivedStale = 0;
        int skippedExisting = 0;
        int batchCap = properties.getMaxItemsPerSource();
        Map<Long, Integer> insertedThisRound = new HashMap<>();
        // 按源公平轮转：每循环每源至多准入一条，直至全站余量耗尽/全部取空
        while (siteRemaining > 0) {
            boolean progressed = false;
            for (NewsSourceDO source : orderedSources) {
                if (siteRemaining <= 0) {
                    break;
                }
                Deque<RawNewsItem> queue = freshQueues.get(source.getId());
                if (queue.isEmpty()) {
                    continue;
                }
                long alreadyAdmitted = admittedToday.getOrDefault(source.getId(), 0L);
                int sourceCap = properties.effectiveSourceDailyCap(source.getSourceKey());
                int inserted = insertedThisRound.getOrDefault(source.getId(), 0);
                if (alreadyAdmitted >= sourceCap || inserted >= batchCap) {
                    queue.clear();
                    continue;
                }
                RawNewsItem candidate = queue.poll();
                while (candidate != null && existing.contains(candidate.urlHash())) {
                    skippedExisting++;
                    candidate = queue.poll();
                }
                if (candidate == null) {
                    continue;
                }
                itemMapper.insert(toRecord(source, candidate, NewsItemStatus.PENDING, now));
                existing.add(candidate.urlHash());
                admitted++;
                siteRemaining--;
                progressed = true;
                admittedToday.merge(source.getId(), 1L, Long::sum);
                insertedThisRound.put(source.getId(), inserted + 1);
            }
            if (!progressed) {
                break;
            }
        }
        // 旧文归档：不计日准入，确定性顺序，与 fresh 共享单源单轮批上限
        for (NewsSourceDO source : orderedSources) {
            int inserted = insertedThisRound.getOrDefault(source.getId(), 0);
            for (RawNewsItem stale : staleQueues.getOrDefault(source.getId(), List.of())) {
                if (existing.contains(stale.urlHash())) {
                    skippedExisting++;
                    continue;
                }
                if (inserted >= batchCap) {
                    break;
                }
                itemMapper.insert(toRecord(source, stale, NewsItemStatus.ARCHIVED, now));
                existing.add(stale.urlHash());
                archivedStale++;
                inserted++;
            }
            insertedThisRound.put(source.getId(), inserted);
        }
        int deferredSources = 0;
        for (NewsSourceDO source : orderedSources) {
            if (!freshQueues.get(source.getId()).isEmpty()) {
                deferredSources++;
            }
        }
        log.info("[news] 准入完成：新准入 {} 条（全站余 {}），旧文归档 {} 条，幂等跳过 {} 条，截留源 {} 个",
                admitted, siteRemaining, archivedStale, skippedExisting, deferredSources);
        return new AdmissionResult(admitted, archivedStale, skippedExisting, siteRemaining, deferredSources);
    }

    /**
     * 待富化 TTL 收尾：首次发现（fetch_time）起 pending-ttl-hours（默认 48h）届满
     * 仍未获发布资格的 pending 条目转 expired 终态——退出付费待办、不再公开
     * （不承诺无条件次日清空；同 URL 重现不重建待办）
     *
     * @return 转 expired 条数
     */
    public int expireOverduePending() {
        Date now = nowSupplier.get();
        Date ttlFloor = new Date(now.getTime() - properties.effectivePendingTtlHours() * 3600L * 1000L);
        return itemMapper.update(null, Wrappers.lambdaUpdate(NewsItemDO.class)
                .eq(NewsItemDO::getStatus, NewsItemStatus.PENDING)
                .lt(NewsItemDO::getFetchTime, ttlFloor)
                .set(NewsItemDO::getStatus, NewsItemStatus.EXPIRED));
    }

    /**
     * 候选确定性排序：发布时间倒序（最新在前），同刻按 urlHash 升序平列
     */
    private static List<RawNewsItem> sortedByRecency(List<RawNewsItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream()
                .sorted(Comparator.comparing(RawNewsItem::publishTime,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(RawNewsItem::urlHash))
                .toList();
    }

    private NewsItemDO toRecord(NewsSourceDO source, RawNewsItem item, String status, Date now) {
        return NewsItemDO.builder()
                .sourceId(source.getId())
                .url(item.url())
                .urlHash(item.urlHash())
                .titleEn(item.title())
                .titleZh(item.titleZh())
                .category("other")
                .langRaw(item.langRaw())
                .publishTime(item.publishTime())
                .fetchTime(now)
                .status(status)
                .heat(0)
                .build();
    }

    /**
     * 库内已存在 url_hash 批查（幂等重发现跳过；同 URL 重现不重建任何待办）。
     * 返回<b>可变</b>集合：准入/归档段插入后回写本轮已入库哈希——同轮跨源
     * 重复 URL 视同已存在跳过，防撞 uq_news_item_url 唯一键（审核修正，#191）
     */
    private Set<String> loadExistingHashes(Set<String> candidateHashes) {
        if (candidateHashes.isEmpty()) {
            return new HashSet<>();
        }
        return itemMapper.selectList(Wrappers.lambdaQuery(NewsItemDO.class)
                        .select(NewsItemDO::getUrlHash)
                        .in(NewsItemDO::getUrlHash, candidateHashes))
                .stream().map(NewsItemDO::getUrlHash).collect(Collectors.toCollection(HashSet::new));
    }

    /**
     * 当日（HKT）各源已准入计数：fetch_time ≥ 日切 且 status&lt;&gt;archived——
     * 准入=进入处理管线（pending 及其后续终态），归档行不计；从库现推，重启不重置
     */
    private Map<Long, Long> countAdmittedTodayBySource(Date now) {
        ZonedDateTime nowHkt = ZonedDateTime.ofInstant(now.toInstant(), HKT_ZONE);
        Date dayStart = Date.from(nowHkt.toLocalDate().atStartOfDay(HKT_ZONE).toInstant());
        List<Map<String, Object>> rows = itemMapper.selectMaps(new QueryWrapper<NewsItemDO>()
                .select("source_id", "COUNT(*) AS cnt")
                .ge("fetch_time", dayStart)
                .ne("status", NewsItemStatus.ARCHIVED)
                .groupBy("source_id"));
        Map<Long, Long> counts = new HashMap<>();
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                Object sourceId = row.get("source_id");
                Object cnt = row.get("cnt");
                if (sourceId instanceof Number sourceIdNumber && cnt instanceof Number cntNumber) {
                    counts.put(sourceIdNumber.longValue(), cntNumber.longValue());
                }
            }
        }
        return counts;
    }
}

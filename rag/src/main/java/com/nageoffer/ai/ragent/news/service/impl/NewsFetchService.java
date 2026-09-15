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

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsSourceMapper;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchException;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchProperties;
import com.nageoffer.ai.ragent.news.fetch.NewsSourceFetcher;
import com.nageoffer.ai.ragent.news.fetch.RawNewsItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 抓取编排服务（抓取序列的编排段）
 *
 * <p>每源序列：策略分派 → 四型抓取器取原始条目 → 回灌窗口过滤 →
 * url_hash 幂等去重 → 批上限 → 入库 published。失败滞回沿用既有范式：
 * 成功（哪怕 0 新条目）清零 consecutive_failures；失败 +1，≥3 自动置
 * enabled=false（需人工复归——结构改版调查后再开）。
 *
 * <p>回灌窗口（默认 14 天）：首次回灌防首页空窗，之后靠 url_hash 去重
 * 自然纯增量（窗口外旧条目即使重新出现在列表页也不入库）；events 型活动开始
 * 时间可在未来，窗口只设下界不设上界。窗口天数与批上限外置 {@link NewsFetchProperties}
 * （历史回灌临时调大跑完即还原）；LLM 摘要/分类/topics 与热度模型归补全与热度模块，本类不碰。
 */
@Slf4j
@Service
public class NewsFetchService {

    /**
     * 滞回阈值：连续失败 ≥3 自动禁源
     */
    static final int FAILURE_THRESHOLD = 3;

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
     * 抓取单源并入库；返回新增条数。失败抛出并计入滞回（调用方 runSafely 隔离）。
     */
    public int fetchAndPersist(NewsSourceDO source) {
        try {
            NewsSourceFetcher fetcher = fetchersByStrategy.get(source.getFetchStrategy());
            if (fetcher == null) {
                // 配置错误也计滞回：3 轮后自动禁源，防止坏配置每轮空转
                throw new NewsFetchException("未知 fetch_strategy: " + source.getFetchStrategy(), false);
            }
            List<RawNewsItem> items = fetcher.fetch(source);
            int inserted = persistNewItems(source.getId(), items);
            resetFailures(source);
            log.info("[news] 源 {} 抓取成功：解析 {} 条，窗口内去重后新增 {} 条",
                    source.getSourceKey(), items.size(), inserted);
            return inserted;
        } catch (Exception e) {
            recordFailure(source, e);
            throw e instanceof NewsFetchException newsFetchException ? newsFetchException
                    : new NewsFetchException("抓取编排失败: " + e.getMessage(), false, e);
        }
    }

    /**
     * 窗口过滤 + 去重 + 批上限 + 入库（status=published；LLM 补双语与分类归补全模块）
     */
    int persistNewItems(Long sourceId, List<RawNewsItem> items) {
        Date now = nowSupplier.get();
        Date windowFloor = new Date(now.getTime() - properties.getBackfillDays() * 24L * 3600 * 1000);
        Map<String, RawNewsItem> inWindow = new HashMap<>();
        for (RawNewsItem item : items) {
            if (item.publishTime() == null || item.publishTime().before(windowFloor)) {
                continue;
            }
            inWindow.putIfAbsent(item.urlHash(), item);
        }
        if (inWindow.isEmpty()) {
            return 0;
        }
        List<String> hashes = new ArrayList<>(inWindow.keySet());
        java.util.Set<String> existing = itemMapper.selectList(Wrappers.lambdaQuery(NewsItemDO.class)
                        .select(NewsItemDO::getUrlHash)
                        .in(NewsItemDO::getUrlHash, hashes))
                .stream().map(NewsItemDO::getUrlHash).collect(java.util.stream.Collectors.toSet());
        int inserted = 0;
        for (RawNewsItem item : inWindow.values()) {
            if (inserted >= properties.getMaxItemsPerSource()) {
                log.warn("[news] 单源单轮入库达上限 {} 条，其余留待下轮：源条目 {}", properties.getMaxItemsPerSource(),
                        inWindow.size());
                break;
            }
            if (existing.contains(item.urlHash())) {
                continue;
            }
            NewsItemDO record = NewsItemDO.builder()
                    .sourceId(sourceId)
                    .url(item.url())
                    .urlHash(item.urlHash())
                    .titleEn(item.title())
                    .titleZh(item.titleZh())
                    .category("other")
                    .langRaw(item.langRaw())
                    .publishTime(item.publishTime())
                    .fetchTime(now)
                    .status("published")
                    .heat(0)
                    .build();
            itemMapper.insert(record);
            inserted++;
        }
        return inserted;
    }

    /**
     * 成功清零滞回计数（仅非零时写库，减少无效更新）
     */
    private void resetFailures(NewsSourceDO source) {
        if (source.getConsecutiveFailures() != null && source.getConsecutiveFailures() == 0) {
            return;
        }
        sourceMapper.update(null, Wrappers.lambdaUpdate(NewsSourceDO.class)
                .eq(NewsSourceDO::getId, source.getId())
                .set(NewsSourceDO::getConsecutiveFailures, 0)
                .set(NewsSourceDO::getUpdateTime, nowSupplier.get()));
    }

    /**
     * 失败滞回 +1；连续 ≥3 自动禁源（禁用是防持续打爆坏端点，复归=人工）
     */
    void recordFailure(NewsSourceDO source, Exception cause) {
        int failures = (source.getConsecutiveFailures() == null ? 0 : source.getConsecutiveFailures()) + 1;
        boolean disable = failures >= FAILURE_THRESHOLD;
        LambdaUpdateWrapper<NewsSourceDO> update = Wrappers.lambdaUpdate(NewsSourceDO.class)
                .eq(NewsSourceDO::getId, source.getId())
                .set(NewsSourceDO::getConsecutiveFailures, failures)
                .set(NewsSourceDO::getUpdateTime, nowSupplier.get());
        if (disable) {
            update.set(NewsSourceDO::getEnabled, false);
        }
        sourceMapper.update(null, update);
        source.setConsecutiveFailures(failures);
        if (disable) {
            source.setEnabled(false);
            log.error("[news] 源 {} 连续 {} 次失败，自动禁用（复归=人工置 enabled=true）：{}",
                    source.getSourceKey(), failures, cause.getMessage());
        } else {
            log.warn("[news] 源 {} 抓取失败（连续 {} 次）：{}", source.getSourceKey(), failures, cause.getMessage());
        }
    }
}

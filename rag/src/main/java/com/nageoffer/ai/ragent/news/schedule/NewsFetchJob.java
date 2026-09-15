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

package com.nageoffer.ai.ragent.news.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemTopicDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsTopicDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemTopicMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsSourceMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsTopicMapper;
import com.nageoffer.ai.ragent.news.heat.NewsHeatService;
import com.nageoffer.ai.ragent.news.service.impl.NewsEnrichService;
import com.nageoffer.ai.ragent.news.service.impl.NewsFetchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 资讯抓取定时任务（接线抓取、LLM 补全与人工抽检日志）
 *
 * <p>轮次序列：逐源抓取（runSafely 隔离，失败滞回 ≥3 自动禁源）→ LLM 补全批
 * （缺摘要条目，逐条隔离）→ 热度重算 → 人工抽检日志（仅首段轮次，日志输出当日新增
 * 与随机 5 条，供人工抽验）。每步独立隔离，单步失败不阻断后续步骤。
 * flag rag.news.enabled 关（默认）时本组件不装配，无任何调度行为。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.news.enabled", havingValue = "true")
public class NewsFetchJob {

    /**
     * 人工抽检日志条数（随机 5 条防漂移）
     */
    static final int MORNING_SAMPLE_SIZE = 5;

    /**
     * 首段判定：HKT 小时低于该值视为首段轮次（12 点前=08 点段）
     */
    static final int MORNING_CUTOFF_HOUR = 12;

    /**
     * 资讯管线统一时区（HKT +08:00，「今天」切日=HKT 00:00）
     */
    static final ZoneId HKT_ZONE = ZoneId.of("Asia/Hong_Kong");

    private final NewsSourceMapper sourceMapper;
    private final NewsItemMapper itemMapper;
    private final NewsItemTopicMapper itemTopicMapper;
    private final NewsTopicMapper topicMapper;
    private final NewsFetchService fetchService;
    private final NewsEnrichService enrichService;
    private final NewsHeatService heatService;
    private final Supplier<Date> nowSupplier;

    @Autowired
    public NewsFetchJob(NewsSourceMapper sourceMapper,
                        NewsItemMapper itemMapper,
                        NewsItemTopicMapper itemTopicMapper,
                        NewsTopicMapper topicMapper,
                        NewsFetchService fetchService,
                        NewsEnrichService enrichService,
                        NewsHeatService heatService) {
        this(sourceMapper, itemMapper, itemTopicMapper, topicMapper, fetchService, enrichService, heatService, Date::new);
    }

    /**
     * 全参构造器（测试注入时钟，FakeWindowCounter 时间旅行先例同源）
     */
    NewsFetchJob(NewsSourceMapper sourceMapper,
                 NewsItemMapper itemMapper,
                 NewsItemTopicMapper itemTopicMapper,
                 NewsTopicMapper topicMapper,
                 NewsFetchService fetchService,
                 NewsEnrichService enrichService,
                 NewsHeatService heatService,
                 Supplier<Date> nowSupplier) {
        this.sourceMapper = sourceMapper;
        this.itemMapper = itemMapper;
        this.itemTopicMapper = itemTopicMapper;
        this.topicMapper = topicMapper;
        this.fetchService = fetchService;
        this.enrichService = enrichService;
        this.heatService = heatService;
        this.nowSupplier = nowSupplier;
    }

    /**
     * 三段抓取：08/13/19 点；cron 外置 rag.news.fetch-cron
     */
    @Scheduled(cron = "${rag.news.fetch-cron:0 0 8,13,19 * * *}")
    public void fetchAllSources() {
        List<NewsSourceDO> sources = sourceMapper.selectList(Wrappers.lambdaQuery(NewsSourceDO.class)
                .eq(NewsSourceDO::getEnabled, true));
        if (sources.isEmpty()) {
            log.info("[news] 无启用信源，本轮跳过");
            return;
        }
        log.info("[news] 抓取轮启动：{} 个启用信源", sources.size());
        int failures = 0;
        for (NewsSourceDO source : sources) {
            try {
                fetchService.fetchAndPersist(source);
            } catch (Exception e) {
                failures++;
                log.error("[news] 源 {} 本轮失败（滞回已计）：{}", source.getSourceKey(), e.getMessage(), e);
            }
        }
        log.info("[news] 抓取轮结束：{} 源，失败 {} 源", sources.size(), failures);
        runSafely("LLM 补全批", () ->
                log.info("[news] 轮内 LLM 补全：{} 条成功", enrichService.enrichPendingItems()));
        runSafely("热度重算", () ->
                log.info("[news] 轮末热度重算：{} 条变更", heatService.recomputeHeat()));
        runSafely("晨报抽样", this::logMorningSample);
    }

    /**
     * 人工抽检日志：仅当日首段轮次（HKT 12 点前，即 08 点段）输出当日新增与
     * 随机 5 条，供人工抽验坏摘要/某源数日无更新；时区统一 HKT
     */
    void logMorningSample() {
        ZonedDateTime nowHkt = ZonedDateTime.ofInstant(nowSupplier.get().toInstant(), HKT_ZONE);
        if (nowHkt.getHour() >= MORNING_CUTOFF_HOUR) {
            return;
        }
        Date dayStart = Date.from(nowHkt.toLocalDate().atStartOfDay(HKT_ZONE).toInstant());
        List<NewsItemDO> recent = itemMapper.selectList(new LambdaQueryWrapper<NewsItemDO>()
                .ge(NewsItemDO::getFetchTime, dayStart)
                .orderByDesc(NewsItemDO::getId));
        if (recent.isEmpty()) {
            log.info("[news][晨报抽样] 当日（HKT）无新增条目——某源可能数日无更新，请对照滞回日志核查");
            return;
        }
        List<NewsItemDO> sample = new ArrayList<>(recent);
        Collections.shuffle(sample);
        log.info("[news][晨报抽样] 当日（HKT）新增 {} 条，随机 {} 条供人工抽验：", recent.size(), MORNING_SAMPLE_SIZE);
        sample.stream().limit(MORNING_SAMPLE_SIZE).forEach(item -> log.info(
                "[news][晨报抽样] #{} [{}] {} | tags={} | {}",
                item.getId(), item.getCategory(),
                item.getTitleZh() != null ? item.getTitleZh() : item.getTitleEn(),
                resolveTopicSlugs(item.getId()),
                item.getUrl()));
    }

    /**
     * 样本条目的主题标签 slug 集（2026-09-13 增补：抽检样本加「标签-摘要对应」检查项——
     * tags 随样本行输出，人工对照摘要判断是否实质相关；弱相关/误标走受控词表提案流治理）。
     * 标签存 join 表（t_news_item_topic→t_news_topic），两次小查询、仅 5 条样本零成本。
     */
    private String resolveTopicSlugs(Long itemId) {
        List<Long> topicIds = itemTopicMapper.selectList(new LambdaQueryWrapper<NewsItemTopicDO>()
                        .eq(NewsItemTopicDO::getItemId, itemId))
                .stream().map(NewsItemTopicDO::getTopicId).toList();
        if (topicIds.isEmpty()) {
            return "-";
        }
        Map<Long, String> slugById = topicMapper.selectList(new LambdaQueryWrapper<NewsTopicDO>()
                        .in(NewsTopicDO::getId, topicIds))
                .stream().collect(Collectors.toMap(NewsTopicDO::getId,
                        t -> t.getSlug() != null ? t.getSlug() : String.valueOf(t.getId()), (a, b) -> a));
        return topicIds.stream().map(id -> slugById.getOrDefault(id, String.valueOf(id)))
                .collect(Collectors.joining(","));
    }

    private void runSafely(String step, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.error("[news] 轮内步骤 {} 失败（不影响其余步骤）：{}", step, e.getMessage(), e);
        }
    }
}

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

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsSourceMapper;
import com.nageoffer.ai.ragent.news.service.impl.NewsFetchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 资讯抓取定时任务（U12-A A3 接线完成；doc 19 §4-1，D3 更新节奏）
 *
 * <p>节奏=in-app 定时 3 段（08/13/19 点），cron 外置 rag.news.fetch-cron；
 * flag rag.news.enabled 关（默认）时本组件不装配，无任何调度行为。
 * 逐源 runSafely 隔离（照 DataRetentionJob 范式）：单源失败只计滞回
 * （K2c，≥3 自动禁源）不阻断他源；总耗时上界由同 host ≥10s 节拍与
 * 单源 50 条批上限约束。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.news.enabled", havingValue = "true")
public class NewsFetchJob {

    private final NewsSourceMapper sourceMapper;
    private final NewsFetchService fetchService;

    /**
     * 三段抓取：08/13/19 点（D3）；晨报抽样在首段任务完成后由 A5 接线
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
    }
}

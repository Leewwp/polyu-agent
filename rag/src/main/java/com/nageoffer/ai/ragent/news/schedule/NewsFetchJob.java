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

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 资讯抓取定时任务骨架（U12-A A2；doc 19 §4-1，D3 更新节奏）
 *
 * <p>节奏=in-app 定时 3 段（08/13/19 点），cron 外置 rag.news.fetch-cron；
 * flag rag.news.enabled 关（默认）时本组件不装配，无任何调度行为。
 *
 * <p>A2 骨架占位：抓取序列（robots 校验→四型抓取器→url_hash 去重→详情正文→
 * LLM 摘要入库）与抓取纪律（同 host ≥10s 间隔、瞬时错误重试 1 次、
 * consecutive_failures≥3 滞回禁源、批上限防长事务）随 A3/A5 接线填充。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.news.enabled", havingValue = "true")
public class NewsFetchJob {

    /**
     * 三段抓取：08/13/19 点（D3）；晨报抽样在首段任务完成后由 A5 接线
     */
    @Scheduled(cron = "${rag.news.fetch-cron:0 0 8,13,19 * * *}")
    public void fetchAllSources() {
        // A2 骨架：无抓取副作用；A3 四型抓取器落地后此处逐源隔离执行（runSafely 范式）
        log.debug("[news] fetch cron triggered (A2 skeleton, A3/A5 wiring pending)");
    }
}

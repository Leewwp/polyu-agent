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

package com.nageoffer.ai.ragent.news.fetch;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 资讯抓取参数（抓取序列的窗口/批上限外置）
 *
 * <p>绑定 {@code rag.news.*} 节，默认值=日常运营口径（14 天窗口、
 * 单源单轮 50 条、HTML_LIST 仅首页、events 当前月+下月）。历史回灌（近
 * 3 个月一次性补齐）通过命令行参数临时调大跑完即还原，日常不改动：
 * {@code --rag.news.backfill-days=95 --rag.news.max-items-per-source=500
 * --rag.news.fetch-pages-max=15 --rag.news.events-past-months=3}。
 */
@Data
@Configuration
@ConfigurationProperties("rag.news")
public class NewsFetchProperties {

    /**
     * 回灌窗口天数：早于（now - backfillDays）的条目不入库
     */
    private int backfillDays = 14;

    /**
     * 单源单轮入库上限（批上限防长事务）
     */
    private int maxItemsPerSource = 50;

    /**
     * HTML_LIST 型抓取页深（1=仅 fetch_endpoint 首页，即现行口径；>1 时逐页
     * 翻取，空页/零有效条目自动到头停止，首页零条目仍 fail-closed）
     */
    private int fetchPagesMax = 1;

    /**
     * events 型回溯过去月数（0=现行口径：当前月+下月两请求；>0 时向前多取
     * N 个月份请求，跨月活动仍防漏）
     */
    private int eventsPastMonths = 0;
}

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
 * 资讯抓取与预算参数（抓取序列的窗口/批上限+LLM 摘要预算护栏外置，#184）
 *
 * <p>绑定 {@code rag.news.*} 节，默认值=日常运营口径（14 天窗口、
 * 单源单轮 50 条、HTML_LIST 仅首页、events 当前月+下月）。历史回灌（近
 * 3 个月一次性补齐）通过命令行参数临时调大跑完即还原，日常不改动：
 * {@code --rag.news.backfill-days=95 --rag.news.max-items-per-source=500
 * --rag.news.fetch-pages-max=15 --rag.news.events-past-months=3}。
 *
 * <p>预算护栏（{@code rag.news.budget-*}，父票 #181 §1 合同默认值）：
 * 日 ¥1.0 / 月 ¥15，单次发出按 5k 入+1k 出 flash 原价 ≈¥0.005 封顶估算，
 * attempts=真实发出次数（含 fallback/重试）、PG 持久化重启不清零；
 * 超额当日降级=仅入库不富化，次日按剩余配额自然补偿（待补条目
 * summary_en IS NULL 常驻查询）。
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

    // ================== LLM 摘要预算护栏（#184，父票 #181 §1 合同） ==================

    /**
     * 资讯摘要日额度（元，HKT 日切）：超额当日降级=仅入库不富化，次日补偿
     */
    private double budgetDailyYuan = 1.0D;

    /**
     * 资讯摘要月额度（元，HKT 月切）：红线 ¥10 量级+50% 缓冲
     */
    private double budgetMonthlyYuan = 15.0D;

    /**
     * 单次发出估算成本（元）：按 5k 入+1k 出 flash 原价封顶 ≈¥0.005
     */
    private double budgetCostPerAttemptYuan = 0.005D;

    /**
     * 摘要输出 token 上限：ChatRequest.maxTokens 透传（底层已支持 max_tokens）
     */
    private int summaryMaxTokens = 1024;

    /**
     * 送 LLM 的正文输入 token 上限（估算口径：CJK 字 1 token、其余 4 字符 1 token）
     */
    private int maxInputTokens = 4000;

    /**
     * 同请求网关层重试上限（≤2，不含首次；路由 fallback 不算网关重试）
     */
    private int llmMaxRetries = 2;
}

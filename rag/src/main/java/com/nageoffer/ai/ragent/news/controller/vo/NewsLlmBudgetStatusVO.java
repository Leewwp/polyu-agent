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

package com.nageoffer.ai.ragent.news.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 资讯 LLM 预算消耗状态（admin 验收口径，#184）
 *
 * <p>双口径：attempts=真实发出次数（含 fallback/重试）+ 估算成本（元）；
 * 附降级事件数与剩余额度，维护者验收时在此核对「预算消耗与 attempts 双口径」
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NewsLlmBudgetStatusVO {

    /**
     * 当日（HKT）：估算成本（元）
     */
    private BigDecimal dailyCostYuan;

    /**
     * 当日（HKT）：真实发出次数（含 fallback/重试）
     */
    private Long dailyAttempts;

    /**
     * 当日（HKT）：预算耗尽降级事件数（次）
     */
    private Long dailyDegraded;

    /**
     * 当日额度（元，rag.news.budget-daily-yuan）
     */
    private BigDecimal dailyLimitYuan;

    /**
     * 当日剩余额度（元，负值=超支事实记录）
     */
    private BigDecimal dailyRemainingYuan;

    /**
     * 当月（HKT）：估算成本（元）
     */
    private BigDecimal monthlyCostYuan;

    /**
     * 当月（HKT）：真实发出次数
     */
    private Long monthlyAttempts;

    /**
     * 当月额度（元，rag.news.budget-monthly-yuan）
     */
    private BigDecimal monthlyLimitYuan;

    /**
     * 当月剩余额度（元）
     */
    private BigDecimal monthlyRemainingYuan;
}

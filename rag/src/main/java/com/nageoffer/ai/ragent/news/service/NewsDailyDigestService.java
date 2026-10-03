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

package com.nageoffer.ai.ragent.news.service;

import java.time.LocalDate;

/**
 * 资讯日报生成服务（#212，父票 #182 r3 §日报——P2-a 出口）
 *
 * <p>只负责<b>生成/重建</b>（写路径）；公开读取（零 LLM）在
 * {@link NewsDailyDigestQueryService}——读写分离是「页面请求不触发 LLM」的
 * 结构保证（读服务不依赖任何 LLM 组件）。
 */
public interface NewsDailyDigestService {

    /**
     * 生成（或重建）指定 HKT 日期的日报：幂等——同日期重跑只产一刊
     * （digest_date 唯一+先删后插，快照行经外键级联随旧刊头带走）。
     * 空窗口也落一行空刊（固定模板导语，零调用）。
     *
     * @param digestDate HKT 日报日期（窗口闭端日：窗口=[D-1 08:00, D 08:00)）
     * @return 生成结果（条数+导语产出方式）
     */
    DigestBuildResult rebuildForDate(LocalDate digestDate);

    /**
     * 漏跑补齐：该日期已存在刊时零动作（已存在的刊不自动重建——重建只由
     * {@link #rebuildForDate} 显式触发）；不存在时生成
     *
     * @return true=本次生成；false=已存在跳过
     */
    boolean generateIfMissing(LocalDate digestDate);

    /**
     * 生成结果载荷
     *
     * @param digestDate  刊日期
     * @param itemCount   快照条数（生成时刻口径）
     * @param introSource 导语产出方式（llm/fallback/empty，见 NewsDailyDigestDO）
     * @param created     false=generateIfMissing 命中已存在刊
     */
    record DigestBuildResult(LocalDate digestDate, int itemCount, String introSource, boolean created) {
    }
}

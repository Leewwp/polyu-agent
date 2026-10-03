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

import java.time.LocalDate;
import java.util.Date;

/**
 * 日报目录行 VO（#212）：日期+统计+导语产出方式——<b>不携带导语正文</b>
 * （列表面不展示导语：隐藏条目不会在目录行留导语残留，同时保持列表轻量）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsDailyDigestSummaryVO {

    /**
     * HKT 日报日期（窗口闭端日）
     */
    private LocalDate digestDate;

    /**
     * 快照条数（生成时刻口径；读取期下架复检后的可见条数以详情接口为准）
     */
    private Integer itemCount;

    /**
     * 导语产出方式（llm/fallback/empty——透明口径，前端可注明「模板导语」）
     */
    private String introSource;

    /**
     * 生成时刻
     */
    private Date buildTime;
}

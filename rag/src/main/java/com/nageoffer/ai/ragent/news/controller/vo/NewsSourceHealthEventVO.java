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

import java.util.Date;

/**
 * 信源健康事件（admin 验收口径，#186：停止/复归记录可查）
 *
 * <p>event_type：isolated=自动隔离（3 连败）/ policy_disabled=策略转停（robots/守卫）/
 * probe_pass=探活通过 / probe_fail=探活失败 / recovered=探活复归。
 * 人工停用/启用走维护者 SQL 不落本表（以源面板 disabled_reason=manual 与库行为准）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NewsSourceHealthEventVO {

    /**
     * 事件 ID
     */
    private Long id;

    /**
     * 信源稳定标识（join t_news_source 还原，缺行（源已删）回退显示 ID）
     */
    private String sourceKey;

    /**
     * 事件类型（isolated/policy_disabled/probe_pass/probe_fail/recovered）
     */
    private String eventType;

    /**
     * 触发事件的单轮六类结果代码
     */
    private String outcome;

    /**
     * 判定依据摘要
     */
    private String detail;

    /**
     * 事件时刻
     */
    private Date eventTime;
}

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
 * 信源健康面板行（admin 验收口径，#186）：源健康/停用原因三分/探活状态单行快照
 *
 * <p>disabled_reason 三分=manual（人工停用，不探活）/ auto（自动隔离，唯一探活对象）/
 * policy（策略禁止，不因可达解禁）；last_outcome=最近一轮六类结果代码；事件流水
 * （停止/复归/探活）另见 {@link NewsSourceHealthEventVO}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NewsSourceHealthVO {

    /**
     * 源 ID
     */
    private Long id;

    /**
     * 信源稳定标识
     */
    private String sourceKey;

    /**
     * 展示名（中文）
     */
    private String displayName;

    /**
     * 展示名（英文）
     */
    private String displayNameEn;

    /**
     * 平台（official/youtube/prn/gnews/events）
     */
    private String platform;

    /**
     * 抓取策略（SITEMAP/HTML_LIST/RSS/RSS_GNEWS/JSON_API）
     */
    private String fetchStrategy;

    /**
     * 源级开关
     */
    private Boolean enabled;

    /**
     * 停用原因（manual/auto/policy；NULL=启用中）——三分承载见类 javadoc
     */
    private String disabledReason;

    /**
     * 连续失败计数（结构失配/网络失败滞回；defer 不计）
     */
    private Integer consecutiveFailures;

    /**
     * 最近一轮六类结果代码（valid_with_content/valid_empty/structure_mismatch/
     * network_failure/policy_forbidden/defer）
     */
    private String lastOutcome;

    /**
     * 最近一轮结果时刻
     */
    private Date lastOutcomeTime;

    /**
     * 探活连续有效完整成功次数（复归阈值默认 2）
     */
    private Integer probeSuccesses;

    /**
     * 最近探活时刻（日级节拍判定）
     */
    private Date probeTime;

    /**
     * 最近停用时刻（自动隔离/策略转停）
     */
    private Date isolatedTime;

    /**
     * 最近探活复归时刻
     */
    private Date recoveredTime;

    /**
     * 是否允许空结果为健康（rag.news.allow-empty-sources 命中）
     */
    private boolean allowEmpty;

    /**
     * 是否在探活对象内（enabled=false AND disabled_reason=auto）
     */
    private boolean probeEligible;
}

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

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

/**
 * 资讯管线状态与六口径日报（#185 验收面：发现/准入/唯一内容/富化成功/公开展示/事件数）
 *
 * <p>六口径语义（HKT 日窗 [00:00, 次日 00:00)）：
 * <ul>
 * <li>discovered：当日入库条目数（全状态——含归档，发现≠准入）</li>
 * <li>admitted：当日新准入数（fetch_time 当日 且 status≠archived）——受全站
 * 日上限约束的口径；archivedToday 单列</li>
 * <li>uniqueContent：当日准入中规范化标题去重数——#180 R2 完整内容哈希
 * （标题+正文摘录）未落本票，此为标题面代理口径</li>
 * <li>enrichedLlm：当日获发布资格且 summary_source=llm 的条数（富化成功）</li>
 * <li>publicVisible：当日获资格且此刻过发布门（eligible_time≤now-180s）且
 * status=published 的条数（含零调用回退；公开展示）</li>
 * <li>eventCount：恒 0——事件身份与聚簇归 #187，本票为占位口径</li>
 * </ul>
 */
@Data
@Builder
public class NewsPipelineStatusVO {

    /**
     * 报告覆盖的 HKT 日
     */
    private LocalDate date;

    /**
     * 发布门时长（秒，实际生效值）
     */
    private Integer gateSeconds;

    /**
     * 全站日准入上限（实际生效值）
     */
    private Integer siteDailyCap;

    /**
     * 六口径·发现：当日入库条目数（全状态）
     */
    private Long discovered;

    /**
     * 六口径·准入：当日新准入数（status≠archived）
     */
    private Long admitted;

    /**
     * 六口径·唯一内容：当日准入的规范化标题去重数（标题面代理，完整内容哈希归后续）
     */
    private Long uniqueContent;

    /**
     * 六口径·富化成功：当日经 LLM 获发布资格条数（summary_source=llm）
     */
    private Long enrichedLlm;

    /**
     * 六口径·公开展示：当日获资格且此刻过门可见条数（含零调用回退）
     */
    private Long publicVisible;

    /**
     * 六口径·事件数：占位恒 0（#187 落地事件身份与聚簇）
     */
    private Long eventCount;

    /**
     * 当日旧文归档数（发现超 48h 直入 archived）
     */
    private Long archivedToday;

    /**
     * 当日零调用回退获资格数（summary_source=fallback 且 eligible_time 当日）
     */
    private Long fallbackToday;

    /**
     * 当前待富化积压（status=pending 实时数）
     */
    private Long pendingNow;

    /**
     * 待富化超龄转终态累计（status=expired 实时数；过期时刻未落列，按现存总量报）
     */
    private Long expiredTotal;

    /**
     * 全站当日剩余准入额度（上限-已准入，可为 0）
     */
    private Long siteRemainingToday;
}

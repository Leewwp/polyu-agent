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

package com.nageoffer.ai.ragent.calendar.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 校历源状态展示载荷（只读消费 t_key_date_source，#193 三态口径）。
 *
 * <p>state 四值（三态展示口径+人工停用）：
 * <ul>
 *   <li>normal：active 且退化计数 0——数据随最近完整轮刷新；</li>
 *   <li>degraded：active 但 degraded_streak&gt;0——<b>展示为最后完整版本</b>
 *       （lastSuccessAt 未刷新，页面不得冒充最新）；</li>
 *   <li>isolated：auto_isolated 自动隔离——日级只读探测中，库内数据为隔离前
 *       最后完整版本（历史发布，非最新）；</li>
 *   <li>manual_disabled：enabled=0 人工停用——不参与同步也不自动复活。</li>
 * </ul>
 * last_success_at 只在完整版本原子发布后刷新（合同§6），本层原样透出——
 * 退化/隔离源的陈旧时间是「数据截至」的证据，不是要修的 bug。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeyDateSourceVO {

    private String sourceKey;

    /** writer=权威写者（四）/ verifier=纯校验源（一，零事件写径） */
    private String role;

    /** 人工启停（true=参与同步） */
    private boolean enabled;

    /** normal / degraded / isolated / manual_disabled（见类注释） */
    private String state;

    /** 该源最近完整候选覆盖学年（如 2026/27；未成功同步为 null） */
    private String coverageAcademicYear;

    /**
     * 最近完整同步时间（≠内容更新时间；退化轮不刷新——展示语义=数据截至）
     */
    private LocalDateTime lastSuccessAt;

    /** 连续退化轮数（正常 0） */
    private Integer degradedStreak;
}

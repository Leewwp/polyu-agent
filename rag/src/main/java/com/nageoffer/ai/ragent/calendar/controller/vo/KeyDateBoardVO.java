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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 校历关键日期看板载荷（#193 独立查询口的统一出参；日期 MCP（#182 接口）
 * 复用同一形状，controller 只是薄壳）。
 *
 * <p>段划分（过期 N 天自动归档=查询侧分类，不写回库——N 走配置
 * {@code rag.calendar.display.archive-after-days}）：
 * <ul>
 *   <li>currentAndUpcoming：ongoing+today+upcoming，date_start 升序（临近度
 *       排序：进行中最前、越近越前）——首页卡片取前 K 条即同一序；</li>
 *   <li>recentPast：过期 ≤N 天（含 N 当天），按结束日倒序（最新过期在前）；</li>
 *   <li>archived：过期 &gt;N 天，按结束日倒序、条数封顶 archived-cap（防多年
 *       累积无界），archivedTotal 为全量计数；</li>
 *   <li>undated：fuzzy 模糊窗（原文桶呈现，不倒计时不伪造具体日）。</li>
 * </ul>
 *
 * <p>三态口径：sources 逐源透出 normal/degraded/isolated（+manual_disabled）；
 * anySourceAbnormal=true 时页面必须按「最后完整版本（截至 lastFullSyncAt）」
 * 呈现，不得冒充最新。today 为服务端分类锚点（Asia/Hong_Kong）——徽章文案
 * 直接消费该值，避免客户端时区漂移。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeyDateBoardVO {

    /** 写者源覆盖学年聚合（各源取字典序最大；全部未同步为 null） */
    private String coverageAcademicYear;

    /** 最近完整同步时间（各源 last_success_at 最大值；只在完整发布后刷新） */
    private LocalDateTime lastFullSyncAt;

    /** 服务端分类锚点（Asia/Hong_Kong 当日） */
    private LocalDate today;

    /** 是否存在退化/隔离源（true=页面按最后完整版本口径呈现） */
    private boolean anySourceAbnormal;

    /** 五源状态（writer 四+verifier 一） */
    private List<KeyDateSourceVO> sources;

    /** 进行中+今日+即将（date_start 升序；首页卡片前 K 条同序） */
    private List<KeyDateVO> currentAndUpcoming;

    /** 过期 ≤N 天（N=archive-after-days；结束日倒序） */
    private List<KeyDateVO> recentPast;

    /** 过期 &gt;N 天（结束日倒序；封顶 archived-cap 条） */
    private List<KeyDateVO> archived;

    /** archived 全量计数（封顶截断时 > archived.size()） */
    private long archivedTotal;

    /** fuzzy 模糊窗（无具体日；不进倒计时） */
    private List<KeyDateVO> undated;
}

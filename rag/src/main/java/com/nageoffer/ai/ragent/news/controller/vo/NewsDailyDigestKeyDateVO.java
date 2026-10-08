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

/**
 * 日报校历关键日期栏目条目 VO（#316——总纲 #315 线一 L1）：快照行直映，
 * 与 t_key_date 现值无关（withdrawn/归档不连带）。
 *
 * <p>ongoing/daysUntil 为<b>生成期冻结</b>口径（as-of=刊日，不随读取时刻
 * 漂移）：daysUntil=0=当日开始，负值=已开始区间（前端 ongoing 徽章优先）；
 * 倒计时门=仅 exact-day/exact-range，onwards 恒 null（口径同
 * {@link com.nageoffer.ai.ragent.calendar.controller.vo.KeyDateVO}）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsDailyDigestKeyDateVO {

    /**
     * 栏内序（1 起，date_start 升序、uid 兜底）
     */
    private Integer seq;

    /**
     * t_key_date 语义身份（SHA-256 hex）
     */
    private String uid;

    /**
     * 中文标题（词表缺词 null=前端回退英文）
     */
    private String titleZh;

    /**
     * 英文标题
     */
    private String titleEn;

    /**
     * 官方人群限制原文（不得省略，随行快照）
     */
    private String audienceText;

    /**
     * exact-day / exact-range / onwards（fuzzy 行不落窗）
     */
    private String precision;

    /**
     * YYYY-MM-DD（落窗条目必有）
     */
    private LocalDate dateStart;

    /**
     * YYYY-MM-DD（仅 exact-range）
     */
    private LocalDate dateEnd;

    /**
     * 模糊窗原文桶（随行快照保形；落窗条目实际不使用）
     */
    private String fuzzyHint;

    /**
     * 已开始未结束（date_start < 刊日 且 有效结束日 >= 刊日）
     */
    private Boolean ongoing;

    /**
     * 刊日→date_start 天数（仅 exact-day/exact-range；onwards null）
     */
    private Integer daysUntil;
}

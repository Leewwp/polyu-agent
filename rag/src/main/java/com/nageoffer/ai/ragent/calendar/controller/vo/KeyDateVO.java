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

/**
 * 校历关键日期展示载荷（#193 查询面，只读消费 t_key_date）。
 *
 * <p>双语标题直通落库列（title_zh NULL=缺词，消费侧回退英文——词表回退由
 * #192 落库约定，本层不翻译零 LLM）。日期一律 {@link LocalDate}（String↔DATE
 * 必崩判例）；fuzzy 行不伪造具体日（date_start/date_end 为 null，只透
 * fuzzy_hint 原文窗桶）。
 *
 * <p>phase/daysUntil 为查询侧分类结果（锚点=Asia/Hong_Kong 当日，每次请求
 * 重算，不写回库）：
 * <ul>
 *   <li>phase：today=当日生效 / ongoing=exact-range 进行中 / upcoming=未来 /
 *       recent=过期 ≤N 天（N=rag.calendar.display.archive-after-days）/
 *       archived=过期 &gt;N 天 / undated=fuzzy 或无日期行；</li>
 *   <li>daysUntil：今日→date_start 天数（负=已过）。仅 exact-day/exact-range
 *       计算（onwards/fuzzy 不进倒计时——开放起点/模糊窗不伪造精确截止语义）。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeyDateVO {

    /** 稳定身份（六段语义数组 SHA-256；改期不换 UID） */
    private String uid;

    /** 覆盖学年（如 2026/27） */
    private String academicYear;

    /** AY / S1 / S2 / SU */
    private String term;

    /** 英文标题（词表模板生成，恒非空） */
    private String titleEn;

    /** 中文标题（词表缺词为 null——消费侧回退英文） */
    private String titleZh;

    /** exact-day / exact-range / onwards / fuzzy */
    private String precision;

    /** 起始日（fuzzy 为 null） */
    private LocalDate dateStart;

    /** 结束日（仅 exact-range 非 null；库内两端包含） */
    private LocalDate dateEnd;

    /** 模糊窗原文桶（仅 fuzzy 非空，如 Late October 2026） */
    private String fuzzyHint;

    /** 官方人群限制原文（合同§2：不得省略） */
    private String audienceText;

    /** 官方来源 URL（页面证据外链） */
    private String sourceUrl;

    /** 查询侧时效分类（见类注释；HKT 锚点） */
    private String phase;

    /** 今日→date_start 天数；仅 exact-day/exact-range，其余 null */
    private Integer daysUntil;
}

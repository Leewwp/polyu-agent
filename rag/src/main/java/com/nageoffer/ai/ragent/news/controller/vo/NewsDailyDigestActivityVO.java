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
 * 日报校园活动版面条目 VO（#330——父票 #317，总纲 #315 线一 L2）：快照行
 * 直映，与 t_news_item 现值无关（保留清理/下架不连带）。
 *
 * <p>ongoing 为<b>生成期冻结</b>口径（as-of=刊日，不随读取时刻漂移）：
 * true=已开始未结束（前端「进行中」组），false=即将来临（含当日开始——
 * 与 L1 关键日期「当日开始不标进行中」同口径）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsDailyDigestActivityVO {

    /**
     * 版面内序（1 起，date_start 升序、item_id 兜底）
     */
    private Integer seq;

    /**
     * 溯源 t_news_item.id（无外键语义，快照独立性红线）
     */
    private Long itemId;

    private String titleZh;

    private String titleEn;

    /**
     * 详情页永久外链（卡片外链语义）
     */
    private String url;

    /**
     * 活动开始日（YYYY-MM-DD，HKT 历日）
     */
    private LocalDate dateStart;

    /**
     * 活动结束日（YYYY-MM-DD，HKT 历日，含端）
     */
    private LocalDate dateEnd;

    /**
     * 进行中=开始日 &lt; 刊日 且 结束日 &gt;= 刊日（生成期冻结）
     */
    private Boolean ongoing;
}

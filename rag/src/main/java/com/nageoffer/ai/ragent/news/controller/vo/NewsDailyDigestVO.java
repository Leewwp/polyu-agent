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
import java.util.List;

/**
 * 日报详情 VO（#212）：刊头+读取期复检后的可见条目
 *
 * <p>introZh/introEn 为<b>生效导语</b>（读取期零调用口径）：无失格条目=刊头
 * 原导语；有失格条目=固定模板导语（可见条数&gt;0）或空刊模板（=0）——
 * 主动下架后出口无导语残留。storedIntroSource 透出刊头导语产出方式供前端标注。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsDailyDigestVO {

    /**
     * HKT 日报日期（窗口闭端日）
     */
    private LocalDate digestDate;

    /**
     * 窗口起点（D-1 08:00 HKT，含）
     */
    private Date windowStart;

    /**
     * 窗口闭端（D 08:00 HKT，不含）
     */
    private Date windowEnd;

    /**
     * 生效导语（中文）：读取期零调用口径，见类 javadoc
     */
    private String introZh;

    /**
     * 生效导语（英文）
     */
    private String introEn;

    /**
     * 刊头导语产出方式（llm/fallback/empty，生成期落定）
     */
    private String storedIntroSource;

    /**
     * 生效导语是否为读取期模板回退（true=有快照条目失格，导语已回退模板）
     */
    private Boolean introDegraded;

    /**
     * 快照总条数（生成时刻口径）
     */
    private Integer itemCount;

    /**
     * 读取期复检后可见条数
     */
    private Integer visibleCount;

    /**
     * 读取期失格条数（主动下架复检过滤掉的快照条数）
     */
    private Integer disqualifiedCount;

    /**
     * 可见条目（刊内序升序）
     */
    private List<NewsDailyDigestItemVO> items;

    /**
     * 生成时刻
     */
    private Date buildTime;
}

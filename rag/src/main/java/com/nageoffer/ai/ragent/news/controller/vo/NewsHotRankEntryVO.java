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

import java.util.List;

/**
 * 热点榜条目载荷（热度榜形态）
 *
 * <p>一条榜单位=一条故事线（合并开启时多源报道并入代表条目；降级态 sources
 * 只含自身）。标签=boom/fresh/rise（爆/新/发酵中，前端映射文案）；
 * sources=信源展示名列表（来源数徽章点击弹名单的数据源）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsHotRankEntryVO {

    /**
     * 故事线代表条目 ID（首报成员）
     */
    private Long itemId;

    /**
     * 标题（中文）
     */
    private String titleZh;

    /**
     * 标题（英文）
     */
    private String titleEn;

    /**
     * 热度分（簇热度，已按 24h 半衰持久化）
     */
    private Integer heat;

    /**
     * 标签（boom/fresh/rise 子集，可空）
     */
    private List<String> tags;

    /**
     * 覆盖信源展示名列表（badge 点击弹信源名单）
     */
    private List<String> sources;
}

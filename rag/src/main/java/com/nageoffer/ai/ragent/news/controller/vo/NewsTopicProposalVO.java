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
 * 待审主题提案行（admin 批量审核面，#202）
 *
 * <p>suggestedAction 三档规则建议（机器侧只看引用数与阈值，近义判定归人工）：
 * promote=引用数已达转正阈值（无近义 curated 目标时的建议轨）；
 * reject=零引用泛化嫌疑（近义目标明确时可人工改 merge）；
 * review=有引用未达阈值（人工近义判定 merge 或 pending 观察）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NewsTopicProposalVO {

    /**
     * 提案行 ID（t_news_topic.id，批量应用按此回指）
     */
    private Long id;

    /**
     * 提案 slug（prop- 哈希形态；promote 时替换为稳定 slug）
     */
    private String slug;

    /**
     * 主题名（中文列；英文新词以原文占位）
     */
    private String nameZh;

    /**
     * 主题名（英文列，可空）
     */
    private String nameEn;

    /**
     * 分组（提案期 PROPOSED）
     */
    private String topicGroup;

    /**
     * 条目关联数（item_refs=t_news_item_topic 按 topic_id 计数，覆盖数）
     */
    private Long itemRefs;

    /**
     * 规则建议轨别：promote / reject / review
     */
    private String suggestedAction;

    /**
     * 建议理由（含阈值口径，供人工复核）
     */
    private String suggestedReason;

    /**
     * 提案入库时间（09-16 批 vs 新增行的时间旁证）
     */
    private Date createTime;
}

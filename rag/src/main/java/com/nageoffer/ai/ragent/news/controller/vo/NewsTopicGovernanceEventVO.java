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
 * 主题治理留痕事件（admin 处置历史可复核面，#202）
 *
 * <p>action：merged=并入近义 curated 主题（targetTopicId 回指）/
 * promoted=转正（detail 含旧→新 slug 与阈值依据）/ rejected=弃
 * （detail 含摘除关联数）。append-only 只增不改。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NewsTopicGovernanceEventVO {

    /**
     * 事件 ID
     */
    private Long id;

    /**
     * 被处置主题行 ID
     */
    private Long topicId;

    /**
     * 被处置主题 slug（处置后现值：promote 后为稳定 slug，merged/rejected 后为原 prop- 哈希）
     */
    private String topicSlug;

    /**
     * 处置动作（merged/promoted/rejected）
     */
    private String action;

    /**
     * merge 目标主题 ID（其余动作 null）
     */
    private Long targetTopicId;

    /**
     * merge 目标主题 slug（join 还原，行缺失回退显示 ID）
     */
    private String targetTopicSlug;

    /**
     * 处置明细（迁移/摘除关联数、旧→新 slug、阈值依据）
     */
    private String detail;

    /**
     * 处置操作者（admin 账号名；本地回放为 replay 标记）
     */
    private String operator;

    /**
     * 事件时刻
     */
    private Date eventTime;
}

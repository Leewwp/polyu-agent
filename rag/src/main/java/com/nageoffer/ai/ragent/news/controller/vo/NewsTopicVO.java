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

/**
 * 公开主题目录卡载荷（doc 19 §5：三维分组目录——名称+一句话简介+条目计数）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsTopicVO {

    /**
     * 主题稳定标识（详情页路由 /public/news/topic/{slug}）
     */
    private String slug;

    /**
     * 主题名（中文）
     */
    private String nameZh;

    /**
     * 主题名（英文）
     */
    private String nameEn;

    /**
     * 三维分组：FACULTY / RESEARCH / STUDENT_AFFAIRS
     */
    private String topicGroup;

    /**
     * 界定描述（中文）
     */
    private String descriptionZh;

    /**
     * 界定描述（英文）
     */
    private String descriptionEn;

    /**
     * 已发布条目计数（curated=false 提案不进目录，也不计此数——其条目尚未挂到提案上）
     */
    private Long itemCount;
}

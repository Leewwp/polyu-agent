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
 * 资讯卡片信源元数据（VO 含双语字段+source 元数据）
 *
 * <p>official=false 时前端卡片带平台徽章 + 页脚「非官方」声明（展示纪律）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsSourceMetaVO {

    /**
     * 信源稳定标识（news-sitemap / media-releases / youtube-main 等）
     */
    private String sourceKey;

    /**
     * 平台：official / youtube / prn / events 等
     */
    private String platform;

    /**
     * 是否官网来源（false=卡片带平台徽章）
     */
    private Boolean official;

    /**
     * 信源展示名（中文）
     */
    private String displayName;

    /**
     * 信源展示名（英文）
     */
    private String displayNameEn;
}

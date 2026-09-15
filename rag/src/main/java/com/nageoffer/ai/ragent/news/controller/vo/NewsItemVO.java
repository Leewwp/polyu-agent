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
import java.util.List;

/**
 * 公开资讯条目载荷（匿名可读）
 *
 * <p>双语字段支持全局+单卡两级切换；url=永久原文外链（卡片永远指向官方原文，
 * 摘要声明「AI 摘要、以原文为准」）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsItemVO {

    /**
     * 条目 ID
     */
    private Long id;

    /**
     * 原文 URL（永久外链）
     */
    private String url;

    /**
     * 标题（中文）
     */
    private String titleZh;

    /**
     * 标题（英文）
     */
    private String titleEn;

    /**
     * AI 摘要（中文）
     */
    private String summaryZh;

    /**
     * AI 摘要（英文）
     */
    private String summaryEn;

    /**
     * 固定 8 类之一（筛选 chips 维度）+other 兜底
     */
    private String category;

    /**
     * 原文发布时间
     */
    private Date publishTime;

    /**
     * 热度分（热点榜与主题「近期焦点」共用；热度模型灌值）
     */
    private Integer heat;

    /**
     * 故事线聚簇：「另有 N 个来源」徽章（覆盖信源数-1）；null=未入簇/降级态/窗口外
     */
    private Integer clusterSourceCount;

    /**
     * 主题 slug 列表（t_news_item_topic 链接对应物；真数据接线补——前端主题详情
     * 按 slug 过滤条目、卡片主题章消费；无主题条目为空列表）
     */
    private List<String> topics;

    /**
     * 信源元数据（平台徽章/官网标记/双语名）
     */
    private NewsSourceMetaVO source;
}

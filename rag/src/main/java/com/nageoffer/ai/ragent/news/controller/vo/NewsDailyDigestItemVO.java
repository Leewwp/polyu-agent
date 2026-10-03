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
 * 日报条目快照 VO（#212）：全部字段来自 t_news_daily_digest_item 快照列，
 * 与 t_news_item 现值无关（源行被 90 天保留清理删除后仍完整返回）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsDailyDigestItemVO {

    /**
     * 溯源 t_news_item.id（点击可尝试进 /news/{id} 详情；源行已清理时详情
     * 404 属预期——卡片主链接是原文外链 url）
     */
    private Long itemId;

    /**
     * 刊内序（1 起，确定性序：发布时间倒序）
     */
    private Integer seq;

    /**
     * 原文 URL 快照（永久外链）
     */
    private String url;

    /**
     * 标题（中文）快照
     */
    private String titleZh;

    /**
     * 标题（英文）快照
     */
    private String titleEn;

    /**
     * 摘要（中文）快照
     */
    private String summaryZh;

    /**
     * 摘要（英文）快照
     */
    private String summaryEn;

    /**
     * 固定 8 类+other 快照
     */
    private String category;

    /**
     * 主题 slug 快照（生成时刻关联）
     */
    private List<String> topics;

    /**
     * 原文发布时间快照
     */
    private Date publishTime;

    /**
     * 信源元数据快照（sourceKey/platform/official/displayName/displayNameEn）
     */
    private NewsSourceMetaVO source;
}

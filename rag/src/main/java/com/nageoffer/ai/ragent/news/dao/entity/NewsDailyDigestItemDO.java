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

package com.nageoffer.ai.ragent.news.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 资讯日报条目快照实体（#212）
 *
 * <p><b>快照独立性红线</b>：item_id/source_id 只作溯源显示，<b>不建外键</b>——
 * t_news_item 有 90 天保留清理（NewsRetentionJob），外键级联会连带删空历史日报；
 * 标题/摘要/URL/来源/主题/分类等展示字段全部冗余快照，源行清理后日报仍完整可读。
 * (digest_id, item_id) 唯一=同刊内一源条目一行。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_news_daily_digest_item")
public class NewsDailyDigestItemDO {

    /**
     * 主键 ID，数据库自增（BIGSERIAL）
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属刊头（t_news_daily_digest.id，ON DELETE CASCADE 随刊头重建带走）
     */
    private Long digestId;

    /**
     * 溯源 t_news_item.id（无外键：90 天保留清理不得连带；读取期仅当源行
     * 仍存在且 status 不为 published 时过滤失格——正常清理后的缺失行保留展示）
     */
    private Long itemId;

    /**
     * 刊内序（1 起）：确定性选材冻结口径=publish_time DESC, id DESC 的全部动态序
     */
    private Integer seq;

    /**
     * 原文 URL 快照（永久外链）
     */
    private String url;

    /**
     * sha256(url) 快照（url_hash）
     */
    private String urlHash;

    /**
     * 标题（中文）快照
     */
    private String titleZh;

    /**
     * 标题（英文）快照
     */
    private String titleEn;

    /**
     * AI 摘要（中文）快照
     */
    private String summaryZh;

    /**
     * AI 摘要（英文）快照
     */
    private String summaryEn;

    /**
     * 固定 8 类+other 快照
     */
    private String category;

    /**
     * 主题 slug 逗号分隔快照（生成时刻 t_news_item_topic 关联）
     */
    private String topicSlugs;

    /**
     * 信源 ID 快照（无外键）
     */
    private Long sourceId;

    /**
     * 信源 source_key 快照（前端信源注册表回链键）
     */
    private String sourceKey;

    /**
     * 信源平台快照（official/youtube/prn/events）
     */
    private String sourcePlatform;

    /**
     * 信源是否官网快照
     */
    private Boolean sourceOfficial;

    /**
     * 信源展示名（中文）快照
     */
    private String sourceDisplayName;

    /**
     * 信源展示名（英文）快照
     */
    private String sourceDisplayNameEn;

    /**
     * 原文发布时间快照（窗口归属判定的锚）
     */
    private Date publishTime;

    /**
     * 发布时间精度冗余（#275）：date=快照时刻为归期代表值（只显日期）/
     * datetime=真实瞬时 / unknown=旧快照（不回填）
     */
    private String publishTimePrecision;
}

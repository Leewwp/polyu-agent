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

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 资讯条目实体
 *
 * <p>一 URL 一行，url_hash=sha256(url) 幂等去重唯一键；双语标题/摘要由 LLM 管线产出。
 * 资讯流与 RAG 证据面物理隔离：本表不被检索链路读取（边界维持原则）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_news_item")
public class NewsItemDO {

    /**
     * 主键 ID，数据库自增（BIGSERIAL）
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 来源信源 ID（t_news_source.id）
     */
    private Long sourceId;

    /**
     * 原文 URL（永久外链，卡片直指官方原文）
     */
    private String url;

    /**
     * sha256(url) 十六进制，幂等去重唯一键
     */
    private String urlHash;

    /**
     * 标题（中文）
     */
    private String titleZh;

    /**
     * 标题（英文）
     */
    private String titleEn;

    /**
     * AI 摘要（中文，≤80 字）
     */
    private String summaryZh;

    /**
     * AI 摘要（英文，≤60 词）
     */
    private String summaryEn;

    /**
     * 固定 8 类：admission/scholarship/research/campus/event/career/exchange/admin（+other 兜底）
     */
    private String category;

    /**
     * 原文语言（en/zh-Hant/zh-Hans）
     */
    private String langRaw;

    /**
     * 原文发布时间
     */
    private Date publishTime;

    /**
     * 抓取入库时间
     */
    private Date fetchTime;

    /**
     * published=展示；hidden=人工抽检应急下架（admin 最小端点）
     */
    private String status;

    /**
     * 热度分（热度模型：Σ信源权重+覆盖源数，24h 半衰期）
     */
    private Integer heat;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;
}

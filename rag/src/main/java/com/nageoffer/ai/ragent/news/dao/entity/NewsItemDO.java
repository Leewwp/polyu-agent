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
     * 处理状态五态（#185，语义见 {@link NewsItemStatus}）：
     * pending/published/archived/expired/hidden
     */
    private String status;

    /**
     * 热度分（热度模型：Σ信源权重+覆盖源数，24h 半衰期）
     */
    private Integer heat;

    /**
     * 发布资格就绪时刻（#185）：合格摘要落库或明示零调用回退的时间；
     * 发布门 180s 从本字段起算（查询侧统一判据）。NULL=#185 之前的历史行，
     * 资格视同早已就绪（历史不重算）
     */
    private Date eligibleTime;

    /**
     * 摘要产出方式（#185）：llm=LLM 富化；fallback=明示零调用回退（标题派生）；
     * NULL=#185 之前的历史行
     */
    private String summarySource;

    /**
     * 产出摘要所用提示词模板版本（#185）：sha256(模板全文) 前 12 位。
     * 改词即版本变化，只影响新资料，历史行不自动重算；fallback 无提示词为 NULL
     */
    private String promptVersion;

    /**
     * 富化判重内容哈希（#187 判重三合同之二）：sha256(规范化标题+正文摘录)——
     * 同哈希且供体 summary_source=llm 时富化阶段零调用复用摘要（两行独立保留=
     * 逐源证据）；NULL=未富化或无正文（YouTube 跳过正文抽取，不参与复用）。
     * 与 #184 请求指纹的边界：指纹含 source_name/动态词表（同渲染请求才复用），
     * 内容哈希跨源同内容也复用；只承诺确切重复复用，不承诺节省比例。
     */
    private String contentHash;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;
}

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
import java.util.Locale;

/**
 * 主题别名账实体（#202 防再提）
 *
 * <p>merged/rejected 提案 name_zh/name_en 的规范化映射：富化提案消费点
 * （{@code NewsEnrichService#linkTopics}）在词表未命中后、{@code createProposal}
 * 之前查本表——命中 <b>不再落新提案行</b>（幂等拦截）：merged 别名直接回链
 * {@code targetTopicId} 的 curated 主题；rejected 别名跳过不挂关联。
 *
 * <p>同键入账语义：同一提案 name_zh/name_en 规范化后同键只入一行；
 * 人工改判（如 reject 改 merge）时按 {@code aliasKey} 覆盖更新（action/
 * target/source/reason 随最新处置刷新）——账面始终反映最新裁决。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_news_topic_alias")
public class NewsTopicAliasDO {

    /**
     * 入账动作：并入近义 curated 主题（target 必填）
     */
    public static final String ACTION_MERGED = "merged";

    /**
     * 入账动作：弃（target 为 NULL）
     */
    public static final String ACTION_REJECTED = "rejected";

    /**
     * 主键 ID，数据库自增（BIGSERIAL）
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 规范化别名键：lower(Locale.ROOT)+去全部空白——与提案消费点同一 normalize 口径
     */
    private String aliasKey;

    /**
     * 别名原始形态（规范化前的原文，审计可读）
     */
    private String aliasDisplay;

    /**
     * 入账动作：merged（target 必填）/ rejected（target 为 NULL）
     */
    private String action;

    /**
     * 被处置的提案行 t_news_topic.id（软状态行保留，供处置明细回溯）
     */
    private Long sourceTopicId;

    /**
     * merged 动作的并入目标 curated 主题 id；rejected 为 NULL
     */
    private Long targetTopicId;

    /**
     * 处置操作者（admin 账号名）
     */
    private String operator;

    /**
     * 处置理由（留痕可复核）
     */
    private String reason;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    /**
     * 更新时间（人工改判覆盖更新的时刻）
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    /**
     * 别名键规范化：lower(Locale.ROOT)+去全部空白；空白输入返回 null（不入账）。
     * 与 NewsEnrichService 提案消费点的 normalize 同一口径——两侧必须一致，
     * 否则拦截失效（IT 断言消费点口径与入账口径互证）
     */
    public static String normalizeKey(String raw) {
        if (raw == null) {
            return null;
        }
        String key = raw.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        return key.isEmpty() ? null : key;
    }
}

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
 * 主题治理留痕事件实体（#202，append-only）
 *
 * <p>三轨处置（merged/promoted/rejected）逐行留痕：detail 含关联迁移/摘除计数与
 * slug 变更明细。当前态在 {@code t_news_topic.status}（可 UPDATE），本表只增不改——
 * 沿 #186 {@code t_news_source_health_event} 的「当前态与审计流水分离」形态。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_news_topic_governance_event")
public class NewsTopicGovernanceEventDO {

    /**
     * 事件动作：merged=并入 / promoted=转正 / rejected=弃
     */
    public static final String ACTION_MERGED = "merged";
    public static final String ACTION_PROMOTED = "promoted";
    public static final String ACTION_REJECTED = "rejected";

    /**
     * 主键 ID，数据库自增（BIGSERIAL）
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 被处置的主题行 t_news_topic.id
     */
    private Long topicId;

    /**
     * 处置动作（本类 ACTION_* 常量）
     */
    private String action;

    /**
     * merge 动作的并入目标 curated 主题 id；promote/reject 为 NULL
     */
    private Long targetTopicId;

    /**
     * 处置明细（迁移关联数/摘除关联数/旧→新 slug/阈值依据，超长截断 500 字符）
     */
    private String detail;

    /**
     * 处置操作者（admin 账号名；本地回放为 replay 标记）
     */
    private String operator;

    /**
     * 事件时刻（业务时钟）
     */
    private Date eventTime;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;
}

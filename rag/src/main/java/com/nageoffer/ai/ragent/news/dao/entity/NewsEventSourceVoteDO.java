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
 * 事件独立来源投票账实体（#187）：(event_id, independence_group) 一行一票——
 * 同机构多 feed、聚合口与原媒体的独立性经 {@link NewsSourceDO#getIndependenceGroup()}
 * 显式映射，不重复加票（CLU-004 口径：PRN 中英双 wire 同一机构多渠道分发计一票）。
 *
 * <p>vote_count 只记录组内参与条目数（计数不放大票权）；每轮重归组后按成员
 * 全量重建（delete+insert，投票账非 append-only——身份审计走迁移账）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_news_event_source_vote")
public class NewsEventSourceVoteDO {

    /**
     * 主键 ID，数据库自增（BIGSERIAL）
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属事件（t_news_event.id）
     */
    private Long eventId;

    /**
     * 独立来源组（t_news_source.independence_group 语义）
     */
    private String independenceGroup;

    /**
     * 组内参与条目数（仅计数，票恒为 1）
     */
    private Integer voteCount;

    /**
     * 组内最早证据时刻（成员 publish_time 最小值）
     */
    private Date firstVoteTime;

    /**
     * 组内最晚证据时刻
     */
    private Date lastVoteTime;

    /**
     * 更新时间（每轮重建）
     */
    private Date updateTime;
}

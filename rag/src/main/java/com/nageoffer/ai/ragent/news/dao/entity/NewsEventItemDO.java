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
 * 事件参与者证据实体（#187）：一条目至多属一事件（item_id 唯一键）。
 *
 * <p>重归组改写 event_id 并落 {@link NewsEventMigrationDO}（kind=regroup/split）；
 * 成员条目转终态（hidden/archived/expired）时摘除本行并落 detach 迁移——
 * 下架处理事件证据与公开投影（公开面按条目 status 过滤天然摘除，事件侧不再计票）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_news_event_item")
public class NewsEventItemDO {

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
     * 参与条目（t_news_item.id；全表唯一——一条目至多属一事件）
     */
    private Long itemId;

    /**
     * 条目信源 ID（入组时快照）
     */
    private Long sourceId;

    /**
     * 入组时 t_news_source.independence_group 快照（源映射变更不回溯历史证据；
     * NULL 源按 source_key 语义已在此解析为具体组名）
     */
    private String independenceGroup;

    /**
     * 成员条目 publish_time=参与者证据时刻；热度票资格=∈[事件 first_report_time, +48h]
     */
    private Date publishTime;

    /**
     * 首次入组时刻
     */
    @TableField(fill = FieldFill.INSERT)
    private Date joinedTime;
}

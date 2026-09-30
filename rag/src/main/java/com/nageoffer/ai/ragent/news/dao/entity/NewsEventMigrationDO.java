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
 * 事件身份迁移账本实体（#187，append-only 审计）
 *
 * <p>四类迁移：merge=事件级合并（旧事件 superseded，item_id 空）/ split=分裂迁出
 * （原 ID 留给含最早成员的确定原组，被迁出条目落新事件并在此逐条记录）/
 * regroup=条目在两个存活事件间移动 / detach=条目转终态（hidden/archived/expired）
 * 摘除参与者证据（new_event_id 空）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_news_event_migration")
public class NewsEventMigrationDO {

    /**
     * 迁移类型：事件级合并（旧→存续，item_id 空）
     */
    public static final String KIND_MERGE = "merge";

    /**
     * 迁移类型：分裂迁出（原 ID 留确定原组，条目落新事件）
     */
    public static final String KIND_SPLIT = "split";

    /**
     * 迁移类型：条目在存活事件间改组
     */
    public static final String KIND_REGROUP = "regroup";

    /**
     * 迁移类型：条目终态摘除参与者证据（下架/过期）
     */
    public static final String KIND_DETACH = "detach";

    /**
     * 主键 ID，数据库自增（BIGSERIAL）
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 迁出事件（旧事件 ID）
     */
    private Long oldEventId;

    /**
     * 迁入事件（存续/新事件 ID；detach 为 NULL）
     */
    private Long newEventId;

    /**
     * merge=merge/split/regroup/detach
     */
    private String kind;

    /**
     * 迁移条目（split/regroup/detach 携带；merge 为 NULL）
     */
    private Long itemId;

    /**
     * 判定依据摘要（确定性文本，不含摘要内容）
     */
    private String reason;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;
}

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
 * 资讯事件持久身份实体（#187 最小模型：只做身份/证据/热度——无综述/事件页/向量/评分）
 *
 * <p>身份规则（父票 #180 §3）：未发生合并/分裂的同事件 ID 稳定；合并选一个存续 ID
 * （最早首报，平手取小 id）并经 {@link NewsEventMigrationDO}（kind=merge）记录
 * 旧→存续，非存续方转 {@link #STATUS_SUPERSEDED}（行保留审计，不再持有成员）；
 * 分裂时原 ID 留给含最早成员的确定原组，迁出条目落新事件并记迁移——不要求多个
 * 旧事件合并后所有旧 ID 同时作为同一实体不变。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_news_event")
public class NewsEventDO {

    /**
     * 事件状态（status 列）：现行事件
     */
    public static final String STATUS_ACTIVE = "active";

    /**
     * 事件状态（status 列）：已并入存续事件（合并非存续方；迁移账可查）
     */
    public static final String STATUS_SUPERSEDED = "superseded";

    /**
     * 主键 ID，数据库自增（BIGSERIAL）
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * active=现行；superseded=已并入存续事件（身份迁移见 t_news_event_migration）
     */
    private String status;

    /**
     * 事件热度=（48h 证据窗内独立来源组数+Σ组内最大源权重）×24h 半衰
     * （锚=firstReportTime，未来封顶 1）；每轮重归组同步写成员条目 heat
     */
    private Integer heat;

    /**
     * 事件首报=成员最早 publish_time；24h 半衰与 48h 投票证据窗的共同锚点
     */
    private Date firstReportTime;

    /**
     * 最晚成员 publish_time
     */
    private Date lastActivityTime;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;
}

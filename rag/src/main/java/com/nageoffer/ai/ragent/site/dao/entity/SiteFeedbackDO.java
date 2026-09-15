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

package com.nageoffer.ai.ragent.site.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 站点反馈实体（doc 25）：匿名访客反馈箱，不设 user_id——公开前缀进
 * PUBLIC_EXCLUDE_PATTERNS 后 UserContext 拦截器整体跳过，该列恒 NULL 属死列；
 * 命名避开上游消息维度反馈（MessageFeedback 系列与 t_message_feedback 表）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_site_feedback")
public class SiteFeedbackDO {

    /**
     * 主键 ID，数据库自增（BIGSERIAL）
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 反馈内容（10–2000 字）
     */
    private String content;

    /**
     * 选填联系方式（≤100 字）
     */
    private String contact;

    /**
     * 提交方客户端 IP（XFF 首值口径，限流与滥用排查用；admin 列表脱敏展示）
     */
    private String clientIp;

    /**
     * 状态：0 未处理 / 1 已处理 / 2 忽略
     */
    private Integer status;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    /**
     * 逻辑删除
     */
    @TableLogic
    private Integer deleted;
}

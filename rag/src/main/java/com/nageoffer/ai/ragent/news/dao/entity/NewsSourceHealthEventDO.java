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
 * 信源健康事件流水（#186：停止/复归记录可查）
 *
 * <p>append-only 审计：自动隔离（isolated）/ 策略转停（policy_disabled）/
 * 探活通过（probe_pass）/ 探活失败（probe_fail）/ 探活复归（recovered）。
 * 人工停用/人工启用由维护者 SQL 直改（无代码路径），不落本表——以
 * t_news_source.disabled_reason='manual' 与 admin 面板为准。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_news_source_health_event")
public class NewsSourceHealthEventDO {

    /**
     * 事件类型：自动隔离（连续 3 败转停，reason=auto）
     */
    public static final String TYPE_ISOLATED = "isolated";

    /**
     * 事件类型：策略转停（robots/出站守卫拒绝，reason=policy）
     */
    public static final String TYPE_POLICY_DISABLED = "policy_disabled";

    /**
     * 事件类型：探活通过（一次有效完整成功，streak +1 未达复归阈值）
     */
    public static final String TYPE_PROBE_PASS = "probe_pass";

    /**
     * 事件类型：探活失败（结构失配/网络失败，streak 清零）
     */
    public static final String TYPE_PROBE_FAIL = "probe_fail";

    /**
     * 事件类型：探活复归（连续两次有效完整成功 → enabled=true）
     */
    public static final String TYPE_RECOVERED = "recovered";

    /**
     * 主键 ID，数据库自增（BIGSERIAL）
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 关联信源（t_news_source.id）
     */
    private Long sourceId;

    /**
     * 事件类型（TYPE_* 常量）
     */
    private String eventType;

    /**
     * 触发事件的单轮结果（六类代码，NewsFetchOutcome.code()；隔离/转停/探活事件均留判定依据）
     */
    private String outcome;

    /**
     * 判定依据摘要（失败原因文本/成功计数等）
     */
    private String detail;

    /**
     * 事件时刻
     */
    private Date eventTime;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;
}

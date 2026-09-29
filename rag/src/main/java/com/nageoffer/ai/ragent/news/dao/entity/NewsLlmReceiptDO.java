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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;

/**
 * 资讯 LLM 付费回执实体（#184 最小付费回执）
 *
 * <p>一行=一次逻辑请求（请求指纹唯一）：先落库再用（响应原文入 response_text，
 * 业务落库失败后同指纹重跑直接复用、不重复付费）；attempts 按真实发出次数
 * write-ahead 递增（含 fallback 与网关重试），进程中途退出时已记部分保守保留
 * （不承诺绝对不重复计费）。日/月预算消耗=按 stat_date/stat_month 聚合本表，
 * 重启不清零。预算耗尽被降级的请求记 status=DEGRADED（次日补偿成功后翻转）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_news_llm_receipt")
public class NewsLlmReceiptDO {

    /**
     * 主键 ID，数据库自增（BIGSERIAL）
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 请求指纹：sha256(实际渲染提示词全文+模型+temperature/topP/maxTokens)，全局唯一
     */
    private String requestFingerprint;

    /**
     * 本行发出所处日（HKT 日切）——预算按日聚合口径。
     * <p>类型映射（修正点6，2026-09-29 本地 polyu-pg 实证）：PG DATE 列与 Java String
     * 经 PgJDBC setString 双向不兼容（INSERT 报 "column is of type date but expression
     * of type character varying"、等值比较报 "operator does not exist: date = character
     * varying"，三套环境 URL 均无 stringtype=unspecified 兜底）——改用
     * {@link LocalDate} 走 mybatis LocalDateTypeHandler 原生 DATE 通道（实证通过）。
     * 周期键随行落定后不改写（修正点3）
     */
    private LocalDate statDate;

    /**
     * 最近发出所处月（yyyy-MM，HKT）——预算按月聚合口径
     */
    private String statMonth;

    /**
     * 指纹成分中的模型（Tier.FAST 主选 id，配置期口径）
     */
    private String modelId;

    /**
     * 实际服务模型（最后一次成功发出的目标 id；未成功为 NULL）
     */
    private String servedModelId;

    /**
     * 真实发出次数（含路由 fallback 与网关重试，write-ahead 保守口径）
     */
    private Integer attempts;

    /**
     * 网关层逻辑重试次数（attempts 的子集口径，供双口径核对）
     */
    private Integer retries;

    /**
     * 估算成本（元）= attempts × 单次封顶单价（默认 ¥0.005，5k 入+1k 出 flash 原价）
     */
    private BigDecimal costEstimate;

    /**
     * 成功响应原文（先落库再用：同指纹重跑复用，不重复付费）
     */
    private String responseText;

    /**
     * PENDING=发出中；SUCCESS=已回执；DEGRADED=预算耗尽降级（次日补偿）；FAILED=重试耗尽
     */
    private String status;

    /**
     * 最近一次失败原因摘要（诊断用，不含提示词与响应内容）
     */
    private String errorBrief;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;
}

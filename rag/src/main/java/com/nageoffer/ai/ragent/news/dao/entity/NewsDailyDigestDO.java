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

import java.time.LocalDate;
import java.util.Date;

/**
 * 资讯日报刊头实体（#212，父票 #182 r3 §日报）
 *
 * <p>一 HKT 日期一刊（digest_date 唯一键）：窗口=[D-1 08:00, D 08:00) 左闭右开，
 * digest_date=D（窗口闭端日）。幂等重建=按日期先删后插（快照行经
 * t_news_daily_digest_item.digest_id 外键 ON DELETE CASCADE 随刊头带走），
 * 重跑只产一刊。导语只有刊头一份，条目内容冗余在快照行表——
 * 本表与快照表对 t_news_item/t_news_source 均不建外键（90 天保留清理不连带）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_news_daily_digest")
public class NewsDailyDigestDO {

    /**
     * 主键 ID，数据库自增（BIGSERIAL）
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * HKT 日报日期=窗口闭端日（唯一键）。PG DATE 列经 mybatis LocalDateTypeHandler
     * 原生映射（String 绑定在 PgJDBC 下双向不兼容，#184 修正点6 实证先例）
     */
    private LocalDate digestDate;

    /**
     * 窗口起点（D-1 08:00 HKT，含）
     */
    private Date windowStart;

    /**
     * 窗口闭端（D 08:00 HKT，不含——publish_time 恰落 08:00:00.000 归下一期）
     */
    private Date windowEnd;

    /**
     * 导语（中文）：LLM 单次调用产出，或失败/预算耗尽/空刊的固定模板
     */
    private String introZh;

    /**
     * 导语（英文）
     */
    private String introEn;

    /**
     * 导语产出方式：llm=预算护栏内单次调用（t_news_llm_receipt 记账）；
     * fallback=LLM 失败/预算耗尽固定模板（零新增调用）；empty=空刊模板（零调用）
     */
    private String introSource;

    /**
     * 生成时刻的快照条数（读取期主动下架复检后的可见条数以接口实时过滤为准）
     */
    private Integer itemCount;

    /**
     * published=现行刊；rebuild_pending=预留降级态（当前默认回退路径=读取期
     * 零调用过滤+模板导语，不落本状态）
     */
    private String status;

    /**
     * 本刊生成时刻
     */
    private Date buildTime;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * intro_source 取值（语义常量，沿 NewsItemStatus.SUMMARY_SOURCE_* 先例）
     */
    public static final String INTRO_SOURCE_LLM = "llm";
    public static final String INTRO_SOURCE_FALLBACK = "fallback";
    public static final String INTRO_SOURCE_EMPTY = "empty";

    /**
     * status 取值
     */
    public static final String STATUS_PUBLISHED = "published";
    public static final String STATUS_REBUILD_PENDING = "rebuild_pending";
}

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

/**
 * 日报校园活动版面快照实体（t_news_daily_digest_activity，#330——父票 #317
 * 校园活动版面，总纲 #315 线一 L2「进行中/即将来临」）。
 *
 * <p><b>快照独立性红线</b>（沿 {@link NewsDailyDigestItemDO}/
 * {@link NewsDailyDigestKeyDateDO}）：item_id 只作溯源，<b>不建外键</b>——
 * t_news_item 行被 90 天保留清理删除不连带；标题/起止日期/链接等展示字段
 * 全部冗余快照，读取面零 LLM、零回查 t_news_item。(digest_id, item_id)
 * 唯一=同刊内一活动一行。
 *
 * <p>选材/ongoing 口径见建表迁移注释（as-of=刊日，生成期冻结，不随读取
 * 时刻漂移；数据源=NewsActivityQueryService 活动实体投影——#323 模型，
 * events 现为唯一活跃源，CPEO/SAO 入库后自动汇入无需改版面）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_news_daily_digest_activity")
public class NewsDailyDigestActivityDO {

    /**
     * 主键 ID，数据库自增（BIGSERIAL）
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属刊头（t_news_daily_digest.id，ON DELETE CASCADE 随刊头重建带走）
     */
    private Long digestId;

    /**
     * 溯源 t_news_item.id（无外键：保留清理删除不连带快照）
     */
    private Long itemId;

    /**
     * 版面内序（1 起，确定性：date_start 升序、item_id 兜底；超容量取最近）
     */
    private Integer seq;

    private String titleZh;

    private String titleEn;

    /**
     * 详情页永久外链（卡片外链语义）
     */
    private String url;

    /**
     * 活动开始日（HKT 历日；#323 模型 publish_time=活动开始语义）
     */
    private LocalDate dateStart;

    /**
     * 活动结束日（HKT 历日；activity_end_time 含端代表值投影）
     */
    private LocalDate dateEnd;

    /**
     * 进行中=开始日 &lt; 刊日 且 结束日 &gt;= 刊日（当日开始归「即将来临」，
     * 与 L1 关键日期「当日开始不标进行中」同口径）
     */
    private Boolean ongoing;
}

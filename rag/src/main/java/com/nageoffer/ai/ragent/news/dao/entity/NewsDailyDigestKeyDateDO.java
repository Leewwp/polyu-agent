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
 * 日报校历关键日期栏目快照实体（t_news_daily_digest_key_date，#316——总纲
 * #315 线一 L1「未来 N 天关键日期与截止提醒」）。
 *
 * <p><b>快照独立性红线</b>（沿 {@link NewsDailyDigestItemDO}）：key_date_id/uid
 * 只作溯源，<b>不建外键</b>——t_key_date 行 withdrawn/archived 不连带；标题/
 * 日期/精度/人群限制等展示字段全部冗余快照，读取面零 LLM、零回查 t_key_date。
 * (digest_id, key_date_id) 唯一=同刊内一事件一行。
 *
 * <p>选材/ongoing/days_until 口径见建表迁移注释（as-of=刊日，生成期冻结，
 * 不随读取时刻漂移）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_news_daily_digest_key_date")
public class NewsDailyDigestKeyDateDO {

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
     * 溯源 t_key_date.id（无外键：withdrawn/归档不连带快照）
     */
    private Long keyDateId;

    /**
     * 栏内序（1 起，确定性：date_start 升序、uid 兜底；超容量取最近）
     */
    private Integer seq;

    /**
     * t_key_date 语义身份（六段数组 SHA-256 hex，溯源用）
     */
    private String uid;

    private String titleZh;

    private String titleEn;

    /**
     * 官方人群限制原文（不得省略，随行快照）
     */
    private String audienceText;

    /**
     * exact-day / exact-range / onwards（fuzzy 行 date_* 全 NULL 不落窗）
     */
    private String precision;

    /**
     * 落窗条目必有 date_start（t_key_date 约束：date_end 非空 ⇒ date_start 非空）
     */
    private LocalDate dateStart;

    /**
     * 仅 exact-range
     */
    private LocalDate dateEnd;

    /**
     * 模糊窗原文桶（onwards/exact 不使用；随行快照保形）
     */
    private String fuzzyHint;

    /**
     * 已开始未结束（date_start &lt; 刊日 且 有效结束日 &gt;= 刊日）
     */
    private Boolean ongoing;

    /**
     * 刊日→date_start 天数（0=当日开始；负=已开始区间，展示层 ongoing 徽章
     * 优先）；倒计时门=仅 exact-day/exact-range，onwards 恒 null
     */
    private Integer daysUntil;
}

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

package com.nageoffer.ai.ragent.calendar;

import com.nageoffer.ai.ragent.calendar.dao.entity.KeyDateDO;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 校历关键日期展示语义三件套（#343 单一源）：有效结束日 / 进行中判定 /
 * 倒计时。实时看板（KeyDateQueryServiceImpl.board/toVO）与日报装配
 * （NewsDailyDigestServiceImpl.toKeyDateSnapshot）共用同一口径——后续
 * 语义调整只改本类，两侧自动同口径（原先两处私有副本靠注释互指承诺，
 * 存在只改一处即静默分叉的漂移风险）。
 *
 * <p><b>倒计时门（合同§4）</b>：daysUntil 仅 exact-day/exact-range 计算；
 * onwards（开放起点）/fuzzy（模糊窗）不伪造精确截止语义，恒 null。
 */
public final class KeyDateSemantics {

    private static final String PRECISION_EXACT_DAY = "exact-day";
    private static final String PRECISION_EXACT_RANGE = "exact-range";

    private KeyDateSemantics() {
    }

    /**
     * 有效结束日（date_end ?? date_start——exact-day/onwards 无结束列，
     * 过期判定与倒序排列统一走本口径）
     */
    public static LocalDate effectiveEnd(KeyDateDO row) {
        return row.getDateEnd() != null ? row.getDateEnd() : row.getDateStart();
    }

    /**
     * 进行中判定：已开始（date_start &lt; asOf）未结束（有效结束日 &gt;= asOf）；
     * 当日开始不算进行中（归「即将来临」）。date_start 非空由调用点前置
     * 分桶/落窗过滤保证（fuzzy 行不进日期语义面）
     */
    public static boolean isOngoing(KeyDateDO row, LocalDate asOf) {
        return row.getDateStart().isBefore(asOf) && !effectiveEnd(row).isBefore(asOf);
    }

    /**
     * 倒计时门：仅 exact-day/exact-range 计 asOf→date_start 天数（负=已过，
     * 供 ongoing 徽章与 recent 段「已过 N 天」文案）；onwards/fuzzy 不伪造
     * 截止语义恒 null；date_start 为空（fuzzy 行）恒 null
     */
    public static Integer daysUntil(KeyDateDO row, LocalDate asOf) {
        Integer daysUntil = null;
        if (PRECISION_EXACT_DAY.equals(row.getPrecision()) || PRECISION_EXACT_RANGE.equals(row.getPrecision())) {
            daysUntil = row.getDateStart() == null ? null : (int) ChronoUnit.DAYS.between(asOf, row.getDateStart());
        }
        return daysUntil;
    }
}

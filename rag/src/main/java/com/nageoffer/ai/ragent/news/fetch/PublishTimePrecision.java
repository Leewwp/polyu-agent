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

package com.nageoffer.ai.ragent.news.fetch;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * 发布时间精度标记（#275）：贯穿发现（RawNewsItem）、资讯行（t_news_item）、
 * 公开 VO 与新日报快照。
 *
 * <ul>
 *   <li>{@link #DATE}=只有日期证据：归期代表值取该日 <b>23:59:59 HKT</b>（落
 *       [D 08:00, D+1 08:00) 窗口归 D+1 刊）——代表值不是真实发布时间，
 *       展示层只显示日期；</li>
 *   <li>{@link #DATETIME}=真实瞬时证据（RSS pubDate / lib HKT HH:mm /
 *       PRN HH:mm ET 换算），保留实际时刻；</li>
 *   <li>{@link #UNKNOWN}=旧行/旧快照（本批前历史）与非本批精确化路径的产出
 *       （sitemap lastmod、events start-date 含义不改）——不猜测、不回填。</li>
 * </ul>
 *
 * <p>窗口边界口径（左闭右开保持）：D 日 07:59:59(.999) 属 D 刊；D 日 08:00:00
 * 起（含 .001）属 D+1 刊；date-only 代表值 23:59:59 属 D+1 刊。
 */
public final class PublishTimePrecision {

    public static final String DATE = "date";
    public static final String DATETIME = "datetime";
    public static final String UNKNOWN = "unknown";

    /** 官网类 date-only 证据的站点时区（与 NewsHtmlListParser 口径一致） */
    static final ZoneId SITE_ZONE = ZoneId.of("Asia/Hong_Kong");

    private PublishTimePrecision() {
    }

    /**
     * date-only 证据的归期代表值：D 23:59:59 HKT（秒粒度；窗口左闭右开下
     * 稳落 D+1 刊，不会越过日末）
     */
    public static Date dateOnlyRepresentative(LocalDate date) {
        return Date.from(date.atTime(23, 59, 59).atZone(SITE_ZONE).toInstant());
    }

    /** 精度归一：null/空白按 unknown（旧行为兼容），小写化 */
    public static String normalize(String precision) {
        if (precision == null || precision.isBlank()) {
            return UNKNOWN;
        }
        return precision.strip().toLowerCase();
    }

    /** 展示/入列前校验：非三值之一的输入按 unknown 兜底 */
    public static String orUnknown(String precision) {
        String normalized = normalize(precision);
        return DATE.equals(normalized) || DATETIME.equals(normalized) ? normalized : UNKNOWN;
    }

    /** HKT 时刻构造（lib HH:mm 路径） */
    static Date hktInstant(LocalDate date, int hour, int minute) {
        return Date.from(LocalDateTime.of(date, java.time.LocalTime.of(hour, minute))
                .atZone(SITE_ZONE).toInstant());
    }
}

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

package com.nageoffer.ai.ragent.news.service.impl;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 日报固定模板导语（#212）——<b>零模型调用</b>的确定性文案：
 * 生成期（空刊 empty / LLM 失败或预算耗尽 fallback）与读取期（主动下架后
 * 导语失格回退）共用同一组模板，保证「预算耗尽也能出刊」「下架后无导语残留」
 * 两条验收的回退路径始终零调用、可预期。
 *
 * <p>模板只含日期与计数等机械事实，不含任何条目内容（无法残留被下架内容）。
 */
public final class NewsDailyDigestTemplates {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 资讯管线统一时区（HKT +08:00，沿 NewsFetchJob 先例；调度/生成/读取共用）
     */
    public static final ZoneId HKT_ZONE = ZoneId.of("Asia/Hong_Kong");

    private NewsDailyDigestTemplates() {
    }

    /**
     * 回退模板导语（LLM 失败/预算耗尽/读取期失格，可见条数&gt;0）：
     * 只报窗口与可见条数两个机械事实
     */
    static String fallbackIntroZh(LocalDate digestDate, int visibleCount) {
        return String.format("本期日报覆盖 %s 至 %s 的公开动态，共 %d 条，以下按发布时间倒序排列。",
                digestDate.minusDays(1).format(DATE_FORMAT), digestDate.format(DATE_FORMAT), visibleCount);
    }

    static String fallbackIntroEn(LocalDate digestDate, int visibleCount) {
        return String.format("This digest covers public updates from %s to %s — %d items, listed newest first.",
                digestDate.minusDays(1).format(DATE_FORMAT), digestDate.format(DATE_FORMAT), visibleCount);
    }

    /**
     * 空刊模板导语（窗口内无可见条目，或读取期复检后全部失格）
     */
    static String emptyIntroZh(LocalDate digestDate) {
        return String.format("本期日报（%s 覆盖窗口）内暂无公开动态。", digestDate.format(DATE_FORMAT));
    }

    static String emptyIntroEn(LocalDate digestDate) {
        return String.format("No public updates in this digest window (covering %s).",
                digestDate.format(DATE_FORMAT));
    }
}

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

package com.nageoffer.ai.ragent.calendar.parse;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 校历逐源词表与固定常量（合同§2 五源 URL + §3 语义码映射，封闭代码集）。
 *
 * <p>解析器只允许把已知标签及别名映射为代码——不得基于词面相似度或日期邻近
 * 猜身份（合同§3）。词表未命中的业务候选一律 UNKNOWN→整源退化，没有「相近词
 * 兜底」。扩展词表=修改本类+补期望账本行，属显式合同变更。
 */
public final class CalendarLexicon {

    private CalendarLexicon() {
    }

    /**
     * 五源固定 URL（合同§2 表；写者四+纯校验源一）
     */
    public static final Map<String, String> SOURCE_URLS = Map.of(
            "cal-academic-calendar", "https://www.polyu.edu.hk/ar/students-in-taught-programmes/academic-calendar/",
            "cal-fee-payment-annual", "https://www.polyu.edu.hk/ar/students-in-taught-programmes/annual-schedules/fee-payment/",
            "cal-timetable-exam-results", "https://www.polyu.edu.hk/ar/students-in-taught-programmes/annual-schedules/timetable-exam-assessment/",
            "cal-exam-timetable", "https://www.polyu.edu.hk/ar/students-in-taught-programmes/examination-information/examination-timetable-and-arrangements/",
            "cal-assessment-results", "https://www.polyu.edu.hk/ar/students-in-taught-programmes/examination-information/assessment-results/");

    /**
     * 纯校验源（verifier，零事件写径；合同§2 与 t_key_date_source.role 同口径）。
     * 门禁 writer 空集下限（#195 审核修正防线 B）只对 writer 源生效——verifier 源
     * WRITE=0 是合法状态（零写径即其合同）。放词表而非解析器接口：SOURCE_URLS/
     * AY_PROSE 同为按 source_key 承载的源级固定常量，单一事实源两处 evaluate 复用
     */
    public static final Set<String> VERIFIER_SOURCES = Set.of("cal-exam-timetable");

    /**
     * 学期措辞→学期码（AY/S1/S2/SU；AY=学年级事件如假期/学年结束）
     */
    public static final Map<String, String> TERM_MAP = Map.of(
            "Semester One", "S1", "Semester Two", "S2", "Summer Term", "SU");

    /**
     * 已识别假期标签→稳定语义码（合同§2 #1 写域；含弯引号变体——页面两种引号并存）
     */
    public static final Map<String, String> HOLIDAY_CODES = Map.ofEntries(
            Map.entry("The day following the Chinese Mid-Autumn Festival", "mid-autumn-following"),
            Map.entry("National Day", "national-day"),
            Map.entry("The day following Chung Yeung Festival", "chung-yeung-following"),
            Map.entry("Christmas Day", "christmas-day"),
            Map.entry("The first weekday after Christmas Day", "christmas-following"),
            Map.entry("The first day of January", "first-january"),
            Map.entry("Lunar New Year\u2019s Day", "lny-day"),
            Map.entry("Lunar New Year's Day", "lny-day"),
            Map.entry("The second day of Lunar New Year", "lny-second"),
            Map.entry("The third day of Lunar New Year", "lny-third"),
            Map.entry("Good Friday", "good-friday"),
            Map.entry("The day following Good Friday", "good-friday-following"),
            Map.entry("Easter Monday", "easter-monday"),
            Map.entry("Ching Ming Festival", "ching-ming"),
            Map.entry("Labour Day", "labour-day"),
            Map.entry("The Birthday of the Buddha", "buddha-birthday"),
            Map.entry("The HKSAR Establishment Day", "hksar-establishment"),
            Map.entry("Tuen Ng Festival", "tuen-ng"));

    /**
     * 教学暂停日标签→语义码（写域扩展，2026-09-29 裁决①批准计入正式写域）
     */
    public static final Map<String, String> SUSPENSION_CODES = Map.of(
            "Chinese Mid-Autumn Festival", "mid-autumn",
            "PolyU Undergraduate Info Day 2026", "ug-info-day",
            "Winter Solstice", "winter-solstice",
            "Christmas Eve", "christmas-eve",
            "New Year Eve", "new-year-eve",
            "Lunar New Year Eve", "lny-eve",
            "Lunar New Year Break", "lny-break");

    /**
     * SID 领取到期人群原文→受众码（实际领取日期不入键；受众码区分语义周期）
     */
    public static final Map<String, String> SID_AUDIENCE = Map.of(
            "students whose student identity cards are due to expire in end of August", "sid-exp-aug",
            "students whose student identity cards are due to expire in end of January", "sid-exp-jan",
            "students who will resume study in Summer Term", "sid-resume-su");

    /**
     * 每源页面级学年散文证据（page_ay 正则；无证据→整源退化，绝不用当前时钟推断）
     */
    public static final Map<String, Pattern> AY_PROSE = Map.of(
            // #1 主证据=表内 "Academic Year … ends" 行 + 互异年份段头（见解析器双证据）
            "cal-academic-calendar", Pattern.compile("Academic Year (\\d{4}/\\d{2}) ends"),
            "cal-fee-payment-annual", Pattern.compile("Student Identity Card for Academic Year (\\d{4}/\\d{2})"),
            "cal-timetable-exam-results", Pattern.compile("Assessment Results for Academic Year (\\d{4}/\\d{2})"),
            "cal-exam-timetable", Pattern.compile("Academic Year (\\d{4}/\\d{2})"),
            "cal-assessment-results", Pattern.compile("Academic Year (\\d{4}/\\d{2})"));

    /**
     * #2 括号对格式：[Notification: …; Payment Deadline: …] 标题（截去可选 Remarks）
     */
    public static final Pattern BRACKET_RE = Pattern.compile(
            "\\[Notification: (\\d{1,2} \\w+ \\d{4}); Payment Deadline: (\\d{1,2} \\w+ \\d{4})\\]\\s*(.+?)(?:\\s*\\[Remarks:.*)?$");

    /**
     * SID 领取行格式（含限定人群原文）
     */
    public static final Pattern SID_RE = Pattern.compile(
            "^(\\d{1,2} \\w+ \\d{4}) onwards Collection of Student Identity Card \\(For (.*?) only\\)$");

    /**
     * 开课行格式：d Month yyyy Commencement of Semester X, yyyy/yy——带自身学年标注
     */
    public static final Pattern COMMENCE_RE = Pattern.compile(
            "^(\\d{1,2} \\w+ \\d{4}) Commencement of (Semester One|Semester Two|Summer Term), (\\d{4}/\\d{2})$");
}

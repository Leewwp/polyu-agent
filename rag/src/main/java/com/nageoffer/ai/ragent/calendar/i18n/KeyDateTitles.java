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

package com.nageoffer.ai.ragent.calendar.i18n;

import com.nageoffer.ai.ragent.calendar.model.KeyDateCandidate;

import java.util.Map;

/**
 * 双语标题规则词表（合同§2：规则词表翻译+缺词回退英文——零 LLM 零人工）。
 *
 * <p>标题是展示字段不入 UID。英文侧按事件码×学期×语义槽模板生成（解析身份的
 * 封闭代码集全量覆盖）；中文侧逐码词表，缺词返回 null——查询/导出侧回退英文
 * （title_zh NULL=回退约定，schema 注释同口径）。新增事件码必须同步本词表
 * （词表缺失不改身份，只影响中文展示）。
 */
public final class KeyDateTitles {

    /**
     * 学期码→英文措辞
     */
    private static final Map<String, String> TERM_EN = Map.of(
            "S1", "Semester One", "S2", "Semester Two", "SU", "Summer Term", "AY", "Academic Year");

    /**
     * 学期码→中文措辞
     */
    private static final Map<String, String> TERM_ZH = Map.of(
            "S1", "第一学期", "S2", "第二学期", "SU", "暑期学期", "AY", "学年");

    /**
     * 假期语义码→双语（HOLIDAY_CODES 值域全量）
     */
    private static final Map<String, String[]> HOLIDAY_TITLES = Map.ofEntries(
            Map.entry("mid-autumn-following", new String[]{"The day following the Chinese Mid-Autumn Festival", "中秋节翌日"}),
            Map.entry("national-day", new String[]{"National Day", "国庆日"}),
            Map.entry("chung-yeung-following", new String[]{"The day following Chung Yeung Festival", "重阳节翌日"}),
            Map.entry("christmas-day", new String[]{"Christmas Day", "圣诞节"}),
            Map.entry("christmas-following", new String[]{"The first weekday after Christmas Day", "圣诞节后首个工作日"}),
            Map.entry("first-january", new String[]{"The first day of January", "一月一日"}),
            Map.entry("lny-day", new String[]{"Lunar New Year's Day", "农历年初一"}),
            Map.entry("lny-second", new String[]{"The second day of Lunar New Year", "农历年初二"}),
            Map.entry("lny-third", new String[]{"The third day of Lunar New Year", "农历年初三"}),
            Map.entry("good-friday", new String[]{"Good Friday", "耶稣受难日"}),
            Map.entry("good-friday-following", new String[]{"The day following Good Friday", "耶稣受难节翌日"}),
            Map.entry("easter-monday", new String[]{"Easter Monday", "复活节星期一"}),
            Map.entry("ching-ming", new String[]{"Ching Ming Festival", "清明节"}),
            Map.entry("labour-day", new String[]{"Labour Day", "劳动节"}),
            Map.entry("buddha-birthday", new String[]{"The Birthday of the Buddha", "佛诞"}),
            Map.entry("hksar-establishment", new String[]{"The HKSAR Establishment Day", "香港特区成立纪念日"}),
            Map.entry("tuen-ng", new String[]{"Tuen Ng Festival", "端午节"}));

    /**
     * 教学暂停语义码→双语（SUSPENSION_CODES 值域全量；写域扩展 2026-09-29 批准）
     */
    private static final Map<String, String[]> SUSPENSION_TITLES = Map.ofEntries(
            Map.entry("mid-autumn", new String[]{"Chinese Mid-Autumn Festival (classes suspended)", "中秋节（停课）"}),
            Map.entry("ug-info-day", new String[]{"PolyU Undergraduate Info Day (classes suspended)", "理大本科资讯日（停课）"}),
            Map.entry("winter-solstice", new String[]{"Winter Solstice (classes suspended)", "冬至（停课）"}),
            Map.entry("christmas-eve", new String[]{"Christmas Eve (classes suspended)", "平安夜（停课）"}),
            Map.entry("new-year-eve", new String[]{"New Year Eve (classes suspended)", "除夕（停课）"}),
            Map.entry("lny-eve", new String[]{"Lunar New Year Eve (classes suspended)", "农历年除夕（停课）"}),
            Map.entry("lny-break", new String[]{"Lunar New Year Break (classes suspended)", "农历新年假期（停课）"}));

    /**
     * SID 受众码→双语（SID_AUDIENCE 值域全量）
     */
    private static final Map<String, String[]> SID_TITLES = Map.ofEntries(
            Map.entry("sid-exp-aug", new String[]{"Student Identity Card collection (cards expiring end of August)", "学生证领取（八月到期换领）"}),
            Map.entry("sid-exp-jan", new String[]{"Student Identity Card collection (cards expiring end of January)", "学生证领取（一月到期换领）"}),
            Map.entry("sid-resume-su", new String[]{"Student Identity Card collection (students resuming in Summer Term)", "学生证领取（暑期复学）"}));

    /**
     * 缴费语义槽→双语
     */
    private static final Map<String, String[]> FEE_SLOT_TITLES = Map.ofEntries(
            Map.entry("initial", new String[]{"first round", "首轮"}),
            Map.entry("remaining", new String[]{"remaining fee round", "剩余费轮次"}),
            Map.entry("summer", new String[]{"summer term round", "暑期轮次"}));

    private KeyDateTitles() {
    }

    /**
     * 英文标题（模板全量覆盖身份代码集；理论缺词兜底=身份码拼接，不抛异常）
     */
    public static String titleEn(KeyDateCandidate c) {
        String term = TERM_EN.getOrDefault(c.getTerm(), c.getTerm());
        return switch (c.getEventCode() == null ? "" : c.getEventCode()) {
            case "semester-teaching" -> term + " teaching " + ("start".equals(c.getSlot()) ? "commences" : "ends");
            case "adddrop" -> term + " Add/Drop period " + ("start".equals(c.getSlot()) ? "commences" : "ends");
            case "exam-period" -> term + " examination period";
            case "revision-days" -> term + " revision days";
            case "congregation" -> "Congregation (overall window)";
            case "results-finalisation" -> term + ("subject".equals(c.getSlot())
                    ? " subject assessment results finalisation" : " overall assessment results finalisation");
            case "academic-year-end" -> "Academic Year " + c.getAy() + " ends";
            case "general-holiday" -> HOLIDAY_TITLES.getOrDefault(c.getSlot(),
                    new String[]{c.getSlot(), null})[0];
            case "teaching-suspension" -> SUSPENSION_TITLES.getOrDefault(c.getSlot(),
                    new String[]{c.getSlot() + " (classes suspended)", null})[0];
            case "fee-notification" -> term + " tuition fee payment notification ("
                    + feeSlotEn(c.getSlot()) + ")";
            case "fee-deadline" -> term + " tuition fee payment deadline ("
                    + feeSlotEn(c.getSlot()) + ")";
            case "sid-card-collection" -> SID_TITLES.getOrDefault(c.getAudienceCode(),
                    new String[]{"Student Identity Card collection", null})[0];
            case "exam-tt-release" -> term + " examination timetable release window";
            case "class-tt-release" -> term + " class timetable release window";
            case "results-subject-release" -> term + " subject results release";
            case "results-overall-release" -> term + " overall results release";
            default -> c.getEventCode() + " " + c.getTerm() + " " + c.getSlot();
        };
    }

    /**
     * 中文标题（词表缺失返回 null=查询侧回退英文；零 LLM）
     */
    public static String titleZh(KeyDateCandidate c) {
        String term = TERM_ZH.getOrDefault(c.getTerm(), c.getTerm());
        return switch (c.getEventCode() == null ? "" : c.getEventCode()) {
            case "semester-teaching" -> term + ("start".equals(c.getSlot()) ? "开课" : "结课");
            case "adddrop" -> term + "增退选科期" + ("start".equals(c.getSlot()) ? "开始" : "截止");
            case "exam-period" -> term + "考试期";
            case "revision-days" -> term + "复习日";
            case "congregation" -> "毕业典礼（总体区间）";
            case "results-finalisation" -> term + ("subject".equals(c.getSlot()) ? "科目成绩定稿" : "总评成绩定稿");
            case "academic-year-end" -> c.getAy() + " 学年结束";
            case "general-holiday" -> HOLIDAY_TITLES.getOrDefault(c.getSlot(), new String[]{null, null})[1];
            case "teaching-suspension" -> SUSPENSION_TITLES.getOrDefault(c.getSlot(), new String[]{null, null})[1];
            case "fee-notification" -> term + "学费缴费通知（" + feeSlotZh(c.getSlot()) + "）";
            case "fee-deadline" -> term + "学费缴费截止（" + feeSlotZh(c.getSlot()) + "）";
            case "sid-card-collection" -> SID_TITLES.getOrDefault(c.getAudienceCode(), new String[]{null, null})[1];
            case "exam-tt-release" -> term + "考试时间表发布窗";
            case "class-tt-release" -> term + "课堂时间表发布窗";
            case "results-subject-release" -> term + "科目成绩发布";
            case "results-overall-release" -> term + "总评成绩发布";
            default -> null;
        };
    }

    private static String feeSlotEn(String slot) {
        String[] t = FEE_SLOT_TITLES.get(slot);
        return t != null ? t[0] : slot;
    }

    private static String feeSlotZh(String slot) {
        String[] t = FEE_SLOT_TITLES.get(slot);
        return t != null ? t[1] : slot;
    }
}

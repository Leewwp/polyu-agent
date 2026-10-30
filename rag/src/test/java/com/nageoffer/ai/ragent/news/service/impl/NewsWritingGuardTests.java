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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 写作守卫测试（#185，零调用质量门）：双语完整性、校园金额/资格/截止日期
 * 保真（真实样例句）、校园+AI 双身份词表防幻觉——守卫拒绝必须可解释（原因携带
 * 关键事实），拒绝后走明示零调用回退不无限付费重试（回退路径归富化测试）。
 */
class NewsWritingGuardTests {

    private static NewsEnrichService.NewsSummaryPayload payload(String summaryZh, String summaryEn) {
        return new NewsEnrichService.NewsSummaryPayload("标题", "Title", summaryZh, summaryEn,
                "scholarship", java.util.List.of());
    }

    private static void assertRejected(String expectedFragment, String titleZh, String titleEn,
                                       String content, String summaryZh, String summaryEn) {
        NewsWritingGuard.RejectionException rejection = assertThrows(NewsWritingGuard.RejectionException.class,
                () -> NewsWritingGuard.enforce(payload(summaryZh, summaryEn),
                        titleZh + " / " + titleEn, content));
        assertTrue(rejection.getMessage().contains(expectedFragment),
                "拒绝原因可解释且含关键事实（期望含「" + expectedFragment + "」，实际=" + rejection.getMessage() + "）");
    }

    // ================== 校园金额/资格/截止日期保真（真实样例句） ==================

    @Test
    void campusScholarshipAmountAndDeadlinePreserved() {
        // 真实样例句：理大奖学金公告（金额/名额/截止日期三个关键事实全在标题）
        String titleZh = "理大设立 10,000 港元新生奖学金，2026 年提供 50 个名额，截止 3 月 31 日申请";
        String titleEn = "PolyU launches HK$10,000 scholarship with 50 places for 2026 intake, apply by March 31";
        String summaryZh = "理大设立 10,000 港元新生奖学金，2026 年提供 50 个名额，申请人须于 3 月 31 日截止日前递交申请。"
                + "奖学金面向全日制新生开放。\n\n校方提醒申请人核对资格条件并按时提交材料。";
        String summaryEn = "PolyU has launched a HK$10,000 scholarship offering 50 places for the 2026 intake, "
                + "with applications closing on March 31.\n\nEligible full-time new students may apply.";

        assertDoesNotThrow(() -> NewsWritingGuard.enforce(payload(summaryZh, summaryEn),
                titleZh + " / " + titleEn, "正文略"));
    }

    @Test
    void campusAmountDriftRejectedWithExplainedReason() {
        // 金额漂移：10,000 → 摘要只写了 1,000（数字守卫拒绝）
        assertRejected("10000",
                "理大设立 10,000 港元新生奖学金", "PolyU launches HK$10,000 scholarship",
                "理大宣布新奖学金计划，金额 10,000 港元，2026 年开放申请。",
                "理大设立 1,000 港元新生奖学金，面向全日制学生。\n\n申请人须按时递交材料。",
                "PolyU launches a HK$1,000 scholarship for full-time students.\n\nApply on time.");
    }

    @Test
    void campusDeadlineDroppedRejected() {
        // 截止日期丢失：标题「3 月 31 日」两个数字均未在任一语言摘要出现（首个缺失即拒）
        assertRejected("3",
                "理大交换计划申请截止 3 月 31 日", "PolyU exchange programme closes March 31",
                "交换计划内容正文。",
                "理大交换计划开放申请，名额有限。\n\n请尽快递交。",
                "PolyU exchange programme is open with limited places.\n\nApply soon.");
    }

    @Test
    void thousandSeparatorNormalizationAccepted() {
        // 千分位写法归一后等值：标题 10,000 = 摘要 10000（不得误拒）
        assertDoesNotThrow(() -> NewsWritingGuard.enforce(
                payload("理大设立 10000 港元奖学金，5 个名额，3 月 1 日截止。\n\n按时申请。",
                        "PolyU offers a 10000 dollars scholarship for 5 places, closing March 1.\n\nApply on time."),
                "理大设立 10,000 港元奖学金，5 个名额，3 月 1 日截止 / PolyU HK$10,000 scholarship",
                "正文略"));
    }

    // ================== 双语完整性 ==================

    @Test
    void halfBilingualOutputRejected() {
        assertRejected("双语摘要不完整",
                "PolyU news", "PolyU news", "content",
                "只有中文摘要，缺失英文。\n\n补充段落。",
                "  ");
        assertRejected("双语摘要不完整",
                "PolyU news", "PolyU news", "content",
                null,
                "English summary only.\n\nSecond paragraph.");
    }

    // ================== 身份词表（校园+AI 双词表） ==================

    @Test
    void aiIdentityMisattributionRejected() {
        // AI 源：DeepMind 的发布被写成 OpenAI（正文只提 DeepMind）——拒绝原因携带被冒用的身份词族
        assertRejected("OpenAI",
                "DeepMind 发布新一代 AlphaFold", "DeepMind releases new AlphaFold",
                "Google DeepMind today announced an update to AlphaFold with improved accuracy.",
                "OpenAI 发布新一代 AlphaFold 蛋白质结构预测模型，精度显著提升。\n\n模型已开放使用。",
                "OpenAI has released a new AlphaFold model with improved accuracy.\n\nIt is now available.");
    }

    @Test
    void campusIdentityMisattributionRejected() {
        // 校园源：理大的成果被安到港大头上（输入只有理大）
        assertRejected("香港大学",
                "理大团队获国家科技进步奖", "PolyU team wins State Science and Technology Award",
                "香港理工大学团队凭借新材料研究获得国家科学技术进步奖。",
                "香港大学团队凭借新材料研究获得国家科学技术进步奖。\n\n研究成果已转化应用。",
                "A University of Hong Kong team won the State Science and Technology Award.\n\nThe work has been applied.");
    }

    @Test
    void supportedIdentityMentionPasses() {
        // 输入提及的机构在摘要中出现=有支撑（含别名族：输入全称「香港理工大学」→摘要用「理大」）
        assertDoesNotThrow(() -> NewsWritingGuard.enforce(
                payload("理大团队获国家科技进步奖，研究已转化应用。\n\n后续将推进产业化。",
                        "The PolyU team won the State Science and Technology Award.\n\nIndustrialisation will follow."),
                "香港理工大学团队获国家科技进步奖 / PolyU team wins award",
                "香港理工大学宣布获奖。"));
        // 协作报道中提及他校（输入有支撑）不误拒
        assertDoesNotThrow(() -> NewsWritingGuard.enforce(
                payload("理大与香港大学合作发布联合研究报告，覆盖 100 个样本。\n\n研究持续进行。",
                        "PolyU and HKU published a joint study covering 100 samples.\n\nThe research continues."),
                "理大与香港大学合作发布联合研究，覆盖 100 个样本 / PolyU-HKU joint study",
                "两校合作正文。"));
    }

    @Test
    void numberExtractionCoversAmountsPercentagesAndDates() {
        assertEquals(java.util.Set.of("10000", "50", "2026", "3", "31", "3.5", "85"),
                NewsWritingGuard.numbersOf("10,000 港元 50 个名额 2026年 3月31日 3.5 万 85%"),
                "千分位/小数/百分比数字抽取归一");
        assertTrue(NewsWritingGuard.numbersOf(null).isEmpty());
        assertTrue(NewsWritingGuard.numbersOf("").isEmpty());
    }

    // ================== 身份词边界/官方源自证/LCQ 序号（守卫判定面） ==================

    @Test
    void asciiSubstringInsideWordNoLongerCountsAsMention() {
        // 词内子串不算提及：摘要英文 "submit/limited" 含子串 "mit"，不得判为提及 MIT 族
        assertDoesNotThrow(() -> NewsWritingGuard.enforce(
                payload("文化之夜开放现场报名。\n\n座位有限。",
                        "Participants may submit the form on site. Seats are limited."),
                "African Cultural Night / African Cultural Night",
                "CF Wing Podium. Admission free."));
        // 真提及照旧拦截：摘要写 MIT 而输入无支撑
        assertRejected("MIT",
                "Campus contest winners announced", "Campus contest winners announced",
                "The contest ceremony was held on campus.",
                "校园大赛揭晓获奖名单。\n\nMIT 团队参赛获特别奖。",
                "Winners of the campus contest were announced.\n\nAn MIT team joined.");
    }

    @Test
    void officialPolyuHostSelfAttestsPolyuFamilyOnly() {
        // polyu.edu.hk 活动模板页正文只有日期/地点/主办方数据块，一个身份词都没有——
        // 摘要按信源归属写「理大」有官方域名自证，不是身份漂移
        assertDoesNotThrow(() -> NewsWritingGuard.enforce(
                payload("理大学生事务部举办考试支持活动，11 月 17 至 19 日举行。\n\n欢迎参加。",
                        "PolyU's exam support series runs 17-19 Nov for students."),
                "Exam Support Series: Connected We Grow / Exam Support Series: Connected We Grow",
                "Date 17 - 19 Nov 2026 Organiser Counselling and Wellness Section, SAO Time 11:30 - 14:30 Venue CF Wing",
                "www.polyu.edu.hk"));
        // 子域（sao.polyu.edu.hk）同样自证
        assertDoesNotThrow(() -> NewsWritingGuard.enforce(
                payload("理大活动公告。\n\n详见原文。",
                        "PolyU event announcement for the campus community."),
                "Exam Support Series / Exam Support Series",
                "Date and venue blocks only.",
                "sao.polyu.edu.hk"));
        // 只放开本校族：polyu.edu.hk 页面被安到港大头上照旧拒绝（跨校防漂移不弱化）
        assertRejected("香港大学",
                "Exam Support Series", "Exam Support Series",
                "Date and venue blocks only.",
                "香港大学举办考试支持活动。\n\n欢迎参加。",
                "The University of Hong Kong runs an exam support series.");
    }

    @Test
    void lcqSerialNumbersExemptedFromFidelityContract() {
        // 「LCQ20:」的 20 是立法会发文编号，不属保真事实——剥离后摘要不复现不判失真
        assertDoesNotThrow(() -> NewsWritingGuard.enforce(
                payload("政府书面回复立法会质询，说明消防演习与应变机制安排。\n\n详情见原文。",
                        "The government gave a written reply on fire drills and contingency response."),
                "LCQ20: Fire drills and contingency response mechanism / LCQ20: Fire drills",
                "Following is a question by the Honourable Member and a written reply by the Secretary."));
        // 剥离只针对 LCQ 前缀形态：标题他处的同数字仍是必须保真的事实
        assertRejected("20",
                "LCQ 2: 校园 20 幢宿舍加装门禁", "LCQ 2: access control",
                "正文。",
                "政府回应质询，说明宿舍门禁安排。\n\n分批实施。",
                "The government replied on hostel access control.\n\nBatch rollout.");
    }

    @Test
    void cjkAliasBenignEmbeddingsDoNotCountAsIdentityMentions() {        // 生产实录（news/213/268）：「香港大会堂（剧院）」内嵌「港大」、「中大型企业」
        // 内嵌「中大」——场地/统计用语非院校指称，不得判为身份提及
        assertDoesNotThrow(() -> NewsWritingGuard.enforce(
                payload("音乐会将于 11 月 9 日晚在香港大会堂剧院举行。\n\n门票现已公开发售。",
                        "The recital takes place at the Theatre of Hong Kong City Hall on 9 Nov."),
                "Dizi Recital in November / Dizi Recital in November",
                "The recital will be held at the Theatre of Hong Kong City Hall.",
                "www.info.gov.hk"));
        assertDoesNotThrow(() -> NewsWritingGuard.enforce(
                payload("调查显示，七成中小企业及中大型企业已采用人工智能。\n\n需求持续上升。",
                        "Seven in ten SMEs and medium-to-large firms have adopted AI."),
                "AI adoption survey / AI adoption survey",
                "Survey findings on AI adoption.",
                "www.info.gov.hk"));
        // 真缩写提及照旧拦截；原文只写场地不构成港大支撑（提及面/支撑面同剥离）
        assertRejected("香港大学",
                "Dizi Recital in November", "Dizi Recital in November",
                "The recital will be held at the Theatre of Hong Kong City Hall.",
                "港大笛子演奏会将举行。\n\n详情见原文。",
                "HKU will present a dizi recital.");
    }

    @Test
    void decimalRefinementOfTitleNumberCountsAsPreserved() {
        // 生产实录（news/288）：新闻稿标题舍入 56%、正文与摘要精确 56.1%——
        // 摘要侧小数精化等值不算漂移
        assertDoesNotThrow(() -> NewsWritingGuard.enforce(
                payload("理大团队开发的肿瘤异质性指数可预测乳腺癌药物反应，病理完全缓解率达 56.1%。\n\n研究已发表。",
                        "The PolyU-developed index predicts drug response with a 56.1% pathological complete response rate."),
                "PolyU develops Tumour Heterogeneity Index achieving a 56% complete response rate / THI 56%",
                "The study reports a 56.1% pCR rate."));
        // 丢精度反向不成立：标题精确 56.1、摘要舍入 56 照旧拒绝
        assertRejected("56.1",
                "Study achieves 56.1% response rate", "Study achieves 56.1% response rate",
                "正文。",
                "研究达到 56% 的缓解率。\n\n详见原文。",
                "The study achieved a 56% response rate.");
        // 缩放改写不因精化规则松动：10000 → 1000 仍拒
        assertRejected("10000",
                "奖学金 10,000 港元", "HK$10,000 scholarship",
                "正文。",
                "奖学金金额为 1000.5 港元。\n\n按时申请。",
                "The scholarship is worth 1000.5 dollars.");
    }
}

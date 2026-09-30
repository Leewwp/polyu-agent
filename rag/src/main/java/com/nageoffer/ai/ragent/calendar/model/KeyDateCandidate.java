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

package com.nageoffer.ai.ragent.calendar.model;

import lombok.Data;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 候选片段（对齐 #189 期望账本行字段）：页面中一个有业务含义的片段及其
 * WRITE/VERIFY/KNOWN_SKIP/UNKNOWN 归属、身份六段、日期精度与原文出处。
 *
 * <p>同一实例在白名单合并（考试区间对/复习日对/毕业典礼跨月）时兼作合并的
 * 「半行」输入：{@code half}/{@code halfDate} 为合并线索（前缀下划线字段不落库、
 * 不进身份）。provenance 保留一对多/多对一出处映射（r12,r14），不得比较
 * 「输入行数=输出事件数」证明完整性。
 */
@Data
public class KeyDateCandidate {

    private String sourceKey;
    private String locator;
    private String rawText;
    private Disposition disposition;
    private String reason;

    private String ay;
    private String term;
    private String eventCode;
    private String audienceCode;
    private String slot;

    /**
     * exact-day / exact-range / onwards / fuzzy
     */
    private String precision;
    private LocalDate dateStart;
    private LocalDate dateEnd;
    /**
     * 模糊窗桶（late-october-2026 等，precision=fuzzy 时非空；不伪造具体日）
     */
    private String fuzzyBucket;

    private String audienceText;
    /**
     * 页面学年证据描述（人工可读，落库供排查；身份学年 ay 的出处）
     */
    private String ayEvidence;
    /**
     * 已批写域扩展标记（teaching-suspension/revision-days，合同§9.2 裁决①；保留供审计）
     */
    private boolean domainExtension;

    /**
     * 原始行/单元格引用列表（出处闭合，合同§4 门 3）
     */
    private List<String> provenance = new ArrayList<>();

    /**
     * 合并线索：pair 合并的半行方向（start/end）；非合并候选为 null
     */
    private String half;

    /**
     * 合并线索：半行自身日期（pair 合并时取两端组成区间）
     */
    private LocalDate halfDate;

    /**
     * 白名单合并后标记：同身份键多实例且无合并规则覆盖（或合并对不完整）——
     * 出现即整源退化（不得分配出现序号/空号/45 天匹配，合同§3）
     */
    private boolean ambiguous;

    public KeyDateCandidate() {
    }

    public KeyDateCandidate(String sourceKey, String locator, String rawText,
                            Disposition disposition, String reason) {
        this.sourceKey = sourceKey;
        this.locator = locator;
        this.rawText = rawText;
        this.disposition = disposition;
        this.reason = reason;
        this.provenance.add(locator);
    }

    /**
     * 是否为白名单可合并事件码（考试区间对/复习日对/毕业典礼跨月总体区间）——
     * 期望账本核对中合并半行允许共享身份键（非合并码的重复身份=歧义）
     */
    public static boolean mergeable(String eventCode) {
        return "exam-period".equals(eventCode) || "revision-days".equals(eventCode)
                || "congregation".equals(eventCode);
    }

    /**
     * 浅拷贝（provenance 列表新建）：合并器产出事件不得改写原候选片段——
     * 片段是对账/出处证据，事件是发布视图（replay.py post_merge 同口径）
     */
    public KeyDateCandidate copy() {
        KeyDateCandidate c = new KeyDateCandidate(sourceKey, locator, rawText, disposition, reason);
        c.setAy(ay);
        c.setTerm(term);
        c.setEventCode(eventCode);
        c.setAudienceCode(audienceCode);
        c.setSlot(slot);
        c.setPrecision(precision);
        c.setDateStart(dateStart);
        c.setDateEnd(dateEnd);
        c.setFuzzyBucket(fuzzyBucket);
        c.setAudienceText(audienceText);
        c.setAyEvidence(ayEvidence);
        c.setDomainExtension(domainExtension);
        c.setProvenance(new ArrayList<>(provenance));
        c.setHalf(half);
        c.setHalfDate(halfDate);
        c.setAmbiguous(ambiguous);
        return c;
    }
}

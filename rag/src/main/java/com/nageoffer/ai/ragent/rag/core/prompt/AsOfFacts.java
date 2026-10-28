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

package com.nageoffer.ai.ragent.rag.core.prompt;

import java.time.LocalDate;

/**
 * 证据条目 as-of 状态计算与标注文案（#413，承 #407/#376 C 形态：计算由代码做、模型只做语言表达）。
 * <p>
 * 纯函数、无状态、不依赖 Spring：判定基准日 {@code today} 由调用方注入（生产=HKT 当前日期，
 * 见 {@link HktAsOfDateSource}），全部判定只做日期比较、不读系统时钟，判定结果可固定复现。
 * <p>
 * 消费 {@code #411} 双日期列的两族独立事实：发布时间（sourceDate，文档级）与活动截止日期
 * （activityEndDate，条目级）。双 null = 无日期事实，返回 null，由组装层维持「日期未知」降级，
 * 不虚构不推算（#376 红线）。
 * <p>
 * 状态优先级（组合矛盾数据时的确定性裁决，先到先得）：
 * <ol>
 *   <li>截止日已过 → {@link Status#EXPIRED}（安全优先：过期事实压过其余信号，防按失效期限行动）</li>
 *   <li>截止日即今日 → {@link Status#ONGOING} 且当日截止（可办理窗口的最后一天）</li>
 *   <li>发布日期在未来 → {@link Status#UPCOMING}（资料描述的未来安排尚未生效）</li>
 *   <li>有截止日且未到 → {@link Status#ONGOING}（尚未截止）</li>
 *   <li>仅有发布日期且已到 → {@link Status#ONGOING}（现行资料）</li>
 * </ol>
 */
public final class AsOfFacts {

    /** 三态词汇与票面一致：已过期 / 进行中 / 未开始 */
    public enum Status {
        EXPIRED("已过期"),
        ONGOING("进行中"),
        UPCOMING("未开始");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private AsOfFacts() {
    }

    /**
     * 按判定基准日计算证据条目 as-of 状态；双日期均 null 时返回 null（日期未知，不判状态）
     */
    public static Status status(LocalDate today, LocalDate sourceDate, LocalDate activityEndDate) {
        if (sourceDate == null && activityEndDate == null) {
            return null;
        }
        if (activityEndDate != null && today.isAfter(activityEndDate)) {
            return Status.EXPIRED;
        }
        if (activityEndDate != null && today.isEqual(activityEndDate)) {
            return Status.ONGOING;
        }
        if (sourceDate != null && today.isBefore(sourceDate)) {
            return Status.UPCOMING;
        }
        return Status.ONGOING;
    }

    /**
     * 渲染注入检索上下文的确定性日期事实行，如
     * {@code 〔日期事实〕截止日期 2026-10-20：已过期（截至 2026-10-29，香港时间）}。
     * <p>
     * 只陈述已算好的结论与基准（模型据此表达，不做日期推断）；双日期均 null 返回 null（维持「日期未知」降级）
     */
    public static String factLine(LocalDate today, LocalDate sourceDate, LocalDate activityEndDate) {
        Status status = status(today, sourceDate, activityEndDate);
        if (status == null) {
            return null;
        }
        String asOf = "（截至 " + today + "，香港时间）";
        if (status == Status.EXPIRED) {
            return "〔日期事实〕截止日期 " + activityEndDate + "：已过期，截止日已过" + asOf;
        }
        if (activityEndDate != null && today.isEqual(activityEndDate)) {
            return "〔日期事实〕截止日期 " + activityEndDate + "：进行中，今日为截止日" + asOf;
        }
        if (status == Status.UPCOMING) {
            return "〔日期事实〕资料日期 " + sourceDate + "：未开始，日期在未来" + asOf;
        }
        if (activityEndDate != null) {
            return "〔日期事实〕截止日期 " + activityEndDate + "：进行中，尚未截止" + asOf;
        }
        return "〔日期事实〕资料日期 " + sourceDate + "：进行中，现行资料" + asOf;
    }
}

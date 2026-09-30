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

/**
 * 单源单轮抓取结果六类统一分类学（#186 合同，父票 #181 §2）
 *
 * <p><b>六类</b>（票面原文次序）：
 * <ol>
 *   <li>{@link #VALID_WITH_CONTENT 有效有内容}——传输+解析全成功且产出 ≥1 条候选。</li>
 *   <li>{@link #VALID_EMPTY 有效空或无新增}——结构有效但零条目，且该源配置了
 *       allow-empty（rag.news.allow-empty-sources）。健康语义：源是好的、结构对，
 *       只是没货（「无新增」=内容全为重复的轮次在抓取层落第一类，去重归准入阶段，
 *       健康语义相同——可达+结构完好）。</li>
 *   <li>{@link #STRUCTURE_MISMATCH 结构失配}——解析 fail-closed 零条目/XML 不合法/
 *       本地配置失配（未知策略、events 缺占位）；未配置 allow-empty 的源零条目也落此类。</li>
 *   <li>{@link #NETWORK_FAILURE 网络失败}——传输层失败（IO/超时/HTTP 4xx/5xx/
 *       重定向违例，瞬时重试 1 次后仍失败）。</li>
 *   <li>{@link #POLICY_FORBIDDEN 策略禁止}——robots Disallow / 出站守卫拒绝。</li>
 *   <li>{@link #DEFER defer}——源站 Crawl-delay 超单次等待上限，本轮礼貌等待。
 *       <b>零计数豁免</b>：不增不清零失败滞回。</li>
 * </ol>
 *
 * <p><b>健康口径</b>：第 1/2 类=有效完整成功（探活复归的合格判定——HTTP 200 本身
 * 不是恢复充分条件，必须解析有效）；第 3/4 类计入失败滞回（阈值 3 自动隔离）；
 * 第 5 类立即转策略停用；第 6 类豁免。
 */
public enum NewsFetchOutcome {

    /**
     * 有效有内容：传输+解析全成功且 ≥1 条候选（去重前条数≥1）
     */
    VALID_WITH_CONTENT("valid_with_content"),

    /**
     * 有效空或无新增：结构有效但零条目（源已配置 allow-empty）——允许空的源健康
     */
    VALID_EMPTY("valid_empty"),

    /**
     * 结构失配：解析 fail-closed/不合法/本地配置失配——不可凭其复归
     */
    STRUCTURE_MISMATCH("structure_mismatch"),

    /**
     * 网络失败：传输层失败（瞬时重试后仍失败）
     */
    NETWORK_FAILURE("network_failure"),

    /**
     * 策略禁止：robots Disallow/出站守卫拒绝——不因可达自动解禁
     */
    POLICY_FORBIDDEN("policy_forbidden"),

    /**
     * defer（礼貌等待）：Crawl-delay 超单次等待上限本轮跳过——零计数豁免
     */
    DEFER("defer");

    private final String code;

    NewsFetchOutcome(String code) {
        this.code = code;
    }

    /**
     * 落库代码（t_news_source.last_outcome 列与事件表 outcome 列共用）
     */
    public String code() {
        return code;
    }

    /**
     * 是否有效完整成功（健康）：第 1/2 类——探活复归的合格判定
     */
    public boolean isValidSuccess() {
        return this == VALID_WITH_CONTENT || this == VALID_EMPTY;
    }

    /**
     * 是否计入失败滞回（连续 ≥3 自动隔离）：第 3/4 类
     */
    public boolean countsTowardHysteresis() {
        return this == STRUCTURE_MISMATCH || this == NETWORK_FAILURE;
    }

    /**
     * 按落库代码反查（admin 面板/测试用；未知代码抛 IllegalArgumentException）
     */
    public static NewsFetchOutcome ofCode(String code) {
        for (NewsFetchOutcome outcome : values()) {
            if (outcome.code.equals(code)) {
                return outcome;
            }
        }
        throw new IllegalArgumentException("未知抓取结果代码: " + code);
    }
}

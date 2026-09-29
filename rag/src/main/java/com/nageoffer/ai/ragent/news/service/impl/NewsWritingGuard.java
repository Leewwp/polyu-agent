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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 资讯写作守卫（#185，零 LLM 调用的本地质量门——「零调用守卫」）
 *
 * <p>对富化产物（解析后的 JSON 载荷）做三类本地校验，任一不过即拒绝该摘要
 * （不发布）：<br>
 * <b>1. 双语完整性</b>：summary_zh/summary_en 均须非空——半语种输出不得发布
 * （双语回退归明示零调用回退路径，不得靠缺语种混过发布）。<br>
 * <b>2. 事实保真（数字面）</b>：标题中的全部数字（金额/日期/名额等关键事实的
 * 最小载体）必须在摘要双语任一中原样再现（去千分位后比对）。标题是抓取链路
 * 唯一无截断的全文输入，以它为保真契约面；中文数字改写（如「一万」）会被
 * 判失真拒绝——提示词已明令数字原样保留，拒绝后走回退不冒坏数字。<br>
 * <b>3. 身份词表（校园+AI 双词表）</b>：摘要中出现的身份词（机构/公司别名族）
 * 必须在原文输入（标题+送模型的正文）中有同族支撑——防止把 DeepMind 的发布
 * 写成 OpenAI、把理大新闻安到港大头上（AIHOT 式身份漂移守卫的本地化）。
 *
 * <p>守卫拒绝不触发无限付费重试：新鲜响应拒绝→回执保留一次免费复用；复用响应
 * 再拒→回执隔离（POISONED）+条目转明示零调用回退（见 NewsEnrichService）。
 */
final class NewsWritingGuard {

    /**
     * 校园身份词表（别名族：族内任一别名出现即视为提及该机构；同族任一别名
     * 在原文出现即视为有支撑）——本港高校族，防跨校安错头
     */
    static final List<Set<String>> CAMPUS_IDENTITY_FAMILIES = List.of(
            Set.of("香港理工大学", "理大", "PolyU", "The Hong Kong Polytechnic University",
                    "Hong Kong Polytechnic University"),
            Set.of("香港大学", "港大", "HKU", "The University of Hong Kong", "University of Hong Kong"),
            Set.of("香港中文大学", "中大", "CUHK", "The Chinese University of Hong Kong",
                    "Chinese University of Hong Kong"),
            Set.of("香港科技大学", "科大", "HKUST", "Hong Kong University of Science and Technology"),
            Set.of("香港城市大学", "城大", "CityU", "City University of Hong Kong"),
            Set.of("香港浸會大學", "香港浸会大学", "浸大", "HKBU", "Hong Kong Baptist University"),
            Set.of("嶺南大學", "岭南大学", "Lingnan"));

    /**
     * AI 身份词表（AI 源内容的公司/机构别名族，#188 启源后主消费面；
     * 官网源报道 AI 合作时同样适用）
     */
    static final List<Set<String>> AI_IDENTITY_FAMILIES = List.of(
            Set.of("OpenAI"),
            Set.of("DeepMind", "Google DeepMind"),
            Set.of("Google"),
            Set.of("Microsoft"),
            Set.of("NVIDIA", "英伟达"),
            Set.of("Anthropic"),
            Set.of("Meta"),
            Set.of("Mistral"),
            Set.of("Hugging Face"),
            Set.of("arXiv"),
            Set.of("MIT", "Massachusetts Institute of Technology"),
            Set.of("量子位", "QbitAI"));

    /**
     * 数字抽取（含千分位与小数）："$10,000"、"85%"、"3.5"、"2026" → 归一数字串
     */
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:,\\d{3})*(?:\\.\\d+)?");

    private NewsWritingGuard() {
    }

    /**
     * 守卫拒绝：携带可解释原因（落日志/admin 抽检），由富化侧决定留待复用或转回退
     */
    static final class RejectionException extends RuntimeException {
        RejectionException(String reason) {
            super(reason);
        }
    }

    /**
     * 对载荷执行三类守卫；通过则静默返回，拒绝抛 {@link RejectionException}
     *
     * @param payload     解析后的双语摘要载荷
     * @param titleLine   标题行（zh+en，同提示词 title_line 口径）——事实保真契约面
     * @param contentText 送模型的正文（可能截断；无正文时传 null）——身份支撑判定面
     */
    static void enforce(NewsEnrichService.NewsSummaryPayload payload, String titleLine, String contentText) {
        if (payload == null) {
            throw new RejectionException("载荷为空");
        }
        boolean zhPresent = payload.summary_zh() != null && !payload.summary_zh().isBlank();
        boolean enPresent = payload.summary_en() != null && !payload.summary_en().isBlank();
        if (!zhPresent || !enPresent) {
            throw new RejectionException("双语摘要不完整（zh=" + zhPresent + ", en=" + enPresent + "），半语种输出不得发布");
        }
        String summaryUnion = payload.summary_zh() + "\n" + payload.summary_en();
        for (String required : numbersOf(titleLine)) {
            if (!numbersOf(summaryUnion).contains(required)) {
                throw new RejectionException("标题关键数字未在摘要中原样保留：" + required + "（金额/名额/日期等事实保真守卫）");
            }
        }
        String input = (titleLine == null ? "" : titleLine) + "\n" + (contentText == null ? "" : contentText);
        List<Set<String>> families = new ArrayList<>(CAMPUS_IDENTITY_FAMILIES);
        families.addAll(AI_IDENTITY_FAMILIES);
        for (Set<String> family : families) {
            if (mentionsAny(summaryUnion, family) && !mentionsAny(input, family)) {
                throw new RejectionException("摘要提及身份词族 " + family + " 但原文无同族支撑（身份/事实防幻觉守卫）");
            }
        }
    }

    /**
     * 文本中的归一化数字全集（去千分位；"10,000"→"10000"）
     */
    static Set<String> numbersOf(String text) {
        if (text == null || text.isEmpty()) {
            return Set.of();
        }
        Set<String> numbers = new java.util.LinkedHashSet<>();
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            numbers.add(matcher.group().replace(",", ""));
        }
        return numbers;
    }

    /**
     * 文本是否提及词族任一别名（ASCII 大小写不敏感，CJK 子串包含）
     */
    private static boolean mentionsAny(String text, Set<String> family) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String alias : family) {
            if (alias.chars().allMatch(c -> c < 128)) {
                if (text.toLowerCase(Locale.ROOT).contains(alias.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            } else if (text.contains(alias)) {
                return true;
            }
        }
        return false;
    }
}

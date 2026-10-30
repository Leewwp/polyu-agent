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
import java.util.Map;
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
 * 最小载体）必须在摘要双语任一中原样再现（去千分位后比对；公文序号如 LCQ20
 * 的编号先剥离——发文序号不属保真事实；摘要侧「小数精化」等值视为保真——
 * 标题舍入 56%、正文/摘要精确 56.1% 不算漂移）。标题是抓取链路唯一无截断的全文输入，
 * 以它为保真契约面；中文数字改写（如「一万」）会被判失真拒绝——提示词已明令
 * 数字原样保留，拒绝后走回退不冒坏数字。<br>
 * <b>3. 身份词表（校园+AI 双词表）</b>：摘要中出现的身份词（机构/公司别名族）
 * 必须在原文输入（标题+送模型的正文）中有同族支撑——防止把 DeepMind 的发布
 * 写成 OpenAI、把理大新闻安到港大头上（AIHOT 式身份漂移守卫的本地化）。
 * 例外：条目 URL 属 {@link #OFFICIAL_DOMAIN_FAMILIES} 官方域名时，本校族身份词
 * 由域名自证（官方页面归属是信源事实，正文模板页可以不含身份词字面）。
 *
 * <p>守卫拒绝不触发无限付费重试：新鲜响应拒绝→回执保留一次免费复用；复用响应
 * 再拒→回执隔离（POISONED）+条目转明示零调用回退（见 NewsEnrichService）。
 */
final class NewsWritingGuard {

    /**
     * 校园身份词表（别名族：族内任一别名出现即视为提及该机构；同族任一别名
     * 在原文出现即视为有支撑）——本港高校族，防跨校安错头
     */
    static final Set<String> POLYU_FAMILY = Set.of("香港理工大学", "理大", "PolyU",
            "The Hong Kong Polytechnic University", "Hong Kong Polytechnic University");
    static final Set<String> HKU_FAMILY = Set.of("香港大学", "港大", "HKU",
            "The University of Hong Kong", "University of Hong Kong");
    static final Set<String> CUHK_FAMILY = Set.of("香港中文大学", "中大", "CUHK",
            "The Chinese University of Hong Kong", "Chinese University of Hong Kong");
    static final Set<String> HKUST_FAMILY = Set.of("香港科技大学", "科大", "HKUST",
            "Hong Kong University of Science and Technology");
    static final Set<String> CITYU_FAMILY = Set.of("香港城市大学", "城大", "CityU",
            "City University of Hong Kong");
    static final Set<String> HKBU_FAMILY = Set.of("香港浸會大學", "香港浸会大学", "浸大", "HKBU",
            "Hong Kong Baptist University");
    static final Set<String> LINGNAN_FAMILY = Set.of("嶺南大學", "岭南大学", "Lingnan");

    static final List<Set<String>> CAMPUS_IDENTITY_FAMILIES = List.of(
            POLYU_FAMILY, HKU_FAMILY, CUHK_FAMILY, HKUST_FAMILY, CITYU_FAMILY, HKBU_FAMILY, LINGNAN_FAMILY);

    /**
     * 官方域名 → 本校身份族的自证面：官方站点的页面本身即归属证据——
     * polyu.edu.hk 的活动/公告模板页正文可以一个身份词都不出现（日期/地点/主办方走
     * 数据区块，正文块只剩骨架文本），摘要按信源归属写「理大/PolyU」不是身份漂移。
     * 只放开域名对应的那一族：polyu.edu.hk 页面被写成港大/中大照旧要求文本支撑；
     * 多校聚合源（JUPAS/UGC/gov.hk）无映射、零放开
     */
    static final Map<String, Set<String>> OFFICIAL_DOMAIN_FAMILIES = Map.ofEntries(
            Map.entry("polyu.edu.hk", POLYU_FAMILY),
            Map.entry("hku.hk", HKU_FAMILY),
            Map.entry("cuhk.edu.hk", CUHK_FAMILY),
            Map.entry("ust.hk", HKUST_FAMILY),
            Map.entry("cityu.edu.hk", CITYU_FAMILY),
            Map.entry("hkbu.edu.hk", HKBU_FAMILY),
            Map.entry("ln.edu.hk", LINGNAN_FAMILY));

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

    /**
     * 标题中的公文序号形态：立法会答问「LCQ20: …」「LCQ 2: …」的数字是发文编号，
     * 不属金额/名额/日期类保真事实（摘要按语义可不复现）。只剥 LCQ 前缀形态，
     * 不泛化到其他字母数字组合
     */
    private static final Pattern TITLE_SERIAL = Pattern.compile("(?i)\\bLCQ\\s*\\d+\\b");

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
        enforce(payload, titleLine, contentText, null);
    }

    /**
     * 全参形态：sourceHost=条目 URL 主机名（小写，可空）——命中 {@link #OFFICIAL_DOMAIN_FAMILIES}
     * 时本校族身份词视为有支撑（官方页面自证归属，见其 javadoc）
     *
     * @param sourceHost 条目 URL 主机名，非官方域名传 null
     */
    static void enforce(NewsEnrichService.NewsSummaryPayload payload, String titleLine, String contentText,
                        String sourceHost) {
        if (payload == null) {
            throw new RejectionException("载荷为空");
        }
        boolean zhPresent = payload.summary_zh() != null && !payload.summary_zh().isBlank();
        boolean enPresent = payload.summary_en() != null && !payload.summary_en().isBlank();
        if (!zhPresent || !enPresent) {
            throw new RejectionException("双语摘要不完整（zh=" + zhPresent + ", en=" + enPresent + "），半语种输出不得发布");
        }
        String summaryUnion = payload.summary_zh() + "\n" + payload.summary_en();
        Set<String> summaryNumbers = numbersOf(summaryUnion);
        for (String required : numbersOf(stripTitleSerials(titleLine))) {
            if (!numberPreserved(required, summaryNumbers)) {
                throw new RejectionException("标题关键数字未在摘要中原样保留：" + required + "（金额/名额/日期等事实保真守卫）");
            }
        }
        String input = (titleLine == null ? "" : titleLine) + "\n" + (contentText == null ? "" : contentText);
        Set<String> selfAttestedFamily = officialFamilyOf(sourceHost);
        List<Set<String>> families = new ArrayList<>(CAMPUS_IDENTITY_FAMILIES);
        families.addAll(AI_IDENTITY_FAMILIES);
        for (Set<String> family : families) {
            if (family == selfAttestedFamily) {
                continue;
            }
            if (mentionsAny(summaryUnion, family) && !mentionsAny(input, family)) {
                throw new RejectionException("摘要提及身份词族 " + family + " 但原文无同族支撑（身份/事实防幻觉守卫）");
            }
        }
    }

    /**
     * 官方域名后缀匹配（host 等于域名或以「.域名」结尾，如 sao.polyu.edu.hk）→
     * 对应本校身份族；非官方域名/null 返回 null（零放开）
     */
    static Set<String> officialFamilyOf(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Set<String>> entry : OFFICIAL_DOMAIN_FAMILIES.entrySet()) {
            String domain = entry.getKey();
            if (normalized.equals(domain) || normalized.endsWith("." + domain)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 标题公文序号剥离：数字保真合同面剔除 LCQ 编号（见 {@link #TITLE_SERIAL}），
     * 序号词本身保留（「LCQ20:」→「LCQ:」，标题他处出现的同数字事实不受影响）
     */
    static String stripTitleSerials(String titleLine) {
        if (titleLine == null || titleLine.isEmpty()) {
            return titleLine;
        }
        return TITLE_SERIAL.matcher(titleLine).replaceAll("LCQ");
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
     * 标题要求数字是否在摘要数字集中保真：相等，或摘要侧为其「小数精化」形式——
     * 新闻稿标题常把正文精确值舍入为整数（正文 56.1% → 标题 56%），摘要按正文写
     * 56.1 不算漂移（「56」←「56.1」成立；「560」/「56.1」←「56.0」等不成立，
     * 反向丢精度「56.1」标题→「56」摘要同样不成立，缩放改写照旧拒绝）
     */
    static boolean numberPreserved(String required, Set<String> summaryNumbers) {
        if (summaryNumbers.contains(required)) {
            return true;
        }
        for (String summaryNumber : summaryNumbers) {
            if (summaryNumber.startsWith(required + ".")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 文本是否提及词族任一别名——ASCII 别名按词边界匹配（子串匹配会把 submit/
     * commit/limit 里的 "mit" 当成 MIT 提及、把 metaverse 当成 Meta 提及；边界定义
     * 与 regex \b 一致=前后字符不得为字母/数字/下划线，"PolyU's"/"polyu.edu.hk"
     * 照常命中），CJK 别名保持子串包含（中文无词边界）；CJK 两字缩写嵌于更长
     * 常用词内不构成提及（见 {@link #CJK_ALIAS_BENIGN_EMBEDDINGS}）
     */
    private static boolean mentionsAny(String text, Set<String> family) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String cjkScanText = null;
        for (String alias : family) {
            if (alias.chars().allMatch(c -> c < 128)) {
                if (containsAsciiWord(text, alias)) {
                    return true;
                }
            } else {
                if (cjkScanText == null) {
                    cjkScanText = stripCjkBenignEmbeddings(text);
                }
                if (cjkScanText.contains(alias)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * CJK 两字缩写嵌于更长常用词内不构成身份提及（证据驱动扩充）：「香港大会堂/
     * 香港大球场」内嵌「港大」、「中大型/中大奖」内嵌「中大」——场地与统计用语
     * 而非院校指称。匹配面前先以等长占位符抹除，防子串包含误报；提及面与支撑面
     * 同走本剥离（原文只写场地时既不误判提及、也不误判支撑）
     */
    private static final List<String> CJK_ALIAS_BENIGN_EMBEDDINGS =
            List.of("香港大会堂", "香港大球场", "中大型", "中大奖");

    private static String stripCjkBenignEmbeddings(String text) {
        String stripped = text;
        for (String benign : CJK_ALIAS_BENIGN_EMBEDDINGS) {
            stripped = stripped.replace(benign, "〓".repeat(benign.length()));
        }
        return stripped;
    }

    private static boolean containsAsciiWord(String text, String asciiAlias) {
        String lower = text.toLowerCase(Locale.ROOT);
        String alias = asciiAlias.toLowerCase(Locale.ROOT);
        int from = 0;
        int index;
        while ((index = lower.indexOf(alias, from)) >= 0) {
            int end = index + alias.length();
            boolean leftBoundary = index == 0 || !isWordChar(lower.charAt(index - 1));
            boolean rightBoundary = end >= lower.length() || !isWordChar(lower.charAt(end));
            if (leftBoundary && rightBoundary) {
                return true;
            }
            from = index + 1;
        }
        return false;
    }

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
    }
}

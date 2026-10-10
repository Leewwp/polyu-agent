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

import cn.hutool.core.util.StrUtil;

import java.util.regex.Pattern;

/**
 * 回答语言的取值、判定与 KB 合成侧规则文本
 * <p>
 * 语言在入口对原始提问判定一次后以 "zh"/"en" 字符串贯穿 Agent 与 rag 两模块，null 表示本轮不约束
 * （判不出语言时回落人设的「跟随用户语言」），跨模块契约就是这两个字面值。
 * 判定实现只有本类一份：Agent 链（AnswerLanguages 委托）与直连链（StreamChatPipeline）共用同一结果。
 */
public final class AnswerLanguageRules {

    public static final String ZH = "zh";
    public static final String EN = "en";

    /**
     * 显式中文回答指令：覆盖「用中文回答」「请以简体中文…」「翻译成中文」等常见形态
     */
    private static final Pattern ZH_EXPLICIT = Pattern.compile(
            "(?:用|以)(?:简体)?(?:中文|汉语|普通话)(?:来)?(?:回答|回复|作答|答)"
                    + "|(?:请|麻烦|帮我)[^。！？!?.]{0,8}(?:用|以)(?:简体)?(?:中文|汉语|普通话)"
                    + "|(?:翻译|译)(?:成|为|到)(?:简体)?(?:中文|汉语)");

    /**
     * 显式英文回答指令：英文动词句式与中文「用英文回答/翻译成英文」两类。
     * 尾部分支用单一确定性星（字符类与有限词表互不重叠，无相邻双星回溯——
     * CodeQL java/polynomial-redos 口径）
     */
    private static final Pattern EN_EXPLICIT = Pattern.compile(
            "(?i)(?:(?:answer|reply|respond|write|rewrite)[^.!?。！？]{0,40}\\bin\\s+english\\b"
                    + "|translate[^.!?。！？]{0,80}?(?:to|into)\\s+english\\b"
                    + "|\\bin\\s+english\\b(?:[\\s.。!！?？]|please|plz|thanks)*$"
                    + "|(?:用|以)(?:英文|英语)(?:来)?(?:回答|回复|作答|答)"
                    + "|(?:翻译|译)(?:成|为|到)(?:英文|英语))");

    /**
     * @return {@link #ZH} / {@link #EN}，判不出返回 null
     */
    public static String detect(String question) {
        if (StrUtil.isBlank(question)) {
            return null;
        }
        String explicit = explicitLanguage(question);
        if (explicit != null) {
            return explicit;
        }
        return majorityLanguage(question);
    }

    private static String explicitLanguage(String question) {
        // 两类都命中时中文优先：以中文书写的指令句通常是主要语言
        if (ZH_EXPLICIT.matcher(question).find()) {
            return ZH;
        }
        if (EN_EXPLICIT.matcher(question).find()) {
            return EN;
        }
        return null;
    }

    /**
     * 汉字占比 ≥30% 判中文：英文问句夹一两个中文专名（研讨室、图书馆）仍在 30% 以下，
     * 中文问句夹 PolyU、IT Help Centre 等英文名通常过半；双方都不占优的极端混语按启发式归属
     */
    private static String majorityLanguage(String question) {
        int cjk = 0;
        int latin = 0;
        for (int i = 0; i < question.length(); ) {
            int cp = question.codePointAt(i);
            i += Character.charCount(cp);
            if ((cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF)) {
                cjk++;
            } else if ((cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z')) {
                latin++;
            }
        }
        if (cjk == 0 && latin == 0) {
            return null;
        }
        if (cjk == 0) {
            return EN;
        }
        if (latin == 0) {
            return ZH;
        }
        return cjk * 10 >= (cjk + latin) * 3 ? ZH : EN;
    }

    private AnswerLanguageRules() {
    }

    /**
     * KB 合成系统提示的追加段：在模板选择完成后由 {@link RAGPromptService} 统一拼接，
     * 意图自定义模板与已解析槽位模板都绕不开；取值非法时返回 null 不追加
     */
    public static String kbSynthesisRule(String answerLanguage) {
        if (ZH.equals(answerLanguage)) {
            return "回答语言：最终答案必须以简体中文撰写；即使检索证据以英文为主，也输出简体中文。"
                    + "事实、数值、日期、资格条件与链接不得因语言转换改变；"
                    + "专有名词、机构名、课程代码、URL 与代码可保留原文。";
        }
        if (EN.equals(answerLanguage)) {
            return "Answer language: the final answer must be written entirely in English; "
                    + "even when the retrieved evidence is mostly Chinese, answer in English. "
                    + "Facts, numbers, dates, eligibility conditions and links must not change across the language conversion; "
                    + "proper nouns, organization names, course codes, URLs and code may stay in their original form.";
        }
        return null;
    }
}

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

package com.nageoffer.ai.ragent.agent.language;

import com.nageoffer.ai.ragent.rag.core.prompt.AnswerLanguageRules;

/**
 * 本轮回答语言判定：入口对原始问题判定一次，值进 RuntimeContext 供主 Agent 与知识工具共用
 * <p>
 * 判定实现在 rag 共享层 {@link AnswerLanguageRules#detect}（Agent 链与直连链单一源）：
 * 显式要求的输出语言/翻译目标优先；否则按主要自然语言判定；
 * 纯编号、表情等无可判定语言时返回 null（不约束，回落人设默认）。
 * 混合语句是启发式（汉字占比 ≥30% 判中文），不宣称完美识别任意文本，边界入验收。
 */
public final class AnswerLanguages {

    /**
     * RuntimeContext 存放键：AgentChatServiceImpl 写，AnswerLanguageMiddleware 与 KnowledgeSearchTool 读
     */
    public static final String RUNTIME_CONTEXT_KEY = "ragent_answer_language";

    private AnswerLanguages() {
    }

    /**
     * @return {@link AnswerLanguageRules#ZH} / {@link AnswerLanguageRules#EN}，判不出返回 null
     */
    public static String detect(String question) {
        return AnswerLanguageRules.detect(question);
    }

    /**
     * 主 Agent 本轮指令文本：由中间件注入模型输入，非法取值返回 null 不注入
     */
    public static String turnDirective(String answerLanguage) {
        if (AnswerLanguageRules.ZH.equals(answerLanguage)) {
            return "【本轮回答语言：简体中文】\n"
                    + "本轮所有面向用户的输出必须使用简体中文。\n"
                    + "- 知识库等工具返回的成品答案若为其他语言，先完整转换为简体中文再输出，"
                    + "事实、数值、日期、资格条件与链接保持不变\n"
                    + "- 专有名词、机构名、课程代码、URL 与代码可保留原文\n"
                    + "- 本约束优先于「原样全文输出」与「跟随用户提问语言」的人设规则";
        }
        if (AnswerLanguageRules.EN.equals(answerLanguage)) {
            return "[Answer language for this turn: English]\n"
                    + "All user-facing output in this turn must be written in English.\n"
                    + "- If a knowledge-base tool returns a finished answer in another language, "
                    + "fully convert it to English before output; keep facts, numbers, dates, "
                    + "eligibility conditions and links unchanged\n"
                    + "- Proper nouns, organization names, course codes, URLs and code may stay in their original form\n"
                    + "- This constraint takes precedence over the verbatim-output and follow-user-language persona rules";
        }
        return null;
    }
}

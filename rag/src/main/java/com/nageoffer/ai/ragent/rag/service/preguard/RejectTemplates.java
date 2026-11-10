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

package com.nageoffer.ai.ragent.rag.service.preguard;

import com.nageoffer.ai.ragent.rag.core.prompt.AnswerLanguageRules;

/**
 * 拒绝回复模板：礼貌解释 + 提问引导，语言随题面（zh/en）；
 * 拒答轮不走模型管道，模板即最终回复全文
 */
public final class RejectTemplates {

    private static final String ZH_TEMPLATE =
            "你好，我是香港理工大学的校园问答助手，只能回答与理大和校园学习生活相关的问题"
                    + "（例如学费、签证、选课退改、宿舍、图书馆、校园卡等）。"
                    + "你这个问题属于校园之外的开放话题，我这里没有可靠资料作答，为避免给出不准确的信息就不展开啦。"
                    + "欢迎换一个理大校园相关的问题，我会基于校内官方资料为你解答。";

    private static final String EN_TEMPLATE =
            "Hi, I'm the campus Q&A assistant for The Hong Kong Polytechnic University, "
                    + "and I can only answer questions about PolyU and campus life "
                    + "(e.g. tuition, visas, course add/drop, hostels, the library, campus cards). "
                    + "Your question is a general topic outside campus, and I don't have reliable sources "
                    + "to answer it, so I'd rather not guess. "
                    + "Please try asking something about PolyU campus life — I'll answer from official university sources.";

    private RejectTemplates() {
    }

    /**
     * 按题面语言取拒绝模板；判不出语言（null）回落中文（系统默认人设语言）
     */
    public static String template(String lang) {
        if (AnswerLanguageRules.EN.equals(lang)) {
            return EN_TEMPLATE;
        }
        return ZH_TEMPLATE;
    }
}

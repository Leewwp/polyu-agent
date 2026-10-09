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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * as-of 时钟基准事实（#332）：系统提示词注入服务器 HKT 当前日期。
 * <p>
 * 修前摸底：RAG 链（RAGPromptService/StreamChatPipeline/context-format.st）无任何当前日期注入，
 * 模型无从把证据里的活动日期与「今天」对比，过期活动被当作「近期/即将」作答（#331 终版重放实录）。
 * #327 的 data-ragent-date 是证据侧日期标记，KB 语料侧 t_knowledge_document 无日期列、
 * 检索器不填 sourceDate（证据块全「日期未知」）——判断新旧只能靠证据文本内嵌日期+本行注入的当前日期。
 * <p>
 * 票面合同「基准=服务器 HKT 时钟」：时区显式 Asia/Hong_Kong（容器 JVM 默认 UTC，
 * 与 KeyDateSyncJob #195 审核修正同款口径）；不新增任何 application.yaml 配置键（#332 comment 约束）。
 */
public final class CurrentDateFact {

    /** as-of 基准时区：香港时间（票面合同口径） */
    public static final ZoneId HKT = ZoneId.of("Asia/Hong_Kong");

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private CurrentDateFact() {
    }

    /**
     * 注入 system prompt 的日期事实行，如「当前日期（香港时间）：2026-10-10（星期六）」。
     * ISO 日期格式便于模型与证据文本日期做机械对比；星期为中文全称（模板正文为中文，
     * 输出语言由语言规则约束，与本行语言无关）。
     */
    public static String hktDateLine(LocalDate today) {
        return "当前日期（香港时间）：" + today.format(ISO_DATE)
                + "（" + today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.SIMPLIFIED_CHINESE) + "）";
    }

    /** 生产入口：取服务器 HKT 当前日期 */
    public static String hktDateLine() {
        return hktDateLine(LocalDate.now(HKT));
    }
}

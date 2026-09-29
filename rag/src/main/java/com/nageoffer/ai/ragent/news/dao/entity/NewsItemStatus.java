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

package com.nageoffer.ai.ragent.news.dao.entity;

/**
 * 资讯条目处理状态与公开可见性语义（#185，父票 #180 §2 合同）
 *
 * <p><b>状态机</b>（status 单列承载，设计取舍见 PR 描述）：
 * <pre>
 * 发现（原文发布时间 ≤48h）──insert──▶ pending（待富化，付费队列成员）
 * 发现（原文发布时间 ＞48h）──insert──▶ archived（终态：旧文归档，不进「今天」、跳过付费富化、不计准入）
 * pending ──富化成功（合格摘要）──▶ published + eligible_time=now
 * pending ──明示零调用回退（守卫/回执终态）──▶ published + eligible_time=now + summary_source=fallback
 * pending ──TTL 48h（fetch_time 起算）届满仍未获资格──▶ expired（终态：退出待办，不再公开）
 * published ──人工下架──▶ hidden（终态语义不变，admin 应急通道）
 * </pre>
 *
 * <p><b>处理状态与可见性分离</b>：published 只代表「发布资格已就绪」，公开可见还须
 * 发布门开启——统一公开资格（列表/详情/主题/检索/热点/徽章及后续 RSS/日报/MCP
 * 出口共用同一条规则）：
 * <blockquote>{@code status='published' AND (eligible_time IS NULL OR eligible_time <= now - 180s)}</blockquote>
 * 180s 从资格就绪起算（rag.news.publish-gate-seconds）；预算延期（DEGRADED）、
 * 无效摘要（FAILED/POISONED）、待富化（pending）都不能靠 180s 超时放行——
 * 它们根本不落 published。eligible_time IS NULL 为 #185 之前的历史行，
 * 资格视同早已就绪（历史不重算）。
 *
 * <p>本类只承载语义常量，不持有行为；三处消费面（LambdaQueryWrapper 构建、
 * QueryWrapper apply、NewsItemTopicMapper 注解 SQL）以本 javadoc 为唯一口径。
 */
public final class NewsItemStatus {

    /**
     * 待富化：已准入管线（计入日准入），等待 LLM 补全或明示回退；TTL 届满转 expired
     */
    public static final String PENDING = "pending";

    /**
     * 发布资格已就绪（合格摘要或明示零调用回执落库，eligible_time 随行落定）；
     * 公开可见=发布门开启（见类 javadoc 统一规则）
     */
    public static final String PUBLISHED = "published";

    /**
     * 终态·旧文归档：发现时原文发布时间已超 48h（rag.news.stale-article-hours）；
     * 不进「今天」、跳过付费富化、不计日准入；同 URL 重现不重建任何待办
     */
    public static final String ARCHIVED = "archived";

    /**
     * 终态·待富化超龄：首次发现（fetch_time）起 48h（rag.news.pending-ttl-hours）
 * 内未获发布资格；退出付费待办，不再公开——不承诺无条件次日清空
     */
    public static final String EXPIRED = "expired";

    /**
     * 终态·人工下架：admin 应急通道（published → hidden），语义沿用 #185 之前
     */
    public static final String HIDDEN = "hidden";

    /**
     * 摘要产出方式（summary_source 列）：LLM 富化产出
     */
    public static final String SUMMARY_SOURCE_LLM = "llm";

    /**
     * 摘要产出方式（summary_source 列）：明示零调用回退（标题派生双语摘要，
     * 守卫拒绝/回执终态的可解释回退，不触发无限付费重试）
     */
    public static final String SUMMARY_SOURCE_FALLBACK = "fallback";

    /**
     * 发布门时长（秒）：从 eligible_time 起算；外置 rag.news.publish-gate-seconds，
     * 此常量仅为该配置的默认值锚点
     */
    public static final int PUBLISH_GATE_SECONDS_DEFAULT = 180;

    /**
     * 语义类不实例化
     */
    private NewsItemStatus() {
    }
}

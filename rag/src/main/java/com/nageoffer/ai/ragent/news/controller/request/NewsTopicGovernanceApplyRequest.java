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

package com.nageoffer.ai.ragent.news.controller.request;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 主题提案批量处置请求（#202 admin 批量应用端点）
 *
 * <p>按建议轨或人工改轨逐条下发；同批可重复提交——已处于目标终态的行返回
 * SKIPPED（幂等），改判冲突（如已 merged 再 reject）整批拒绝不落任何变更
 * （{@code @Transactional} 全批原子）。
 */
@Data
@NoArgsConstructor
public class NewsTopicGovernanceApplyRequest {

    /**
     * 逐条处置指令（至少一条）
     */
    private List<NewsTopicDisposition> dispositions;

    /**
     * 单条处置指令：action 三轨 MERGE / PROMOTE / REJECT
     *（按建议轨或人工改轨）；
     * MERGE 须给 mergeTargetTopicId（curated 目标）；
     * PROMOTE 须给 promoteSlug（稳定 slug）+ promoteGroup（FACULTY/RESEARCH/
     * STUDENT_AFFAIRS），名称/描述四列可选拟定（不给则保留提案现值）；
     * reason 建议必填（留痕可复核）。
     */
    @Data
    @NoArgsConstructor
    public static class NewsTopicDisposition {

        /**
         * 提案行 ID（t_news_topic.id）
         */
        private Long topicId;

        /**
         * 处置轨别：MERGE / PROMOTE / REJECT
         */
        private String action;

        /**
         * MERGE 轨：并入的 curated 目标主题 ID
         */
        private Long mergeTargetTopicId;

        /**
         * PROMOTE 轨：稳定 slug（替换 prop- 哈希；不得与现有 slug 冲突）
         */
        private String promoteSlug;

        /**
         * PROMOTE 轨：正式三维分组 FACULTY / RESEARCH / STUDENT_AFFAIRS
         */
        private String promoteGroup;

        /**
         * PROMOTE 轨可选：中文主题名（转正进目录的策展名，不给保留现值）
         */
        private String promoteNameZh;

        /**
         * PROMOTE 轨可选：英文主题名（不给保留现值）
         */
        private String promoteNameEn;

        /**
         * PROMOTE 轨可选：中文界定描述（不给保留现值）
         */
        private String promoteDescriptionZh;

        /**
         * PROMOTE 轨可选：英文界定描述（不给保留现值）
         */
        private String promoteDescriptionEn;

        /**
         * 处置理由（留痕落行，逐条落票面证据包）
         */
        private String reason;
    }
}

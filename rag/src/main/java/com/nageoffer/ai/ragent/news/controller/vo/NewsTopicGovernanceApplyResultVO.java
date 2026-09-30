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

package com.nageoffer.ai.ragent.news.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 主题提案批量处置结果（#202）
 *
 * <p>逐条 outcome：APPLIED=本轮落处置 / SKIPPED=已处于目标终态（幂等重复提交）；
 * 任一指令非法（轨别缺参/阈值未达/slug 冲突/改判冲突）整批拒绝（全批原子，
 * appliedCount 归零），错误信息走统一异常面。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NewsTopicGovernanceApplyResultVO {

    /**
     * 本轮实际落处置条数
     */
    private int appliedCount;

    /**
     * 幂等跳过条数（已处于目标终态）
     */
    private int skippedCount;

    /**
     * 逐条结果
     */
    private List<DispositionOutcome> results;

    /**
     * 单条结果
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DispositionOutcome {

        /**
         * 提案行 ID
         */
        private Long topicId;

        /**
         * 处置动作（MERGE/PROMOTE/REJECT）
         */
        private String action;

        /**
         * APPLIED / SKIPPED
         */
        private String outcome;

        /**
         * 结果明细（SKIPPED 说明已处终态与去向）
         */
        private String detail;
    }
}

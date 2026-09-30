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

package com.nageoffer.ai.ragent.calendar.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 校历源审计载荷（#193 可选纯读核对视图，admin 面；零写径）。
 *
 * <p>与公开面 {@link KeyDateSourceVO} 的差异：透出 lastDiag（最近一轮有界
 * 诊断——完整发布行数（解析行数+upsert/撤回/恢复/不变计数）或退化原因摘要，
 * 即合同「抓取轮日志」的落库载体，由 #192 摄取面写入、本视图只读）与
 * 隔离/探测计数、该源事件行数分布。不含 last_complete_snapshot 原文
 * （体积大且含全量指纹，核对入口有 diag 即可定位）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeyDateAuditVO {

    private String sourceKey;

    private String sourceUrl;

    /** writer / verifier */
    private String role;

    /** 人工启停（true=参与同步） */
    private boolean enabled;

    /** active / auto_isolated（状态机落库值） */
    private String autoState;

    private Integer degradedStreak;

    private Integer probeOkStreak;

    private String coverageAcademicYear;

    private LocalDateTime lastSuccessAt;

    /** 最近一轮诊断（解析行数+失败计数/退化原因；退化不覆盖快照只写此列） */
    private String lastDiag;

    private LocalDateTime updatedAt;

    /** 该源 published 事件行数 */
    private long publishedCount;

    /** 该源 withdrawn 事件行数（撤回保留 UID 与历史） */
    private long withdrawnCount;

    /** 该源 archived 事件行数 */
    private long archivedCount;
}

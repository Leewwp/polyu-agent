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

package com.nageoffer.ai.ragent.calendar.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 校历源状态实体（t_key_date_source，合同§6）。
 *
 * <p>人工启停（enabled=false=manual_disabled 语义，不自动复活）与自动隔离
 * （auto_state=auto_isolated，日级只读探测、两次完整探测复归）严格区分——
 * 两者的恢复路径不同是 r3 相对 r2 的关键修订（r2 三败永久 disabled 会造成
 * 13 天不复归）。last_success_at 只在完整版本原子发布后刷新；退化只写诊断。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_key_date_source")
public class KeyDateSourceDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * cal-academic-calendar 等五源
     */
    private String sourceKey;
    private String sourceUrl;

    /**
     * writer=权威写者（四）/ verifier=纯校验源（一，零事件写径）
     */
    private String role;

    /**
     * 人工启停：1=启用 / 0=停用（manual_disabled；不自动复活）
     */
    private String enabled;

    /**
     * active / auto_isolated（自动隔离状态机）
     */
    private String autoState;

    private Integer degradedStreak;
    private Integer probeOkStreak;

    /**
     * 最近完整候选的覆盖学年（覆盖域证据）
     */
    private String coverageAcademicYear;

    /**
     * 最近完整同步时间（≠内容更新时间；退化轮不刷新）
     */
    private LocalDateTime lastSuccessAt;

    /**
     * 最后完整候选版本快照（JSON 轻量指纹）；退化不覆盖
     */
    private String lastCompleteSnapshot;

    /**
     * 有界诊断（最近一轮 未知/跳过/discrepancy 摘要；不保凭据）
     */
    private String lastDiag;

    private LocalDateTime updatedAt;
}

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

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 校历关键日期事件实体（t_key_date，#192 合同 r3）。
 *
 * <p>身份=uid（六段语义数组 SHA-256，日期/标题不入键）——语义五列落库便于排查
 * 与重建。PG DATE 一律 LocalDate 映射（String↔DATE 必崩判例）；fuzzy 不保留伪
 * 精确日期（check 约束兜底）。published/withdrawn/archived 分离；改期/恢复同
 * UID 修订 revision，撤回不物理删除。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_key_date")
public class KeyDateDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * SHA-256(六段身份数组) 小写 64 hex，UNIQUE；ICS 前缀由导出层拼接
     */
    private String uid;

    private String academicYear;
    /**
     * AY / S1 / S2 / SU
     */
    private String term;
    private String eventCode;
    private String audienceCode;
    private String semanticSlot;

    private String sourceKey;
    private String sourceUrl;

    private String titleEn;
    /**
     * 规则词表中文标题；缺词 NULL=查询侧回退英文（零 LLM）
     */
    private String titleZh;
    /**
     * 官方人群限制原文（不得省略）
     */
    private String audienceText;

    /**
     * 原文出处（合并事件为多片段拼接）
     */
    private String rawText;
    /**
     * 出处 locator 列表（逗号分隔，如 r12,r14）
     */
    private String provenance;

    /**
     * exact-day / exact-range / onwards / fuzzy
     */
    private String precision;
    private LocalDate dateStart;
    private LocalDate dateEnd;
    /**
     * 模糊窗原文桶（precision=fuzzy 时非空）
     */
    private String fuzzyHint;

    /**
     * published / withdrawn / archived
     */
    private String status;

    private Integer revision;
    /**
     * 最近一次变更摘要（有界）
     */
    private String changeSummary;

    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
    private LocalDateTime withdrawnAt;

    /**
     * NULL(从未导出) / exported / cancelled（#194 ICS 消费的最小承载）
     */
    private String icsExportState;
    private String icsLastDates;
}

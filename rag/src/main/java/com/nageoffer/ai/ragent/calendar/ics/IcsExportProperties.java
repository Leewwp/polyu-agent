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

package com.nageoffer.ai.ragent.calendar.ics;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 校历 .ics 导出参数（#194；绑定 {@code rag.calendar.ics.*}，先例
 * {@code KeyDateDisplayProperties}——阈值不硬编码魔数）。
 *
 * <p>VALARM 白名单是<b>事件码封闭集</b>（票面：仅明确 deadline 事件，非整个
 * fee 类别——fee-notification 通知/假期/考试期等一律不挂提醒）；扩展白名单=
 * 修改本默认值或配置覆盖，属显式合同变更。
 */
@Data
@Configuration
@ConfigurationProperties("rag.calendar.ics")
public class IcsExportProperties {

    /**
     * VALARM deadline 事件码白名单（票面例举：缴费截止/成绩发布）。默认三项：
     * fee-deadline=学费缴费截止、results-subject-release/results-overall-release
     * =科目/总评成绩发布。fee-notification（同 fee 类别但非截止）刻意不在列
     * ——票面反例。
     */
    private Set<String> deadlineEventCodes = new LinkedHashSet<>(Set.of(
            "fee-deadline", "results-subject-release", "results-overall-release"));

    /**
     * 截止提醒前置天数（VALARM TRIGGER=-P{n}D，相对 DTSTART）。默认 7 天
     * （缴费/成绩发布留出处理窗口）；0=当天提醒。观察项：手机实际提醒及时性
     * 取决于客户端刷新设置，服务端不承诺必达（票面 R4 口径）。
     */
    private int alarmLeadDays = 7;

    /**
     * 取消组件留存天数（票面合同下限 ≥90 天：STATUS:CANCELLED 组件自撤回日
     * 起至少保留 90 天供订阅端刷新消化）。锚点=withdrawn_at；精确变模糊的取消
     * 无撤回时间列，锚点回退 last_seen_at（同轮刷新——实际留存 ≥90 天口径仍
     * 成立，见服务实现注释）。配置低于 90 按 90 执行（合同下限钳制）。
     */
    private int cancelRetentionDays = 90;

    /**
     * 生效留存天数（合同下限 90 钳制）
     */
    int effectiveRetentionDays() {
        return Math.max(90, cancelRetentionDays);
    }
}

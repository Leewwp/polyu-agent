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

package com.nageoffer.ai.ragent.calendar.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 校历展示面参数（#193；绑定 {@code rag.calendar.display.*}，先例
 * NewsFetchProperties——阈值不硬编码魔数）。
 *
 * <p>注意「过期 N 天自动归档」是<b>查询侧分类</b>：每次请求以 HKT 当日为锚
 * 重算 recent/archived 分段，不写回 t_key_date.status（铁律：只读消费；
 * DB archived 状态归摄取面生命周期管理）。跨学年旧行靠 archived-cap 封顶
 * 防无界返回，archivedTotal 另给全量计数。
 */
@Data
@Configuration
@ConfigurationProperties("rag.calendar.display")
public class KeyDateDisplayProperties {

    /**
     * 过期 N 天自动归档：事件结束日距今超过 N 天（严格大于）进 archived 段；
     * 恰 N 天仍在 recentPast 段（边界含端）。默认 14 天=学生仍可能关心的回看窗口。
     */
    private int archiveAfterDays = 14;

    /**
     * archived 段返回条数上限（多年累积防无界载荷；archivedTotal 不受此限）
     */
    private int archivedCap = 120;
}

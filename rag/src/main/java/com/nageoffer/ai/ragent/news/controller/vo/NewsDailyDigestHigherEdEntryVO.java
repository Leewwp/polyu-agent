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

/**
 * 日报「八校/高教」独立栏目条目 VO（#412 B 线，读取期下架复检后的生效口径）
 *
 * <p>条目=B 线注册源（JUPAS/UGC 等，#410）的过门进刊快照——八校联校课程与
 * 交换 / JUPAS 与研究生政策 / UGC 高教政策三类横向信息；source 徽章字段直映
 * 快照列（来源可辨识，不与理大本校新闻混淆）。窗口零条目=空列表，前端整段
 * 隐藏不渲染空壳。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsDailyDigestHigherEdEntryVO {

    /**
     * 溯源条目 id（点击进 /news/{id} 详情；源行被保留清理后详情 404 属预期）
     */
    private Long itemId;

    /**
     * 栏内序（1 起，发布时间倒序——与主流程条目同一排序口径）
     */
    private Integer seq;

    /**
     * 原文 URL 快照（JUPAS/UGC 官方原文永久外链）
     */
    private String url;

    private String titleZh;

    private String titleEn;

    private String summaryZh;

    private String summaryEn;

    /**
     * 固定 8 类+other 快照
     */
    private String category;

    /**
     * 原文发布时间快照（ISO 时间串）
     */
    private java.util.Date publishTime;

    /**
     * 发布时间精度快照（#275）：date=只显日期（不显示 23:59:59 代表值时刻）
     */
    private String publishTimePrecision;

    /**
     * 信源元数据快照（来源标识徽章）
     */
    private NewsSourceMetaVO source;
}

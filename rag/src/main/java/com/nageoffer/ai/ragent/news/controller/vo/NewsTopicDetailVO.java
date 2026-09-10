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

import java.util.Date;

/**
 * 主题详情页载荷（doc 19 §6：面包屑返回+名称+界定描述+（条数·更新时间）统计+
 * 近期焦点（主题内热榜，前端按 heat 排序取 Top2）+最新动态全量）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsTopicDetailVO {

    /**
     * 主题元数据（含条目计数）
     */
    private NewsTopicVO topic;

    /**
     * 主题内最近一条发布时刻（「更新时间」统计位；空主题为 null）
     */
    private Date lastPublishTime;

    /**
     * 主题内条目分页（发布时间倒序；「近期焦点」由前端在本页内按 heat 取 Top2）
     */
    private NewsPageVO items;
}

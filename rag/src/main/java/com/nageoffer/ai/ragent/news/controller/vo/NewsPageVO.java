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
 * 公开资讯分页载荷（doc 19 §13-9：V1 首屏 20 条+加载更多，不做页码跳转）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsPageVO {

    /**
     * 当前页条目
     */
    private List<NewsItemVO> records;

    /**
     * 过滤条件下总条数
     */
    private Long total;

    /**
     * 当前页码（1 起）
     */
    private Long page;

    /**
     * 页大小
     */
    private Long size;

    /**
     * 是否还有下一页（加载更多按钮的显隐依据）
     */
    private Boolean hasMore;
}

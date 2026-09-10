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

package com.nageoffer.ai.ragent.news.service;

import com.nageoffer.ai.ragent.news.controller.vo.NewsItemVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsPageVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsTopicDetailVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsTopicVO;

import java.util.List;

/**
 * 公开资讯查询服务（U12-A，只读面）
 *
 * <p>服务公开四端点的全部读语义；条目可见性=status published
 * （下架止血即置 hidden，列表/热点/主题计数同步消失）。
 */
public interface NewsQueryService {

    /**
     * 已发布条目分页（发布时间倒序；category 空=全部资讯态）
     *
     * @param category 固定 8 类之一，blank 则不过滤
     * @param page     页码（1 起）
     * @param size     页大小（钳制到上限）
     */
    NewsPageVO listPublished(String category, int page, int size);

    /**
     * 热点榜 Top N（热度倒序，同分并列按发布时间倒序；热度模型 A4 灌值前全 0 即时间序）
     */
    List<NewsItemVO> listHot(int limit);

    /**
     * 主题目录（curated 词表三维分组，含已发布条目计数；AI 提案不进目录）
     */
    List<NewsTopicVO> listCuratedTopics();

    /**
     * 主题详情（元数据+条数·更新时间统计+条目分页）
     *
     * @param slug 主题稳定标识；不存在或非策展主题抛 ClientException
     */
    NewsTopicDetailVO getTopicDetail(String slug, int page, int size);
}

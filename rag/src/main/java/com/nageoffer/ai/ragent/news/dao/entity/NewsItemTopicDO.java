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

package com.nageoffer.ai.ragent.news.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 条目-主题关联实体（U12-A，doc 19 §3）
 *
 * <p>复合主键 (item_id, topic_id) 表（无单列 @TableId，主键语义由 DDL 承载），
 * 仅走 insert / Wrapper 查询，不使用 selectById / updateById。
 * 保留期清理随 t_news_item 级联删除（ON DELETE CASCADE）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_news_item_topic")
public class NewsItemTopicDO {

    /**
     * 条目 ID（t_news_item.id）
     */
    private Long itemId;

    /**
     * 主题 ID（t_news_topic.id）
     */
    private Long topicId;
}

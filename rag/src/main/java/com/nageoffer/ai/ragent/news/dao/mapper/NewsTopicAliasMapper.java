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

package com.nageoffer.ai.ragent.news.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.ragent.news.dao.entity.NewsTopicAliasDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 主题别名账 Mapper（#202）
 *
 * <p>别名入账（治理侧，alias_key 同键覆盖更新）与别名拦截查询（富化消费侧，
 * {@code NewsEnrichService#linkTopics} 词表未命中后按 alias_key 查一次）共用本 Mapper。
 */
@Mapper
public interface NewsTopicAliasMapper extends BaseMapper<NewsTopicAliasDO> {
}

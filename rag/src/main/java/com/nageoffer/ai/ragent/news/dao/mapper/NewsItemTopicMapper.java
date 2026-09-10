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
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.news.dao.dto.TopicPublishedCountDTO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemTopicDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.List;

/**
 * 条目-主题关联 Mapper
 *
 * <p>关联表无单列主键，只提供 BaseMapper 的 insert / Wrapper 查询；
 * 公开查询面的三个 join 语义（计数 / 最近发布时刻 / 主题内条目分页）以
 * 注解 SQL 承载——条目计数只数 status=published，下架条目即从目录计数与详情页消失。
 */
@Mapper
public interface NewsItemTopicMapper extends BaseMapper<NewsItemTopicDO> {

    /**
     * 按主题统计已发布条目数（LEFT 语义：无条目的主题不返回行，调用侧以 0 兜底）
     */
    @Select("SELECT iit.topic_id AS topicId, COUNT(*) AS cnt "
            + "FROM t_news_item_topic iit "
            + "JOIN t_news_item ii ON ii.id = iit.item_id "
            + "WHERE ii.status = 'published' "
            + "GROUP BY iit.topic_id")
    List<TopicPublishedCountDTO> countPublishedByTopic();

    /**
     * 主题内最近一条已发布条目的发布时刻（空主题返回 NULL）
     */
    @Select("SELECT MAX(ii.publish_time) "
            + "FROM t_news_item_topic iit "
            + "JOIN t_news_item ii ON ii.id = iit.item_id "
            + "WHERE iit.topic_id = #{topicId} AND ii.status = 'published'")
    Date selectLastPublishTime(@Param("topicId") Long topicId);

    /**
     * 主题内已发布条目分页（发布时间倒序，同刻并列按 id 倒序稳定排序）；
     * 首参为 MyBatis-Plus 分页对象，由 PaginationInnerInterceptor 生成分页 SQL
     */
    @Select("SELECT ii.* FROM t_news_item ii "
            + "JOIN t_news_item_topic iit ON iit.item_id = ii.id "
            + "WHERE iit.topic_id = #{topicId} AND ii.status = 'published' "
            + "ORDER BY ii.publish_time DESC, ii.id DESC")
    IPage<NewsItemDO> selectPublishedPageByTopic(IPage<NewsItemDO> page, @Param("topicId") Long topicId);
}

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

import com.nageoffer.ai.ragent.news.controller.vo.NewsHotRankEntryVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsItemVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsPageVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsTopicDetailVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsTopicVO;

import java.time.LocalDate;
import java.util.List;

/**
 * 公开资讯查询服务（只读面）
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
     * 已发布条目分页（多主题过滤档，「只看关注」#215 调整）：在 category 语义之上
     * 限定条目至少挂载 topicSlugs 之一（解析到 curated+active 主题；全部未解析=空页）
     *
     * <p>服务端过滤为分页正确性所必需——客户端过滤全局流会产生页内漏配（/topics/ai
     * 44 vs 9 计数错位判例）；分页/排序与无过滤档完全一致。
     *
     * @param topicSlugs 主题 slug 集（null/空=不过滤，走原语义）
     */
    NewsPageVO listPublished(String category, List<String> topicSlugs, int page, int size);

    /**
     * 热点榜 Top N（故事线粒度：热度倒序，同分按最新报道倒序；一簇一条、
     * 附标签与信源名单。热度全 0 时按发布时间兜底序）
     */
    List<NewsHotRankEntryVO> listHot(int limit);

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

    /**
     * 资讯详情单条：分享/直链场景单条可达，不用「list 前端过滤」凑合
     *
     * <p>复用 NewsItemVO+topics 批查装配；仅 published 可见（下架/不存在同形
     * ClientException，不泄漏存在性）。
     *
     * @param id 条目 ID；非正数或不存在/未发布抛 ClientException
     */
    NewsItemVO getPublishedDetail(long id);

    /**
     * 全局检索：标题+摘要四列 ILIKE，仅 published
     *
     * <p>排序：{@code time}（默认）=发布时间；{@code relevance}=标题命中优先、
     * 摘要命中次之、同分按时间（正文未存储，无正文加权）。
     * T21：{@code order}=asc/desc（默认 desc）两分支全贯——time 分支直接反序，
     * relevance 分支桶序与桶内 tie-break 均随向；category 可选，限定检索范围（eq 谓词同 list）。
     * 分页语义与 list 一致。
     *
     * @param q        关键词；blank 返回空页（前端 q 空时不进检索态，此处为契约兜底）
     * @param sort     time / relevance，其余取值按 time 处理
     * @param order    asc / desc，其余取值按 desc 处理
     * @param category 可选分类过滤，blank=全部理大资讯
     */
    NewsPageVO searchPublished(String q, String sort, String order, String category, int page, int size);

    /**
     * MCP 出口受限检索（#182 r3 §MCP / #214）：关键词 × 主题 slug × 时间窗三元过滤，
     * 仅已发布可见条目——与 {@link #searchPublished} 同一条统一公开资格判据
     * （status=published 且过发布门），下架/未过门条目一律隔离，不得另写可见性规则。
     *
     * <p>与前端检索口的分工：本口是机器消费的过滤面（发布时间倒序、无 relevance 档、
     * 分页钳制同 list），供 mcp-server 无数据库层远程调用；三个过滤维度全部可独立生效，
     * 全部缺省时语义等价 listPublished（时间倒序首页）。摘要由 AI 生成、以原文为准的
     * 声明义务在消费侧（mcp-server 出口文案）。
     *
     * @param q        关键词；blank 不过滤（标题+摘要四列 ILIKE，转义同 searchPublished）
     * @param topicSlug 主题 slug；blank 不过滤；非策展（curated+active）主题抛
     *                  ClientException「主题不存在」（与 getTopicDetail 同形，不泄漏存在性）
     * @param dateFrom 发布时间窗起（含当日）；null 不过滤
     * @param dateTo   发布时间窗止（含当日）；null 不过滤；from 晚于 to 抛 ClientException
     */
    NewsPageVO searchPublishedForMcp(String q, String topicSlug, LocalDate dateFrom, LocalDate dateTo, int page, int size);
}

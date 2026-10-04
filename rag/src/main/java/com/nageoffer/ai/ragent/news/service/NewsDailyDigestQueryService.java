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

import java.time.LocalDate;
import java.util.List;

import com.nageoffer.ai.ragent.news.controller.vo.NewsDailyDigestSummaryVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsDailyDigestVO;

/**
 * 资讯日报公开读取服务（#212）——<b>零 LLM</b>：页面/RSS 请求只读快照表，
 * 不触发任何模型调用（结构保证：本服务不依赖 LLMService/NewsLlmBudgetService
 * 等付费组件；导语在生成期一次性产出，读取期只做快照过滤与模板回退）。
 */
public interface NewsDailyDigestQueryService {

    /**
     * 近期日报目录（digest_date 倒序；日期+条数+导语产出方式+每期首条可见标题
     * ——不携带导语正文，规避隐藏条目残留问题的同时保持列表轻量）
     *
     * <p>firstTitle 口径（#240）：批量一次 IN 查询取各期 published 可见集中
     * seq 最小条（读取期下架复检同 {@link #getDetail}；空期两字段 null）。
     *
     * @param limit 条数（[1,90] 钳制，默认 30）
     */
    List<NewsDailyDigestSummaryVO> listRecent(int limit);

    /**
     * 某日期日报详情：刊头+快照条目（读取期主动下架复检后）
     *
     * <p>主动下架复检规则（零调用）：快照条目回查 t_news_item——源行<b>仍存在且
     * status 不为 published</b>（hidden/expired/archived）→ 该快照失格过滤；
     * 源行<b>已不存在</b>（90 天保留清理）→ 快照保留展示（快照独立性）。
     * 任一条目失格 → 导语失格：可见条数&gt;0 回退固定模板导语、=0 回退空刊模板
     * （均零新增模型调用）。
     *
     * @return null=该日期无刊（控制器同形「日报不存在」）
     */
    NewsDailyDigestVO getDetail(LocalDate digestDate);

    /**
     * RSS 2.0 渲染（零 LLM）：从详情 VO 确定性生成 XML 文本——页面/RSS 请求
     * 路径不触发任何模型调用（结构保证见实现类 javadoc）
     *
     * @param detail {@link #getDetail} 的非空结果
     */
    String renderRss(NewsDailyDigestVO detail);

    /**
     * 期级 RSS 渲染（#240，Q10——零 LLM）：订阅对象是「日报」这份<b>连续刊物</b>，
     * 每期一条 item（最近 30 期、日期倒序）——title=理大资讯日报 · 日期+头条标题、
     * description=生效导语（zh 口径）+可见条目标题简表、link=站内 /daily/{date}
     * 绝对 canonical、guid=期日期。空期条目保留并附休刊说明文案（每日 URL
     * 可预期是特性）；与 {@link #renderRss}（单刊条目 feed）同构组装与转义约定。
     */
    String renderIssuesRss();
}

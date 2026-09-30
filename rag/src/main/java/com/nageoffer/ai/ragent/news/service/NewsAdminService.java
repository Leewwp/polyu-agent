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

import com.nageoffer.ai.ragent.news.controller.vo.NewsLlmBudgetStatusVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsPipelineStatusVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsSourceHealthEventVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsSourceHealthVO;

import java.time.LocalDate;
import java.util.List;

/**
 * 资讯管理面服务
 *
 * <p>admin UI 前的最小止血通道：人工抽检发现坏摘要/坏条目时单条下架；
 * 预算护栏消耗状态查询（#184 验收口径：attempts 与成本双口径+降级事件）；
 * 管线状态与六口径日报（#185 验收口径：发现/准入/唯一内容/富化成功/公开展示/
 * 事件数+积压可见性）；源健康面板与停止/复归/探活事件流水（#186 验收口径：
 * 停用原因三分+探活状态+迁移判据可见性）。
 */
public interface NewsAdminService {

    /**
     * 单条下架（status=published → hidden）：列表/热点/主题计数与详情同步消失；
     * 幂等——已 hidden 的条目再次下架不报错
     *
     * @param id 条目 ID
     * @throws com.nageoffer.ai.ragent.framework.exception.ClientException 条目不存在
     */
    void hide(Long id);

    /**
     * 资讯 LLM 预算消耗状态（HKT 日/月双窗口）：attempts 与估算成本双口径+
     * 当日降级事件数+剩余额度——数据源 t_news_llm_receipt 聚合，重启不清零
     *
     * @return 当日/当月消耗与额度快照
     */
    NewsLlmBudgetStatusVO llmBudgetStatus();

    /**
     * 管线状态与六口径日报（#185）：按 HKT 日窗聚合 t_news_item——发现/准入/
     * 唯一内容/富化成功/公开展示/事件数（占位 0，#187 落地）+旧文归档/零调用
     * 回退/待富化积压/超龄终态/剩余准入额度
     *
     * @param date 报告日（HKT）；null=今天
     * @return 六口径与积压快照
     */
    NewsPipelineStatusVO pipelineStatus(LocalDate date);

    /**
     * 源健康面板（#186）：全量源行快照——停用原因三分（manual/auto/policy）、
     * 连续失败、最近六类结果、探活状态（连续成功/最近探活时刻/是否探活对象）、
     * 停止/复归时刻、allow-empty 命中
     *
     * @return 按源 key 升序的源健康行
     */
    List<NewsSourceHealthVO> sourceHealth();

    /**
     * 信源健康事件流水（#186：停止/复归记录可查）——isolated/policy_disabled/
     * probe_pass/probe_fail/recovered 按时间倒序
     *
     * @param limit 最近 N 条（调用方限幅）
     * @return 事件列表（含 sourceKey 还原）
     */
    List<NewsSourceHealthEventVO> sourceHealthEvents(int limit);
}

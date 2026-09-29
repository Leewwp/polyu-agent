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

import java.time.LocalDate;

/**
 * 资讯管理面服务
 *
 * <p>admin UI 前的最小止血通道：人工抽检发现坏摘要/坏条目时单条下架；
 * 预算护栏消耗状态查询（#184 验收口径：attempts 与成本双口径+降级事件）；
 * 管线状态与六口径日报（#185 验收口径：发现/准入/唯一内容/富化成功/公开展示/
 * 事件数+积压可见性）。源健康/停用原因面板归 #186，本面不含。
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
}

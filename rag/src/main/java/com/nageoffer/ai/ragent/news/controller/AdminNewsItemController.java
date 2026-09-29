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

package com.nageoffer.ai.ragent.news.controller;

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.news.controller.vo.NewsLlmBudgetStatusVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsPipelineStatusVO;
import com.nageoffer.ai.ragent.news.service.NewsAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 资讯管理面控制器（单条快速下架+预算护栏状态查询）
 *
 * <p>路径 /admin/** 已在 SaTokenConfig ADMIN_PATH_PATTERNS 清单内（admin 角色拦截
 * + 审计自动覆盖），无需新增白名单行；admin UI 归后续扩展，本控制器仅承载
 * 应急止血最小端点与 #184 预算验收查询端点。
 */
@RestController
@RequestMapping("/admin/news")
@RequiredArgsConstructor
public class AdminNewsItemController {

    private final NewsAdminService newsAdminService;

    /**
     * 单条下架：status published → hidden，列表/热点/主题计数同步消失，幂等
     */
    @PostMapping("/item/{id}/hide")
    public Result<Void> hide(@PathVariable Long id) {
        newsAdminService.hide(id);
        return Results.success(null);
    }

    /**
     * 资讯 LLM 预算消耗状态（#184 验收）：attempts 与估算成本双口径+当日降级
     * 事件数+剩余额度（HKT 日/月窗口，t_news_llm_receipt 聚合，重启不清零）
     */
    @GetMapping("/llm-budget")
    public Result<NewsLlmBudgetStatusVO> llmBudgetStatus() {
        return Results.success(newsAdminService.llmBudgetStatus());
    }

    /**
     * 管线状态与六口径日报（#185 验收）：发现/准入/唯一内容/富化成功/公开展示/
     * 事件数（占位 0）+归档/回退/积压/剩余准入额度——t_news_item 现推，重启不重置；
     * date 缺省=今天（HKT）
     */
    @GetMapping("/pipeline")
    public Result<NewsPipelineStatusVO> pipelineStatus(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Results.success(newsAdminService.pipelineStatus(date));
    }
}

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

import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.news.controller.request.NewsTopicGovernanceApplyRequest;
import com.nageoffer.ai.ragent.news.controller.vo.NewsTopicGovernanceApplyResultVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsTopicGovernanceEventVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsTopicProposalVO;
import com.nageoffer.ai.ragent.news.governance.NewsTopicGovernanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 主题提案治理管理面控制器（#202 三轨处置：merge 并入 / promote 转正 / reject 弃）
 *
 * <p>路径 /admin/** 已在 SaTokenConfig ADMIN_PATH_PATTERNS 清单内（admin 角色
 * 拦截 + ADMIN_AUDIT 自动审计覆盖），无需新增白名单行。三面：
 * <ol>
 *   <li>pending 提案列表（覆盖数+规则建议轨别——按建议或人工改轨）；</li>
 *   <li>批量应用（全批原子、可重复运行幂等、留痕落 t_news_topic_governance_event）；</li>
 *   <li>治理留痕流水（处置历史可复核）。</li>
 * </ol>
 * admin UI 归后续扩展，本控制器仅承载端点（票面：端点即可，无前端页面）。
 *
 * <p>装配口径（#206 注记）：本控制器不受 {@code rag.news.enabled} 门控（与
 * AdminNewsItemController 同例）——治理与留痕属 admin 运维审计面，资讯抓取/公开展示
 * 关停时处置历史仍须可复核；公开面与抓取面的开关门控在 PublicNewsController /
 * NewsFetchJob 各自装配。
 */
@RestController
@RequestMapping("/admin/news/topic")
@RequiredArgsConstructor
public class AdminNewsTopicController {

    private final NewsTopicGovernanceService governanceService;

    /**
     * 待审提案列表：curated=false AND status='active'，逐行覆盖数（item_refs）
     * 与规则建议轨别（promote/reject/review；近义判定归人工）
     */
    @GetMapping("/proposals")
    public Result<List<NewsTopicProposalVO>> pendingProposals() {
        return Results.success(governanceService.listPendingProposals());
    }

    /**
     * 批量应用处置：按列表建议或人工改轨逐条下发（MERGE/PROMOTE/REJECT+轨别参数+
     * 理由）；全批原子（任一非法整批拒绝）、同批重提交幂等（SKIPPED）；操作者取
     * 登录态 admin 账号落留痕
     */
    @PostMapping("/proposals/apply")
    public Result<NewsTopicGovernanceApplyResultVO> apply(@RequestBody NewsTopicGovernanceApplyRequest request) {
        LoginUser user = UserContext.get();
        String operator = user == null || user.getUsername() == null ? "unknown" : user.getUsername();
        return Results.success(governanceService.applyBatch(request.getDispositions(), operator));
    }

    /**
     * 治理留痕流水：merged/promoted/rejected 逐行留痕倒序（detail 含关联迁移/
     * 摘除计数与 slug 变更明细）；limit 缺省 50（服务端限幅 1..200）
     */
    @GetMapping("/events")
    public Result<List<NewsTopicGovernanceEventVO>> events(@RequestParam(defaultValue = "50") int limit) {
        return Results.success(governanceService.listGovernanceEvents(limit));
    }
}

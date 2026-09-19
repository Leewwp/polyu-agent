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

package com.nageoffer.ai.ragent.agent.share;

import com.nageoffer.ai.ragent.agent.share.vo.AgentShareAdminItemVO;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理员会话分享治理（处理违规/过期内容，issue #82）
 *
 * <p>路径 /admin/** 由 SaTokenConfig 管理面角色拦截器（ORDER_ADMIN_ROLE）强制 admin 角色，
 * 零新增鉴权代码。adminOverride 语义：撤销不受 owner 校验限制。
 */
@RestController
@RequestMapping("/admin/agent-share")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.share.enabled", havingValue = "true")
public class AdminAgentShareController {

    private final AgentConversationShareService shareService;

    /**
     * 全量分享列表（治理面）
     */
    @GetMapping
    public Result<List<AgentShareAdminItemVO>> listAll() {
        return Results.success(shareService.listAllForAdmin());
    }

    /**
     * 管理员下架分享
     */
    @DeleteMapping("/{token}")
    public Result<Void> revokeAsAdmin(@PathVariable String token) {
        // adminOverride=true 时服务端跳过 owner 归属校验（/admin/** 角色拦截器已保证 admin 角色）
        shareService.revokeShare(token, null, true);
        return Results.success();
    }
}

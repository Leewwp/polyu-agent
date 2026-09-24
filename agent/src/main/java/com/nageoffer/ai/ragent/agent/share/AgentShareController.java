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

import com.nageoffer.ai.ragent.agent.share.request.AgentShareCreateRequest;
import com.nageoffer.ai.ragent.agent.share.vo.AgentShareCreatedVO;
import com.nageoffer.ai.ragent.agent.share.vo.AgentShareMineItemVO;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent 会话只读分享控制器（登录面：创建/撤销/我的分享，issue #82）
 *
 * <p>feature flag：share.enabled=false 时整个 Bean 不装配（两粒度共用一开关，issue #124），端点 404。
 * 启用与过期终值由部署方把关。
 */
@RestController
@RequestMapping("/agent/share")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "share.enabled", havingValue = "true")
public class AgentShareController {

    private final AgentConversationShareService shareService;

    /**
     * 创建会话的只读分享快照（游客身份被服务层硬阻断，issue #91 增补）；
     * scope/anchor 向后兼容扩展见 issue #138（缺省=full 旧客户端零变化）
     */
    @PostMapping
    public Result<AgentShareCreatedVO> createShare(@RequestBody AgentShareCreateRequest request) {
        return Results.success(shareService.createShare(
                request.getConversationId(), UserContext.getUserId(), UserContext.getRole(),
                request.getScope(), request.getAnchorAssistantMessageId()));
    }

    /**
     * 撤销本人分享
     */
    @DeleteMapping("/{token}")
    public Result<Void> revokeShare(@PathVariable String token) {
        shareService.revokeShare(token, UserContext.getUserId(), false);
        return Results.success();
    }

    /**
     * 本人分享列表
     */
    @GetMapping("/mine")
    public Result<List<AgentShareMineItemVO>> listMine() {
        return Results.success(shareService.listMine(UserContext.getUserId()));
    }
}

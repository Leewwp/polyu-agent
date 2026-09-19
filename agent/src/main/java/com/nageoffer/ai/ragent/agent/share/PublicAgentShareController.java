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

import com.nageoffer.ai.ragent.agent.share.vo.PublicAgentShareVO;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话分享匿名读控制器（issue #82）
 *
 * <p>路径 /public/share/agent/** 落在 SaTokenConfig 登录拦截白名单既有模式
 * （/public/share/**）内，零新增鉴权代码；首发不收录立场与单条分享同款：
 * 分享页 meta robots noindex 双保险。
 */
@RestController
@RequestMapping("/public/share/agent")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.share.enabled", havingValue = "true")
public class PublicAgentShareController {

    private final AgentConversationShareService shareService;

    /**
     * 匿名读取会话分享快照（不存在/已撤销/已过期统一同一错误语义）
     */
    @GetMapping("/{token}")
    public Result<PublicAgentShareVO> getPublicShare(@PathVariable String token) {
        return Results.success(shareService.getPublicShare(token));
    }
}

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

package com.nageoffer.ai.ragent.rag.controller;

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.rag.controller.request.ShareCreateRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.ShareCreatedVO;
import com.nageoffer.ai.ragent.rag.controller.vo.ShareMineItemVO;
import com.nageoffer.ai.ragent.rag.service.AnswerShareService;
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
 * 公开答案分享控制器（登录面：创建/撤销/我的分享）
 *
 * <p>feature flag：rag.share.enabled=false（默认关）时整个 Bean 不装配，端点 404。
 * 启用与过期终值由部署方把关。
 */
@RestController
@RequestMapping("/share")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.share.enabled", havingValue = "true")
public class AnswerShareController {

    private final AnswerShareService answerShareService;

    /**
     * 创建单条回答的公开分享快照
     */
    @PostMapping
    public Result<ShareCreatedVO> createShare(@RequestBody ShareCreateRequest request) {
        return Results.success(answerShareService.createShare(request.getMessageId(), UserContext.getUserId()));
    }

    /**
     * 撤销本人分享
     */
    @DeleteMapping("/{token}")
    public Result<Void> revokeShare(@PathVariable String token) {
        answerShareService.revokeShare(token, UserContext.getUserId(), false);
        return Results.success();
    }

    /**
     * 本人分享列表
     */
    @GetMapping("/mine")
    public Result<List<ShareMineItemVO>> listMine() {
        return Results.success(answerShareService.listMine(UserContext.getUserId()));
    }
}

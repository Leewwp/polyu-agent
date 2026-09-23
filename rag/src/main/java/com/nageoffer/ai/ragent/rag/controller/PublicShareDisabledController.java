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

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.convention.Result;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开分享关闭态兜底：flag 关（默认）时 PublicShareController 不装配，
 * 本兜底接管同路径，抛与启用态「链接无效」完全同源的 ClientException——
 * 既避免 no-handler 落到系统错误（B000001）产生运维噪音，也不泄漏功能开关状态。
 */
@RestController
@RequestMapping("/public/share")
@ConditionalOnProperty(name = "share.enabled", havingValue = "false", matchIfMissing = true)
public class PublicShareDisabledController {

    @GetMapping("/{token}")
    public Result<Void> disabled(@PathVariable String token) {
        throw new ClientException("分享链接无效或已撤销");
    }
}

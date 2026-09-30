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

package com.nageoffer.ai.ragent.calendar.controller;

import com.nageoffer.ai.ragent.calendar.controller.vo.KeyDateBoardVO;
import com.nageoffer.ai.ragent.calendar.service.KeyDateQueryService;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开校历关键日期控制器（#193 查询口薄壳；服务层才是查询入口——日期 MCP
 * 随 #182 接口复用 {@link KeyDateQueryService}，不另写判据）。
 *
 * <p>路径在 SaTokenConfig 登录拦截白名单（/public/calendar/**），匿名可读
 * （首页卡片+关键日期页消费）。flag rag.calendar.enabled 关（默认）时不装配，
 * 由 {@link PublicCalendarDisabledController} 孪生兜底 404——与 /public/news
 * 同范式。零 LLM、只读，无任何写径。
 */
@RestController
@RequestMapping("/public/calendar")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.calendar.enabled", havingValue = "true")
public class PublicKeyDateController {

    private final KeyDateQueryService keyDateQueryService;

    /**
     * 关键日期看板：临近度排序+过期 N 天归档分段+源三态（正常/退化保留最后
     * 完整版本/隔离）+覆盖学年与最近完整同步时间；首页卡片取
     * currentAndUpcoming 前 K 条即同一序。
     */
    @GetMapping("/key-dates")
    public Result<KeyDateBoardVO> keyDates() {
        return Results.success(keyDateQueryService.board());
    }
}

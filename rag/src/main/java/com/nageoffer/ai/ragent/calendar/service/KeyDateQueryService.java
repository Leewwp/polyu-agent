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

package com.nageoffer.ai.ragent.calendar.service;

import com.nageoffer.ai.ragent.calendar.controller.vo.KeyDateAuditVO;
import com.nageoffer.ai.ragent.calendar.controller.vo.KeyDateBoardVO;

import java.util.List;

/**
 * 校历关键日期查询服务（#193 独立查询口的服务层，只读消费 t_key_date /
 * t_key_date_source，零 LLM）。
 *
 * <p>本接口是后续日期 MCP（随 #182 接口）的<b>同一查询入口</b>：MCP 工具与
 * 公开 controller 都复用 {@link #board()}，不得另写可见性判据或排序逻辑
 * （先例：NewsQueryService 统一公开资格注释）。分类锚点=Asia/Hong_Kong
 * （与摄取调度同时区口径）；「过期 N 天归档」为查询侧分类，不写库。
 */
public interface KeyDateQueryService {

    /**
     * 关键日期看板（临近度排序+过期分段+源三态）。
     *
     * <p>可见性：仅 status=published 事件进入展示段（withdrawn 保留 UID 与
     * 历史但不可见；archived DB 状态按日期归入展示 archived 段——跨学年旧
     * 行不冒充最新、正常过期不代表官方取消）。
     */
    KeyDateBoardVO board();

    /**
     * 纯读核对视图（admin 面，零写径）：逐源透出最近一轮诊断（解析行数+
     * 失败计数/退化原因——#192 摄取面写入的 last_diag）与事件行数分布。
     */
    List<KeyDateAuditVO> auditSources();
}

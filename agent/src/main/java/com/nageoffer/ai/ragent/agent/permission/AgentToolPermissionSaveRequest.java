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

package com.nageoffer.ai.ragent.agent.permission;

import lombok.Data;

/**
 * 工具权限矩阵管理端保存参数（#487 验收 9）：id 空=新增，非空=更新；
 * role/decision/tool_id 合法性由服务层校验（admin/user/guest × 精确名或 mcp:* × ALLOW/ASK/DENY）
 */
@Data
public class AgentToolPermissionSaveRequest {

    /**
     * 更新时必填
     */
    private String id;

    /**
     * admin/user/guest
     */
    private String role;

    /**
     * 精确 MCP 工具 id，或通配 mcp:*
     */
    private String toolId;

    /**
     * ALLOW/ASK/DENY
     */
    private String decision;

    /**
     * 缺省 1（生效）
     */
    private Integer enabled;

    private String description;
}

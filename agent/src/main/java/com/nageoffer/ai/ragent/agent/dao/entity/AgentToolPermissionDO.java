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

package com.nageoffer.ai.ragent.agent.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 工具权限矩阵（#487 t_agent_tool_permission）：role×tool_id×decision 声明式配置，
 * 每 run 会话级编译进权限引擎；role 按字符串校验三值，不强依赖 UserRole 枚举
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_agent_tool_permission")
public class AgentToolPermissionDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * admin/user/guest
     */
    private String role;

    /**
     * MCP 工具 id；通配 mcp:* = 当前挂载的全部 MCP 工具（编译期展开）
     */
    private String toolId;

    /**
     * ALLOW/ASK/DENY
     */
    private String decision;

    /**
     * 1 生效 0 停用；停用行不进编译快照
     */
    private Integer enabled;

    private String description;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;
}

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

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * Agent 会话只读分享快照实体（issue #82）
 *
 * <p>不可变快照：创建时对会话标题与白名单消息对做值复制，此后原始会话继续对话、
 * 修改、删除均不影响已分享内容（冻结语义）；公开读只读本表、永不回链
 * t_agent_conversation / t_agent_message。不保存用户名、邮箱、头像、思考内容、
 * 工具轨迹、原始 IP（隐私负面清单）。克隆 t_answer_share 模式。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName(value = "t_agent_conversation_share", autoResultMap = true)
public class AgentConversationShareDO {

    /**
     * 主键 ID，雪花算法
     */
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 分享 token：SecureRandom 32 字节 Base64URL（43 字符），加密随机不可枚举
     */
    private String token;

    /**
     * 创建者用户 ID；仅用于归属校验与撤销/管理面治理，公开载荷绝不返回
     */
    private String ownerUserId;

    /**
     * 源会话业务 ID（仅用于撤销/我的列表溯源，不进公开载荷）
     */
    private String conversationId;

    /**
     * 会话标题快照（值复制）
     */
    private String title;

    /**
     * 白名单消息快照（有序数组：role/content/createTime，经 typeHandler 落 JSONB）
     */
    @TableField(typeHandler = AgentShareSnapshotListTypeHandler.class)
    private List<AgentShareSnapshotItem> messages;

    /**
     * 内容语言启发标记（zh/en），决定分享页默认展示语言
     */
    private String lang;

    /**
     * 内容/知识版本标记（agent.share.content-version）
     */
    private String contentVersion;

    /**
     * 状态：ACTIVE / REVOKED
     */
    private String status;

    /**
     * 过期时刻；NULL 即不过期（终值由部署方确定）
     */
    private Date expireTime;

    /**
     * 撤销时刻
     */
    private Date revokedTime;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    /**
     * 逻辑删除
     */
    @TableLogic
    private Integer deleted;
}

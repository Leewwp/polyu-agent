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

package com.nageoffer.ai.ragent.rag.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.nageoffer.ai.ragent.framework.convention.SourceRef;
import com.nageoffer.ai.ragent.knowledge.dao.handler.SourceRefListTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * 公开答案分享快照实体
 *
 * <p>不可变 Q&A 快照：创建时对 question/answer/citations 做值复制，
 * 公开读只读本表、绝不回链 t_message（原消息删除/编辑不影响快照）。
 * 不保存用户名、邮箱、头像、思考内容、工具轨迹、原始 IP（隐私负面清单）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName(value = "t_answer_share", autoResultMap = true)
public class AnswerShareDO {

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
     * 创建者用户 ID；仅用于归属校验与撤销，公开载荷绝不返回
     */
    private String ownerUserId;

    /**
     * 快照来源 assistant 消息 ID（内部溯源字段，公开不返回）
     */
    private String messageId;

    /**
     * 快照来源会话 ID（内部溯源字段，公开不返回）
     */
    private String conversationId;

    /**
     * 问题快照（值复制自 reply_to_message_id 前驱 user 消息）
     */
    private String question;

    /**
     * 回答 Markdown 快照（值复制自 assistant 消息 content）
     */
    private String answerMd;

    /**
     * 结构化官方引用快照
     */
    @TableField(typeHandler = SourceRefListTypeHandler.class)
    private List<SourceRef> citations;

    /**
     * 问题语言快照（zh/en），决定分享页默认展示语言
     */
    private String lang;

    /**
     * 内容/知识版本标记（rag.share.content-version）
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

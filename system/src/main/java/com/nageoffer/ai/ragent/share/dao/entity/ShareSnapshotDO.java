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

package com.nageoffer.ai.ragent.share.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.nageoffer.ai.ragent.share.dao.handler.JsonbStringTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 统一分享快照实体（issue #124）：两种粒度合表（kind 判别），payload 为不透明
 * JSONB（module 零解析，类型与序列化归粒度 adapter）。不可变快照语义：创建时值复制，
 * 公开读只读本表；不含用户名/邮箱/思考内容/工具轨迹/IP（隐私负面清单）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName(value = "t_share_snapshot", autoResultMap = true)
public class ShareSnapshotDO {

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
     * 创建者用户 ID；仅用于归属校验与撤销/治理，公开载荷绝不返回
     */
    private String ownerUserId;

    /**
     * 快照粒度判别：answer / conversation
     */
    private String kind;

    /**
     * 来源会话 ID（内部溯源字段，公开不返回）
     */
    private String conversationId;

    /**
     * 语言启发标记（zh/en），决定分享页默认展示语言
     */
    private String lang;

    /**
     * 状态：ACTIVE / REVOKED
     */
    private String status;

    /**
     * 过期时刻；NULL 即不过期
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

    /**
     * 不透明载荷 JSON（answer=messageId/question/answerMd/citations/contentVersion；
     * conversation=title/messages/contentVersion；module 零解析）
     */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String payload;
}

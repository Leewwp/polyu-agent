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

package com.nageoffer.ai.ragent.share;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 公开读视图（readByToken 的返回）：只含公开面字段，payload 为不透明 JSON 字符串，
 * 由各粒度 adapter 自行反序列化并投影为对外的 VO（身份/溯源字段不存在于本类型）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SharePublicView {

    /**
     * 快照粒度（adapter 按它选载荷类型）
     */
    private ShareKind kind;

    /**
     * 语言启发标记（zh/en），决定分享页默认展示语言
     */
    private String lang;

    /**
     * 不透明载荷 JSON（含粒度侧 contentVersion；module 零解析）
     */
    private String payload;

    /**
     * 快照创建时间
     */
    private Date createTime;

    /**
     * 过期时刻；NULL=不过期
     */
    private Date expireTime;
}

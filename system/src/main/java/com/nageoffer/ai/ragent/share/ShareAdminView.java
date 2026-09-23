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
 * 管理面列表视图（adminList 的返回）：额外携带 ownerUserId 供治理定位，
 * payload 不透明，adapter 投影展示字段
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShareAdminView {

    /**
     * 分享 token
     */
    private String token;

    /**
     * 快照粒度
     */
    private ShareKind kind;

    /**
     * 创建者用户 ID（治理定位用，公开载荷绝不返回）
     */
    private String ownerUserId;

    /**
     * 状态：ACTIVE / REVOKED
     */
    private String status;

    /**
     * 不透明载荷 JSON
     */
    private String payload;

    /**
     * 过期时刻；NULL=不过期
     */
    private Date expireTime;

    /**
     * 创建时间
     */
    private Date createTime;
}

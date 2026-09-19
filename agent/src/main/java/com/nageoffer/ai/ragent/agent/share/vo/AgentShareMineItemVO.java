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

package com.nageoffer.ai.ragent.agent.share.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 「我的分享」列表项（管理面治理列表复用本形态并追加 ownerUserId）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentShareMineItemVO {

    /**
     * 分享 token（撤销与拼接链接用）
     */
    private String token;

    /**
     * 会话标题预览
     */
    private String titlePreview;

    /**
     * 白名单消息条数（供管理面判断内容量级）
     */
    private Integer messageCount;

    /**
     * 状态：ACTIVE / REVOKED
     */
    private String status;

    /**
     * 过期时刻；NULL 即不过期
     */
    private Date expireTime;

    /**
     * 创建时间
     */
    private Date createTime;
}

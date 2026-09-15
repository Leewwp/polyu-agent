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

package com.nageoffer.ai.ragent.rag.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 本人分享列表项（owner 视角）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShareMineItemVO {

    /**
     * 分享 token
     */
    private String token;

    /**
     * 问题摘要（截断）
     */
    private String questionPreview;

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

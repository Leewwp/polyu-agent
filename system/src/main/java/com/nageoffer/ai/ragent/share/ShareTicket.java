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
 * 创建分享的回执：token 即对外链接凭据，expireAt 为 NULL 时表示不过期
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShareTicket {

    /**
     * 分享 token（43 字符 Base64URL，已落库）
     */
    private String token;

    /**
     * 快照行 ID
     */
    private String id;

    /**
     * 过期时刻；NULL=不过期
     */
    private Date expireAt;
}

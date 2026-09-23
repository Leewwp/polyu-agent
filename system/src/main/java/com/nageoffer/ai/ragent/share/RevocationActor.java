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
 * 撤销发起方：owner 本人（须归属校验）或管理员覆盖（跳过校验）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RevocationActor {

    /**
     * 发起撤销的用户 ID；adminOverride=true 时忽略
     */
    private String userId;

    /**
     * 管理员覆盖：跳过归属校验（/admin/** 角色拦截器已保证 admin 角色）
     */
    private boolean adminOverride;

    public static RevocationActor owner(String userId) {
        return new RevocationActor(userId, false);
    }

    public static RevocationActor admin() {
        return new RevocationActor(null, true);
    }
}

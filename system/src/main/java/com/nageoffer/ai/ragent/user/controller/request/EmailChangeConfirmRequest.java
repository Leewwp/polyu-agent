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

package com.nageoffer.ai.ragent.user.controller.request;

import lombok.Data;

/**
 * 改邮箱第二步（#104）：新邮箱 + 验证码。验码通过即落库（email + email_verified=1）
 * 并向老邮箱发成功通知信；老邮箱立即释放可被再注册（墓碑机制仅注销用）
 */
@Data
public class EmailChangeConfirmRequest {

    private String newEmail;

    private String code;
}

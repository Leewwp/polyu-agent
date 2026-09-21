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
 * 自助注册请求（U2；#103 增唯一用户名）：用户名 + 邮箱 + 密码；用户名经
 * {@link com.nageoffer.ai.ragent.user.security.UsernamePolicy} 规范化，邮箱验证码随后发送
 */
@Data
public class RegisterRequest {

    private String username;

    private String email;

    private String password;
}

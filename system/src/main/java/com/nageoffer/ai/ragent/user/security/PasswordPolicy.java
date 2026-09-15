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

package com.nageoffer.ai.ragent.user.security;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.exception.ClientException;

/**
 * 密码输入策略：下限常规强度，上限 64 字符规避 BCrypt 72 字节截断。
 * 注册/重置/改密三处共用，防口径漂移。
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 64;

    private static final String MESSAGE = "密码长度需为 " + MIN_LENGTH + "-" + MAX_LENGTH + " 位字符";

    private PasswordPolicy() {
    }

    /**
     * 校验明文密码长度区间，不合规抛 ClientException
     */
    public static void validate(String password) {
        if (StrUtil.isBlank(password) || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            throw new ClientException(MESSAGE);
        }
    }
}

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

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 用户名策略（#103）：注册与 admin 建号共用，防口径漂移。
 *
 * <p>规则拍板（grilling Q2）：{@code ^[a-z0-9_-]{3,20}$}，大写自动转小写（不区分大小写）；
 * 20 上限恰好对齐既有 {@code *_by VARCHAR(20)} 操作人列（#100 放宽后更宽裕）；
 * <b>禁 @ 是硬规则</b>——登录 SQL 是 {@code username=key OR email=key LIMIT 1} 无排序，
 * 允许 @ 时攻击者可注册 {@code victim@polyu.edu.hk} 形用户名制造登录二义，禁 @ 使
 * 「含 @ 的键只匹配 email 列、不含 @ 的键只匹配 username 列」严格二分；保留字黑名单；
 * 注册后不可改名（登录标识 + 11 张表 created_by/审计 operator_name 的戳）。
 */
public final class UsernamePolicy {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 20;

    private static final Pattern PATTERN = Pattern.compile("^[a-z0-9_-]{" + MIN_LENGTH + "," + MAX_LENGTH + "}$");

    /**
     * 保留字：管理/系统语义与品牌词，防伪装官方（大小写不敏感——normalize 后全小写比对）
     */
    private static final Set<String> RESERVED = Set.of(
            "admin", "administrator", "root", "system", "guest", "official", "support", "service",
            "help", "null", "polyu", "polyuguide", "polyuguide_official");

    private UsernamePolicy() {
    }

    /**
     * 规范化并校验：去首尾空白 → 转小写 → 规则/保留字校验。不合规抛 ClientException（文案面向用户）。
     *
     * @return 规范化后的用户名（全小写）
     */
    public static String normalize(String raw) {
        String username = StrUtil.trimToNull(raw);
        if (username == null) {
            throw new ClientException("用户名不能为空");
        }
        username = username.toLowerCase(Locale.ROOT);
        if (!PATTERN.matcher(username).matches()) {
            throw new ClientException("用户名须为 3–20 位小写字母、数字、下划线或连字符（不区分大小写，不可包含 @）");
        }
        if (RESERVED.contains(username)) {
            throw new ClientException("该用户名不可用，请换一个");
        }
        return username;
    }
}

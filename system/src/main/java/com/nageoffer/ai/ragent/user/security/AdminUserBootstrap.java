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

import java.security.SecureRandom;
import java.util.Arrays;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 管理员一次性引导：取代 init_data 里的 admin/admin 明文种子（已移除）。
 *
 * <p>仅当用户表为空时执行（幂等，重启不重复创建）。密码优先取
 * {@code ragent.bootstrap.admin-password}（部署环境注入），未配置则生成 16 位随机密码：
 * dev profile 日志完整打印一次（运维取得初始凭据的唯一渠道，首次登录后应立即修改）；
 * prod profile 只打掩码（F-10，防日志留存/聚合系统落明文——部署环境应注入
 * ragent.bootstrap.admin-password 而不是依赖随机密码）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminUserBootstrap implements ApplicationRunner {

    private final UserMapper userMapper;
    private final PasswordCodec passwordCodec;
    private final Environment environment;

    @Value("${ragent.bootstrap.admin-username:admin}")
    private String adminUsername;

    @Value("${ragent.bootstrap.admin-password:}")
    private String adminPassword;

    @Override
    public void run(ApplicationArguments args) {
        Long userCount = userMapper.selectCount(
                Wrappers.lambdaQuery(UserDO.class).eq(UserDO::getDeleted, 0));
        if (userCount != null && userCount > 0) {
            return;
        }
        String password = StrUtil.isNotBlank(adminPassword) ? adminPassword : randomPassword();
        UserDO admin = UserDO.builder()
                .username(adminUsername)
                .password(passwordCodec.encode(password))
                .role("admin")
                .build();
        userMapper.insert(admin);
        if (StrUtil.isNotBlank(adminPassword)) {
            log.warn("[bootstrap] 用户表为空，已按 ragent.bootstrap.admin-password 创建管理员 {}（请尽快修改密码）",
                    adminUsername);
        } else if (isProdProfile()) {
            // F-10（安全审计）：prod 日志不落明文初始密码，防日志留存/聚合系统泄漏；
            // 随机密码由此不再可从日志取得，运维恢复路径=用 ragent.bootstrap.admin-password
            // 重启重灌（幂等仅空表生效，需先清表）或直接改库重置
            log.warn("[bootstrap] 用户表为空，已创建管理员 {}，初始密码不入 prod 日志——"
                    + "请改用 ragent.bootstrap.admin-password 注入（清空用户表后重启生效）或重置密码",
                    adminUsername);
        } else {
            log.warn("[bootstrap] 用户表为空，已创建管理员 {}，初始密码（仅打印一次，请立即登录修改）：{}",
                    adminUsername, password);
        }
    }

    private boolean isProdProfile() {
        return Arrays.asList(environment.getActiveProfiles()).contains("prod");
    }

    private String randomPassword() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}

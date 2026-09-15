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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理员引导日志脱敏测试：prod profile 不落明文初始密码，
 * dev 保留明文（运维取初始凭据的唯一渠道）；入库的都是完整随机密码，脱敏仅作用于日志。
 */
@ExtendWith(MockitoExtension.class)
class AdminUserBootstrapTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordCodec passwordCodec;

    @Mock
    private Environment environment;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        logAppender = new ListAppender<>();
        logAppender.start();
        logger = (Logger) LoggerFactory.getLogger(AdminUserBootstrap.class);
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
    }

    private AdminUserBootstrap bootstrap() {
        AdminUserBootstrap bootstrap = new AdminUserBootstrap(userMapper, passwordCodec, environment);
        ReflectionTestUtils.setField(bootstrap, "adminUsername", "admin");
        ReflectionTestUtils.setField(bootstrap, "adminPassword", "");
        return bootstrap;
    }

    @Test
    void prodProfileMustNotLogPlaintextRandomPassword() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        // encode 直通桩：实体密码==明文随机密码==日志里（若不脱敏）会出现的那串
        when(passwordCodec.encode(any())).thenAnswer(inv -> inv.getArgument(0));
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});

        bootstrap().run(null);

        ArgumentCaptor<UserDO> captor = ArgumentCaptor.forClass(UserDO.class);
        verify(userMapper).insert(captor.capture());
        String insertedPassword = captor.getValue().getPassword();
        assertThat(logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains(insertedPassword)))
                .as("prod 日志不得出现完整初始密码")
                .isTrue();
        assertThat(logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("ragent.bootstrap.admin-password")))
                .as("prod 提示须给出恢复路径")
                .isTrue();
    }

    @Test
    void devProfileKeepsPlaintextOnceLog() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(passwordCodec.encode(any())).thenAnswer(inv -> inv.getArgument(0));
        when(environment.getActiveProfiles()).thenReturn(new String[]{});

        bootstrap().run(null);

        ArgumentCaptor<UserDO> captor = ArgumentCaptor.forClass(UserDO.class);
        verify(userMapper).insert(captor.capture());
        String insertedPassword = captor.getValue().getPassword();
        assertThat(logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains(insertedPassword)))
                .as("dev 日志保留明文初始密码（仅打印一次的既有约定）")
                .isTrue();
    }
}

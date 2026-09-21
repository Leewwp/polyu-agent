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

package com.nageoffer.ai.ragent.user.service.impl;

import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.user.controller.request.EmailChangeConfirmRequest;
import com.nageoffer.ai.ragent.user.controller.request.EmailChangeRequest;
import com.nageoffer.ai.ragent.user.controller.vo.CurrentUserVO;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import com.nageoffer.ai.ragent.user.mail.MailSender;
import com.nageoffer.ai.ragent.user.mail.MailTemplates;
import com.nageoffer.ai.ragent.user.mail.MailVerificationService;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.user.security.PasswordCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 个人中心服务面（#104）：/user/me 扩展资料、改邮箱两步流程
 * （密码错/码错/占用/成功后 verified=1+老邮箱通知断言）
 */
class UserServiceImplTest {

    private static final String OLD_EMAIL = "old@example.com";
    private static final String NEW_EMAIL = "new@example.com";
    private static final String PASSWORD = "right-pass-123";

    private UserMapper userMapper;
    private PasswordCodec passwordCodec;
    private MailVerificationService mailVerificationService;
    private MailSender mailSender;
    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        passwordCodec = new PasswordCodec();
        mailVerificationService = mock(MailVerificationService.class);
        mailSender = mock(MailSender.class);
        userService = new UserServiceImpl(userMapper, mock(BizChangeLogContext.class), passwordCodec,
                mock(AccountDeletionCascade.class), mailVerificationService, mailSender);
        UserContext.set(LoginUser.builder().userId("100").username("alice").role("user").build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private UserDO userWithEmail(String email) {
        return UserDO.builder()
                .id("100")
                .username("alice")
                .password(passwordCodec.encode(PASSWORD))
                .role("user")
                .email(email)
                .emailVerified(email == null ? null : 1)
                .createTime(new Date())
                .build();
    }

    private void stubActive(UserDO user) {
        when(userMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(user);
    }

    @Test
    void currentUserDetailCarriesEmailFields() {
        UserDO user = userWithEmail(OLD_EMAIL);
        stubActive(user);

        CurrentUserVO vo = userService.currentUserDetail();

        assertEquals("100", vo.getUserId());
        assertEquals("alice", vo.getUsername());
        assertEquals(OLD_EMAIL, vo.getEmail());
        assertEquals(1, vo.getEmailVerified());
        assertEquals(user.getCreateTime(), vo.getCreateTime());
    }

    @Test
    void requestEmailChangeChecksPasswordThenSendsCodeAndNotifiesOldMailbox() {
        stubActive(userWithEmail(OLD_EMAIL));

        EmailChangeRequest request = new EmailChangeRequest();
        request.setNewEmail("  New@Example.COM ");
        request.setCurrentPassword(PASSWORD);
        userService.requestEmailChange(request);

        verify(mailVerificationService).sendCode("new@example.com", "change");
        ArgumentCaptor<com.nageoffer.ai.ragent.user.mail.MailMessage> captor =
                ArgumentCaptor.forClass(com.nageoffer.ai.ragent.user.mail.MailMessage.class);
        verify(mailSender).send(captor.capture());
        assertEquals(OLD_EMAIL, captor.getValue().to());
        assertEquals("PolyUGuide · 邮箱更改请求通知 / Email change request notice", captor.getValue().subject());
    }

    @Test
    void requestEmailChangeRejectsWrongPassword() {
        stubActive(userWithEmail(OLD_EMAIL));

        EmailChangeRequest request = new EmailChangeRequest();
        request.setNewEmail(NEW_EMAIL);
        request.setCurrentPassword("wrong-pass");
        ClientException ex = assertThrows(ClientException.class, () -> userService.requestEmailChange(request));

        assertEquals("当前密码不正确", ex.getMessage());
        verify(mailVerificationService, never()).sendCode(anyString(), anyString());
        verify(mailSender, never()).send(any());
    }

    @Test
    void requestEmailChangeRejectsTakenEmail() {
        stubActive(userWithEmail(OLD_EMAIL));
        when(userMapper.selectActiveByUsernameOrEmail(NEW_EMAIL)).thenReturn(userWithEmail(NEW_EMAIL));

        EmailChangeRequest request = new EmailChangeRequest();
        request.setNewEmail(NEW_EMAIL);
        request.setCurrentPassword(PASSWORD);
        ClientException ex = assertThrows(ClientException.class, () -> userService.requestEmailChange(request));

        assertEquals("该邮箱已被其他账号绑定", ex.getMessage());
        verify(mailVerificationService, never()).sendCode(anyString(), anyString());
    }

    @Test
    void confirmEmailChangeVerifiesCodeUpdatesEmailAndNotifiesOldMailbox() {
        UserDO user = userWithEmail(OLD_EMAIL);
        stubActive(user);
        when(mailVerificationService.verifyCode(NEW_EMAIL, "change", "123456")).thenReturn(true);

        EmailChangeConfirmRequest request = new EmailChangeConfirmRequest();
        request.setNewEmail(NEW_EMAIL);
        request.setCode("123456");
        userService.confirmEmailChange(request);

        assertEquals(NEW_EMAIL, user.getEmail());
        assertEquals(1, user.getEmailVerified());
        verify(userMapper).updateById(user);
        ArgumentCaptor<com.nageoffer.ai.ragent.user.mail.MailMessage> captor =
                ArgumentCaptor.forClass(com.nageoffer.ai.ragent.user.mail.MailMessage.class);
        verify(mailSender).send(captor.capture());
        assertEquals(OLD_EMAIL, captor.getValue().to());
        assertEquals("PolyUGuide · 绑定邮箱已更改 / Email address changed", captor.getValue().subject());
    }

    @Test
    void confirmEmailChangeRejectsBadCode() {
        UserDO user = userWithEmail(OLD_EMAIL);
        stubActive(user);
        when(mailVerificationService.verifyCode(NEW_EMAIL, "change", "000000")).thenReturn(false);

        EmailChangeConfirmRequest request = new EmailChangeConfirmRequest();
        request.setNewEmail(NEW_EMAIL);
        request.setCode("000000");
        ClientException ex = assertThrows(ClientException.class, () -> userService.confirmEmailChange(request));

        assertEquals("验证码无效或已过期", ex.getMessage());
        verify(userMapper, never()).updateById(any(UserDO.class));
    }

    @Test
    void confirmEmailChangeRejectsEmailTakenBetweenRequestAndConfirm() {
        UserDO user = userWithEmail(OLD_EMAIL);
        stubActive(user);
        when(mailVerificationService.verifyCode(NEW_EMAIL, "change", "123456")).thenReturn(true);
        when(userMapper.selectActiveByUsernameOrEmail(NEW_EMAIL)).thenReturn(userWithEmail(NEW_EMAIL));

        EmailChangeConfirmRequest request = new EmailChangeConfirmRequest();
        request.setNewEmail(NEW_EMAIL);
        request.setCode("123456");
        ClientException ex = assertThrows(ClientException.class, () -> userService.confirmEmailChange(request));

        assertEquals("该邮箱已被其他账号绑定", ex.getMessage());
        verify(userMapper, never()).updateById(any(UserDO.class));
    }

    @Test
    void requestEmailChangeWithoutOldEmailSkipsNotify() {
        // 存量/管理员建号用户 email=NULL：验码照发，老邮箱通知无从投递跳过
        stubActive(userWithEmail(null));

        EmailChangeRequest request = new EmailChangeRequest();
        request.setNewEmail(NEW_EMAIL);
        request.setCurrentPassword(PASSWORD);
        userService.requestEmailChange(request);

        verify(mailVerificationService).sendCode(NEW_EMAIL, "change");
        verify(mailSender, never()).send(any());
    }
}

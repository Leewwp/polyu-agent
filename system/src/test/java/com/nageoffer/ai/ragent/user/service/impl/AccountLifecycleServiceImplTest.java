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

import cn.dev33.satoken.stp.StpUtil;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.user.controller.request.AccountDeleteRequest;
import com.nageoffer.ai.ragent.user.controller.request.AccountRestoreRequest;
import com.nageoffer.ai.ragent.user.controller.request.EmailResendRequest;
import com.nageoffer.ai.ragent.user.controller.request.EmailVerifyRequest;
import com.nageoffer.ai.ragent.user.controller.request.ForgotPasswordRequest;
import com.nageoffer.ai.ragent.user.controller.request.RegisterRequest;
import com.nageoffer.ai.ragent.user.controller.request.ResetPasswordRequest;
import com.nageoffer.ai.ragent.user.controller.vo.LoginVO;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import com.nageoffer.ai.ragent.user.mail.MailVerificationService;
import com.nageoffer.ai.ragent.user.security.PasswordCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 账号生命周期单元测试（U2）：反枚举口径、验证门、注销冷静期与恢复
 */
class AccountLifecycleServiceImplTest {

    private static final String EMAIL = "u2@example.com";
    private static final String PASSWORD = "right-pass-123";

    private UserMapper userMapper;
    private MailVerificationService mailVerificationService;
    private AccountLifecycleServiceImpl service;
    private MockedStatic<StpUtil> stpUtil;
    private PasswordCodec passwordCodec;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        mailVerificationService = mock(MailVerificationService.class);
        passwordCodec = new PasswordCodec();
        service = new AccountLifecycleServiceImpl(userMapper, passwordCodec, mailVerificationService);
        stpUtil = mockStatic(StpUtil.class);
        stpUtil.when(StpUtil::getTokenValue).thenReturn("token");
    }

    @AfterEach
    void tearDown() {
        stpUtil.close();
    }

    private UserDO registeredUser() {
        return UserDO.builder()
                .id("100")
                .username(EMAIL)
                .password(passwordCodec.encode(PASSWORD))
                .role("user")
                .email(EMAIL)
                .emailVerified(1)
                .build();
    }

    // ---------- 注册 ----------

    @Test
    void registerCreatesUnverifiedUserAndSendsCode() {
        when(userMapper.selectActiveByUsernameOrEmail(EMAIL)).thenReturn(null);
        when(userMapper.insert(any(UserDO.class))).thenAnswer(inv -> {
            inv.getArgument(0, UserDO.class).setId("100");
            return 1;
        });

        RegisterRequest req = new RegisterRequest();
        req.setEmail(EMAIL);
        req.setPassword(PASSWORD);
        service.register(req);

        ArgumentCaptor<UserDO> captor = ArgumentCaptor.forClass(UserDO.class);
        verify(userMapper).insert(captor.capture());
        UserDO created = captor.getValue();
        assertEquals(EMAIL, created.getUsername());
        assertEquals(EMAIL, created.getEmail());
        assertEquals("user", created.getRole());
        assertEquals(0, created.getEmailVerified());
        assertTrue(created.getPassword().startsWith("{bcrypt}"));
        verify(mailVerificationService).sendCode(EMAIL, "verify");
    }

    @Test
    void registerNormalizesEmailToLowercase() {
        when(userMapper.selectActiveByUsernameOrEmail("u2@example.com")).thenReturn(null);
        when(userMapper.insert(any(UserDO.class))).thenReturn(1);

        RegisterRequest req = new RegisterRequest();
        req.setEmail("  U2@Example.COM ");
        req.setPassword(PASSWORD);
        service.register(req);

        verify(userMapper).insert(any(UserDO.class));
        verify(mailVerificationService).sendCode("u2@example.com", "verify");
    }

    @Test
    void registerSilentlyAcceptsWhenEmailTaken() {
        when(userMapper.selectActiveByUsernameOrEmail(EMAIL)).thenReturn(registeredUser());

        RegisterRequest req = new RegisterRequest();
        req.setEmail(EMAIL);
        req.setPassword(PASSWORD);
        service.register(req);

        // 不建号、不发码、不报错——与可注册路径的响应不可区分（反枚举）
        verify(userMapper, never()).insert(any(UserDO.class));
        verify(mailVerificationService, never()).sendCode(anyString(), anyString());
    }

    @Test
    void registerDuplicateKeyRaceIsSilentlyAccepted() {
        when(userMapper.selectActiveByUsernameOrEmail(EMAIL)).thenReturn(null);
        when(userMapper.insert(any(UserDO.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_user_email_active"));

        RegisterRequest req = new RegisterRequest();
        req.setEmail(EMAIL);
        req.setPassword(PASSWORD);
        service.register(req);

        verify(mailVerificationService, never()).sendCode(anyString(), anyString());
    }

    @Test
    void registerRejectsWeakPassword() {
        RegisterRequest req = new RegisterRequest();
        req.setEmail(EMAIL);
        req.setPassword("short");
        assertThrows(ClientException.class, () -> service.register(req));
        verify(userMapper, never()).insert(any(UserDO.class));
    }

    @Test
    void registerRejectsOverlongPassword() {
        RegisterRequest req = new RegisterRequest();
        req.setEmail(EMAIL);
        req.setPassword("x".repeat(65));
        assertThrows(ClientException.class, () -> service.register(req));
        verify(userMapper, never()).insert(any(UserDO.class));
    }

    @Test
    void registerRejectsMalformedEmail() {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("not-an-email");
        req.setPassword(PASSWORD);
        assertThrows(ClientException.class, () -> service.register(req));
        verify(userMapper, never()).insert(any(UserDO.class));
    }

    // ---------- 邮箱验证 ----------

    @Test
    void verifyEmailMarksVerified() {
        UserDO user = registeredUser();
        user.setEmailVerified(0);
        when(userMapper.selectActiveByUsernameOrEmail(EMAIL)).thenReturn(user);
        when(mailVerificationService.verifyCode(EMAIL, "verify", "123456")).thenReturn(true);

        EmailVerifyRequest req = new EmailVerifyRequest();
        req.setEmail(EMAIL);
        req.setCode("123456");
        service.verifyEmail(req);

        ArgumentCaptor<UserDO> captor = ArgumentCaptor.forClass(UserDO.class);
        verify(userMapper).updateById(captor.capture());
        assertEquals(1, captor.getValue().getEmailVerified());
    }

    @Test
    void verifyEmailIsIdempotentWhenAlreadyVerified() {
        when(userMapper.selectActiveByUsernameOrEmail(EMAIL)).thenReturn(registeredUser());

        EmailVerifyRequest req = new EmailVerifyRequest();
        req.setEmail(EMAIL);
        req.setCode("123456");
        service.verifyEmail(req);

        verify(mailVerificationService, never()).verifyCode(anyString(), anyString(), anyString());
        verify(userMapper, never()).updateById(any(UserDO.class));
    }

    @Test
    void verifyEmailUsesSameGenericErrorForMissingUserAndBadCode() {
        // 反枚举核心断言：无此用户与码错必须同文案
        when(userMapper.selectActiveByUsernameOrEmail(EMAIL)).thenReturn(null);
        ClientException missing = assertThrows(ClientException.class,
                () -> service.verifyEmail(verifyReq("123456")));

        UserDO user = registeredUser();
        user.setEmailVerified(0);
        when(userMapper.selectActiveByUsernameOrEmail(EMAIL)).thenReturn(user);
        when(mailVerificationService.verifyCode(EMAIL, "verify", "000000")).thenReturn(false);
        ClientException wrong = assertThrows(ClientException.class,
                () -> service.verifyEmail(verifyReq("000000")));

        assertEquals(missing.getMessage(), wrong.getMessage());
    }

    private EmailVerifyRequest verifyReq(String code) {
        EmailVerifyRequest req = new EmailVerifyRequest();
        req.setEmail(EMAIL);
        req.setCode(code);
        return req;
    }

    // ---------- 重发验证码 ----------

    @Test
    void resendSendsOnlyForRegisteredUnverified() {
        UserDO user = registeredUser();
        user.setEmailVerified(0);
        when(userMapper.selectActiveByUsernameOrEmail(EMAIL)).thenReturn(user);

        EmailResendRequest req = new EmailResendRequest();
        req.setEmail(EMAIL);
        service.resendVerificationCode(req);
        verify(mailVerificationService).sendCode(EMAIL, "verify");
    }

    @Test
    void resendSilentForUnknownAndVerifiedEmails() {
        when(userMapper.selectActiveByUsernameOrEmail(EMAIL)).thenReturn(null);
        EmailResendRequest req = new EmailResendRequest();
        req.setEmail(EMAIL);
        service.resendVerificationCode(req);

        when(userMapper.selectActiveByUsernameOrEmail(EMAIL)).thenReturn(registeredUser());
        service.resendVerificationCode(req);

        verify(mailVerificationService, never()).sendCode(anyString(), anyString());
    }

    // ---------- 忘记密码 ----------

    @Test
    void forgotSendsResetOnlyForVerifiedUsers() {
        when(userMapper.selectActiveByUsernameOrEmail(EMAIL)).thenReturn(registeredUser());

        ForgotPasswordRequest req = new ForgotPasswordRequest();
        req.setEmail(EMAIL);
        service.requestPasswordReset(req);
        verify(mailVerificationService).sendCode(EMAIL, "reset");
    }

    @Test
    void forgotSilentForUnknownAndUnverifiedEmails() {
        when(userMapper.selectActiveByUsernameOrEmail(EMAIL)).thenReturn(null);
        ForgotPasswordRequest req = new ForgotPasswordRequest();
        req.setEmail(EMAIL);
        service.requestPasswordReset(req);

        UserDO unverified = registeredUser();
        unverified.setEmailVerified(0);
        when(userMapper.selectActiveByUsernameOrEmail(EMAIL)).thenReturn(unverified);
        service.requestPasswordReset(req);

        verify(mailVerificationService, never()).sendCode(anyString(), anyString());
    }

    // ---------- 密码重置 ----------

    @Test
    void resetPasswordStoresNewHash() {
        UserDO user = registeredUser();
        when(userMapper.selectActiveByUsernameOrEmail(EMAIL)).thenReturn(user);
        when(mailVerificationService.verifyCode(EMAIL, "reset", "654321")).thenReturn(true);

        ResetPasswordRequest req = new ResetPasswordRequest();
        req.setEmail(EMAIL);
        req.setCode("654321");
        req.setNewPassword("brand-new-pass-9");
        service.resetPassword(req);

        ArgumentCaptor<UserDO> captor = ArgumentCaptor.forClass(UserDO.class);
        verify(userMapper).updateById(captor.capture());
        assertTrue(captor.getValue().getPassword().startsWith("{bcrypt}"));
        assertTrue(passwordCodec.matches("brand-new-pass-9", captor.getValue().getPassword()));
    }

    @Test
    void resetUsesSameGenericErrorForMissingUserAndBadCode() {
        when(userMapper.selectActiveByUsernameOrEmail(EMAIL)).thenReturn(null);
        ClientException missing = assertThrows(ClientException.class, () -> service.resetPassword(resetReq("111111")));

        when(userMapper.selectActiveByUsernameOrEmail(EMAIL)).thenReturn(registeredUser());
        when(mailVerificationService.verifyCode(EMAIL, "reset", "222222")).thenReturn(false);
        ClientException wrong = assertThrows(ClientException.class, () -> service.resetPassword(resetReq("222222")));

        assertEquals(missing.getMessage(), wrong.getMessage());
    }

    @Test
    void resetRejectsWeakNewPassword() {
        ResetPasswordRequest req = resetReq("654321");
        req.setNewPassword("short");
        assertThrows(ClientException.class, () -> service.resetPassword(req));
        verify(userMapper, never()).updateById(any(UserDO.class));
    }

    private ResetPasswordRequest resetReq(String code) {
        ResetPasswordRequest req = new ResetPasswordRequest();
        req.setEmail(EMAIL);
        req.setCode(code);
        req.setNewPassword("brand-new-pass-9");
        return req;
    }

    // ---------- 自助注销 ----------

    @Test
    void deleteRequiresLogin() {
        stpUtil.when(StpUtil::getLoginIdDefaultNull).thenReturn(null);
        assertThrows(ClientException.class, () -> service.deleteAccount(deleteReq(PASSWORD)));
        verify(userMapper, never()).softDeleteById(anyString(), any(Date.class));
    }

    @Test
    void deleteSoftDeletesOnPasswordMatch() {
        stpUtil.when(StpUtil::getLoginIdDefaultNull).thenReturn("100");
        when(userMapper.selectById("100")).thenReturn(registeredUser());

        service.deleteAccount(deleteReq(PASSWORD));

        verify(userMapper).softDeleteById(eq("100"), any(Date.class));
        stpUtil.verify(StpUtil::logout);
    }

    @Test
    void deleteWrongPasswordRejected() {
        stpUtil.when(StpUtil::getLoginIdDefaultNull).thenReturn("100");
        when(userMapper.selectById("100")).thenReturn(registeredUser());

        assertThrows(ClientException.class, () -> service.deleteAccount(deleteReq("wrong-pass-999")));
        verify(userMapper, never()).softDeleteById(anyString(), any(Date.class));
    }

    @Test
    void deleteBlocksAdminSelfDeletion() {
        stpUtil.when(StpUtil::getLoginIdDefaultNull).thenReturn("1");
        UserDO admin = UserDO.builder().id("1").username("admin")
                .password(passwordCodec.encode(PASSWORD)).role("admin").build();
        when(userMapper.selectById("1")).thenReturn(admin);

        assertThrows(ClientException.class, () -> service.deleteAccount(deleteReq(PASSWORD)));
        verify(userMapper, never()).softDeleteById(anyString(), any(Date.class));
    }

    private AccountDeleteRequest deleteReq(String password) {
        AccountDeleteRequest req = new AccountDeleteRequest();
        req.setPassword(password);
        return req;
    }

    // ---------- 撤销注销 ----------

    @Test
    void restoreWithinGraceWindowLogsIn() {
        UserDO deleted = registeredUser();
        deleted.setDeleteTime(new Date(System.currentTimeMillis() - 24L * 3600 * 1000));
        when(userMapper.selectSoftDeletedByUsernameOrEmail(EMAIL)).thenReturn(deleted);
        when(userMapper.selectActiveByUsernameOrEmail(EMAIL)).thenReturn(null);

        LoginVO vo = service.restoreAccount(restoreReq());

        verify(userMapper).restoreById(eq("100"), any(Date.class));
        stpUtil.verify(() -> StpUtil.login("100"));
        assertNotNull(vo);
        assertEquals("token", vo.getToken());
    }

    @Test
    void restorePastGraceWindowRejected() {
        UserDO deleted = registeredUser();
        deleted.setDeleteTime(new Date(System.currentTimeMillis() - 31L * 24 * 3600 * 1000));
        when(userMapper.selectSoftDeletedByUsernameOrEmail(EMAIL)).thenReturn(deleted);

        assertThrows(ClientException.class, () -> service.restoreAccount(restoreReq()));
        verify(userMapper, never()).restoreById(anyString(), any(Date.class));
    }

    @Test
    void restoreWrongPasswordUsesGenericError() {
        when(userMapper.selectSoftDeletedByUsernameOrEmail(EMAIL)).thenReturn(null);
        ClientException missing = assertThrows(ClientException.class,
                () -> service.restoreAccount(restoreReq("wrong-pass-999")));

        UserDO deleted = registeredUser();
        deleted.setDeleteTime(new Date());
        when(userMapper.selectSoftDeletedByUsernameOrEmail(EMAIL)).thenReturn(deleted);
        ClientException wrong = assertThrows(ClientException.class,
                () -> service.restoreAccount(restoreReq("wrong-pass-999")));

        assertEquals(missing.getMessage(), wrong.getMessage());
        verify(userMapper, never()).restoreById(anyString(), any(Date.class));
    }

    @Test
    void restoreBlockedWhenEmailReoccupied() {
        UserDO deleted = registeredUser();
        deleted.setDeleteTime(new Date());
        when(userMapper.selectSoftDeletedByUsernameOrEmail(EMAIL)).thenReturn(deleted);
        when(userMapper.selectActiveByUsernameOrEmail(EMAIL)).thenReturn(UserDO.builder().id("999").build());

        assertThrows(ClientException.class, () -> service.restoreAccount(restoreReq()));
        verify(userMapper, never()).restoreById(anyString(), any(Date.class));
    }

    private AccountRestoreRequest restoreReq() {
        return restoreReq(PASSWORD);
    }

    private AccountRestoreRequest restoreReq(String password) {
        AccountRestoreRequest req = new AccountRestoreRequest();
        req.setAccount(EMAIL);
        req.setPassword(password);
        return req;
    }
}

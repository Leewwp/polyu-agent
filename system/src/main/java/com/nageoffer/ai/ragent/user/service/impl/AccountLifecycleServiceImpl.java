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
import cn.hutool.core.util.StrUtil;
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
import com.nageoffer.ai.ragent.user.enums.UserRole;
import com.nageoffer.ai.ragent.user.mail.MailVerificationService;
import com.nageoffer.ai.ragent.user.security.ClientIps;
import com.nageoffer.ai.ragent.user.security.LoginRateLimiter;
import com.nageoffer.ai.ragent.user.security.PasswordCodec;
import com.nageoffer.ai.ragent.user.security.PasswordPolicy;
import com.nageoffer.ai.ragent.user.security.UsernamePolicy;
import com.nageoffer.ai.ragent.user.service.AccountLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.regex.Pattern;

/**
 * 账号生命周期实现（U2）。验证码收发全部走 T10 {@link MailVerificationService}
 * （logger 默认档，码值见应用日志；开放注册窗与 SMTP 凭据同窗切换）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountLifecycleServiceImpl implements AccountLifecycleService {

    private static final String DEFAULT_AVATAR_URL = "https://avatars.githubusercontent.com/u/583231?v=4";

    /**
     * 邮箱格式（宽松 RFC 近似）与长度上限
     */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final int EMAIL_MAX_LENGTH = 254;

    /**
     * 邮箱已注册/不存在共用口径：不泄漏注册状态
     */
    private static final String CODE_INVALID_MESSAGE = "验证码无效或已过期";
    private static final String RESET_CODE_INVALID_MESSAGE = "重置码无效或已过期";

    /**
     * 提码端点（邮箱验证/密码重置）按 IP 限流兜底（O1/M2）：
     * 码级失败计数之上再加流量层，防验证码机制未知缺陷被在线爆破
     */
    private static final String CODE_SUBMIT_RATE_SCOPE = "ragent:rl:code-submit:";
    private static final int CODE_SUBMIT_LIMIT = 10;
    private static final java.time.Duration CODE_SUBMIT_WINDOW = java.time.Duration.ofMinutes(15);

    /**
     * 注销冷静期（天）
     */
    private static final int DELETE_GRACE_DAYS = 30;

    private final UserMapper userMapper;
    private final PasswordCodec passwordCodec;
    private final MailVerificationService mailVerificationService;
    private final LoginRateLimiter loginRateLimiter;

    @Override
    public void register(RegisterRequest requestParam) {
        // 用户名规则/保留字/小写化（#103）；email 归一化在前，两键格式都过再走限速与占用检查
        String username = UsernamePolicy.normalize(requestParam == null ? null : requestParam.getUsername());
        String email = normalizeEmail(requestParam == null ? null : requestParam.getEmail());
        String password = requestParam == null ? null : requestParam.getPassword();
        PasswordPolicy.validate(password);
        // 注册限速：3 次/小时/IP——计入一切注册尝试（含重复报错路径），防枚举探测
        loginRateLimiter.tryAcquire("ragent:rl:register:ip:", ClientIps.resolve(), 3, java.time.Duration.ofHours(1));

        // 重复显式报错（#103 反转既有静默策略；忘记密码端点已可探测存在性，防枚举收益趋零）
        // 禁 @ 规则使「含 @ 的键只匹配 email 列、不含 @ 的键只匹配 username 列」，两键互不串台
        if (userMapper.selectActiveByUsernameOrEmail(email) != null) {
            throw new ClientException("该邮箱已注册");
        }
        if (userMapper.selectActiveByUsernameOrEmail(username) != null) {
            throw new ClientException("用户名已被占用");
        }
        UserDO record = UserDO.builder()
                .username(username)
                .password(passwordCodec.encode(password))
                .role(UserRole.USER.getCode())
                .email(email)
                .emailVerified(0)
                .build();
        try {
            userMapper.insert(record);
        } catch (DuplicateKeyException ex) {
            // 兜底覆盖并发窗口与冷静期软删行占名（uk_user_username 全表唯一含软删行；
            // 邮箱唯一索引只盖活跃行，活跃占用已被前置检查显式报错）
            log.info("[register] 注册唯一键冲突转显式报错：emailHashPresent={}",
                    StrUtil.isNotBlank(email));
            throw new ClientException("用户名已被占用");
        }
        mailVerificationService.sendCode(email, "verify");
    }

    @Override
    public void verifyEmail(EmailVerifyRequest requestParam) {
        String email = normalizeEmail(requestParam == null ? null : requestParam.getEmail());
        String code = requestParam == null ? null : requestParam.getCode();
        // 提码限流先于存在性判断（O1/M2），未知邮箱的探测同样计额
        loginRateLimiter.tryAcquire(CODE_SUBMIT_RATE_SCOPE, ClientIps.resolve(), CODE_SUBMIT_LIMIT, CODE_SUBMIT_WINDOW);
        UserDO user = userMapper.selectActiveByUsernameOrEmail(email);
        if (user == null) {
            throw new ClientException(CODE_INVALID_MESSAGE);
        }
        if (user.getEmailVerified() != null && user.getEmailVerified() == 1) {
            // 幂等：重复提交已验证邮箱不消耗码、不报错
            return;
        }
        if (!mailVerificationService.verifyCode(email, "verify", code)) {
            throw new ClientException(CODE_INVALID_MESSAGE);
        }
        user.setEmailVerified(1);
        userMapper.updateById(user);
    }

    @Override
    public void resendVerificationCode(EmailResendRequest requestParam) {
        String email = normalizeEmail(requestParam == null ? null : requestParam.getEmail());
        UserDO user = userMapper.selectActiveByUsernameOrEmail(email);
        // 未注册/已验证：静默受理（不发码），与正常重发路径不可区分
        if (user == null || (user.getEmailVerified() != null && user.getEmailVerified() == 1)) {
            return;
        }
        mailVerificationService.sendCode(email, "verify");
    }

    @Override
    public void requestPasswordReset(ForgotPasswordRequest requestParam) {
        String email = normalizeEmail(requestParam == null ? null : requestParam.getEmail());
        // 重置请求限速：3 次/小时/（IP+邮箱）——先于存在性判断，未知邮箱的探测同样计额
        loginRateLimiter.tryAcquire("ragent:rl:reset:", ClientIps.resolve() + "|" + email, 3, java.time.Duration.ofHours(1));
        UserDO user = userMapper.selectActiveByUsernameOrEmail(email);
        // 未注册/未验证：静默受理（不发码）
        if (user == null || user.getEmailVerified() == null || user.getEmailVerified() != 1) {
            return;
        }
        mailVerificationService.sendCode(email, "reset");
    }

    @Override
    public void resetPassword(ResetPasswordRequest requestParam) {
        String email = normalizeEmail(requestParam == null ? null : requestParam.getEmail());
        String code = requestParam == null ? null : requestParam.getCode();
        String newPassword = requestParam == null ? null : requestParam.getNewPassword();
        PasswordPolicy.validate(newPassword);
        // 提码限流先于存在性判断（O1/M2），与邮箱验证端点共用同一 IP 预算
        loginRateLimiter.tryAcquire(CODE_SUBMIT_RATE_SCOPE, ClientIps.resolve(), CODE_SUBMIT_LIMIT, CODE_SUBMIT_WINDOW);
        UserDO user = userMapper.selectActiveByUsernameOrEmail(email);
        if (user == null) {
            throw new ClientException(RESET_CODE_INVALID_MESSAGE);
        }
        if (!mailVerificationService.verifyCode(email, "reset", code)) {
            throw new ClientException(RESET_CODE_INVALID_MESSAGE);
        }
        user.setPassword(passwordCodec.encode(newPassword));
        userMapper.updateById(user);
        // 重置成功踢该用户全部既有会话（O1/L3）：token 被窃取时改密码即把攻击者下线
        StpUtil.logout(user.getId());
    }

    @Override
    public void deleteAccount(AccountDeleteRequest requestParam) {
        String password = requestParam == null ? null : requestParam.getPassword();
        Object loginId = StpUtil.getLoginIdDefaultNull();
        if (loginId == null) {
            throw new ClientException("请先登录");
        }
        UserDO user = userMapper.selectById(String.valueOf(loginId));
        if (user == null) {
            throw new ClientException("用户不存在");
        }
        if (UserRole.ADMIN.getCode().equals(user.getRole())) {
            // 唯一管理员自助注销=自锁管理面，明确拒绝；admin 号退役走管理面删号（同套级联）
            throw new ClientException("管理员账号不支持自助注销");
        }
        if (!passwordCodec.matches(password, user.getPassword())) {
            throw new ClientException("密码不正确");
        }
        Date now = new Date();
        userMapper.softDeleteById(user.getId(), now);
        // 下线当前会话；冷静期信息（30 天内可撤销）由前端提示文案承载
        StpUtil.logout();
        log.info("[account-delete] 自助注销进入冷静期：userId={} 到期=now+{}d", user.getId(), DELETE_GRACE_DAYS);
    }

    @Override
    public LoginVO restoreAccount(AccountRestoreRequest requestParam) {
        // 恢复=登录等价面，账号键双通道（#103 后用户名≠邮箱）：含 @ 才按邮箱规范化，
        // 不含 @ 按用户名原样查（selectSoftDeletedByUsernameOrEmail 双列匹配；用户名规则本就禁 @）
        String account = StrUtil.trimToNull(requestParam == null ? null : requestParam.getAccount());
        if (account == null) {
            throw new ClientException("账号不能为空");
        }
        String key = account.contains("@") ? normalizeEmail(account) : account.toLowerCase();
        String password = requestParam == null ? null : requestParam.getPassword();
        // 恢复=登录等价面，与 login 同口径双键限速（O1/M3），防绕过登录锁定的旁路爆破
        loginRateLimiter.checkLocked(ClientIps.resolve(), key);
        UserDO user = userMapper.selectSoftDeletedByUsernameOrEmail(key);
        if (user == null || !passwordCodec.matches(password, user.getPassword())) {
            // 账号或密码错误计失败（账号不存在同样计，口径同登录防探测）
            loginRateLimiter.recordFailure(ClientIps.resolve(), key);
            // 与登录失败同口径，不区分「账号不存在/已不在冷静期/密码错」
            throw new ClientException("账号或密码错误");
        }
        Date deadline = user.getDeleteTime() == null
                ? new Date(0)
                : Date.from(user.getDeleteTime().toInstant().plus(DELETE_GRACE_DAYS, java.time.temporal.ChronoUnit.DAYS));
        if (deadline.before(new Date())) {
            throw new ClientException("注销冷静期已过，账号已进入删除流程，无法恢复");
        }
        // 邮箱被新注册占用则不可恢复（uk_user_email_active 部分索引允许冷静期内同邮箱再注册）
        if (user.getEmail() != null && userMapper.selectActiveByUsernameOrEmail(user.getEmail()) != null) {
            throw new ClientException("该邮箱已绑定其他账号，无法恢复原账号");
        }
        userMapper.restoreById(user.getId(), new Date());
        StpUtil.login(user.getId());
        log.info("[account-restore] 冷静期撤销注销：userId={}", user.getId());
        String avatar = StrUtil.isBlank(user.getAvatar()) ? DEFAULT_AVATAR_URL : user.getAvatar();
        return new LoginVO(user.getId(), user.getRole(), StpUtil.getTokenValue(), avatar);
    }

    /** 邮箱规范化（去空白+小写+格式校验）；包内共用（UserServiceImpl 改邮箱同口径） */
    static String normalizeEmail(String raw) {
        String email = StrUtil.trimToNull(raw);
        if (email == null) {
            throw new ClientException("邮箱不能为空");
        }
        email = email.toLowerCase();
        if (email.length() > EMAIL_MAX_LENGTH || !EMAIL_PATTERN.matcher(email).matches()) {
            throw new ClientException("邮箱格式不正确");
        }
        return email;
    }
}

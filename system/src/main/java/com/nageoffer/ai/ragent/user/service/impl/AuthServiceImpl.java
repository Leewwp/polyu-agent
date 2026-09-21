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
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.user.controller.request.LoginRequest;
import com.nageoffer.ai.ragent.user.controller.vo.LoginVO;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import com.nageoffer.ai.ragent.user.security.LoginRateLimiter;
import com.nageoffer.ai.ragent.user.security.PasswordCodec;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.user.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String DEFAULT_AVATAR_URL = "https://avatars.githubusercontent.com/u/583231?v=4";

    private final UserMapper userMapper;
    private final PasswordCodec passwordCodec;
    private final LoginRateLimiter loginRateLimiter;
    private final GuestSessionMigration guestSessionMigration;

    @Override
    public LoginVO login(LoginRequest requestParam) {
        String username = requestParam.getUsername();
        String password = requestParam.getPassword();
        if (StrUtil.isBlank(username) || StrUtil.isBlank(password)) {
            throw new ClientException("用户名或密码不能为空");
        }
        // 密码输入上限 64 字符（规避 BCrypt 72 字节截断口径）；超限走统一错误不另开口径
        if (password.length() > 64) {
            throw new ClientException("用户名或密码错误");
        }
        // 失败限速前置检查（5 次/15 分钟/（IP+账号）双键，超限锁 15 分钟；成功登录不计）
        loginRateLimiter.checkLocked(resolveClientIp(), username);
        // 登录双键：用户名或注册邮箱均可（注册用户 username=email，二者同值）
        UserDO user = userMapper.selectActiveByUsernameOrEmail(username.trim());
        if (user == null || !passwordCodec.matches(password, user.getPassword())) {
            loginRateLimiter.recordFailure(resolveClientIp(), username);
            throw new ClientException("用户名或密码错误");
        }
        // 注册用户必须完成邮箱验证（存量/管理员建/游客无邮箱不受限）
        if (user.getEmail() != null && (user.getEmailVerified() == null || user.getEmailVerified() != 1)) {
            throw new ClientException("邮箱未验证，请先完成邮箱验证");
        }
        // 存量明文密码：校验通过后立即升级为哈希（透明收敛，不需要用户操作）
        if (passwordCodec.needsUpgrade(user.getPassword())) {
            user.setPassword(passwordCodec.encode(password));
            userMapper.updateById(user);
        }
        if (user.getId() == null) {
            throw new ClientException("用户信息异常");
        }
        String loginId = user.getId().toString();
        // 游客会话升级迁移（#103）：认证成功后、写新登录态前捕获当前会话身份——
        // StpUtil.login 会就地替换 loginId，错过这个窗口就再也拿不到 guest 身份了
        Object previousLoginId = StpUtil.getLoginIdDefaultNull();
        if (previousLoginId != null && !loginId.equals(String.valueOf(previousLoginId))) {
            migrateGuestDataOnLogin(String.valueOf(previousLoginId), loginId);
        }
        StpUtil.login(loginId);
        String avatar = StrUtil.isBlank(user.getAvatar()) ? DEFAULT_AVATAR_URL : user.getAvatar();
        return new LoginVO(loginId, user.getRole(), StpUtil.getTokenValue(), avatar);
    }

    /**
     * 游客态登录正式账号：把 guest 名下四表会话平移给正式账号。
     * 仅当前会话确实是 guest 用户行时触发；迁移失败不阻断登录（记 error 日志）。
     */
    private void migrateGuestDataOnLogin(String guestLoginId, String loginId) {
        try {
            UserDO current = userMapper.selectById(guestLoginId);
            if (current == null || !ROLE_GUEST.equals(current.getRole())) {
                return;
            }
            int moved = guestSessionMigration.migrate(guestLoginId, loginId);
            if (moved > 0) {
                log.info("[guest-migrate] 游客会话升级迁移完成：guestId={} -> userId={} rows={}",
                        guestLoginId, loginId, moved);
            }
        } catch (Exception ex) {
            log.error("[guest-migrate] 游客会话迁移失败（不阻断登录）：guestId={} target={} 原因={}",
                    guestLoginId, loginId, ex.getMessage());
        }
    }

    @Override
    public void logout() {
        StpUtil.logout();
    }

    /**
     * guest 角色标记：与 admin/user 并列；管理面角色拦截器只认 admin，guest 等同普通用户权限面
     */
    private static final String ROLE_GUEST = "guest";

    /**
     * 匿名试用开关：默认 false；启用与额度终值由部署方决定
     */
    @Value("${ragent.anonymous.enabled:false}")
    private boolean anonymousEnabled;

    @Override
    public LoginVO guestLogin() {
        if (!anonymousEnabled) {
            throw new ClientException("匿名试用未开启");
        }
        // 已登录且是 guest：复用当前会话，不重复铸造游客账号
        Object existingLoginIdObj = StpUtil.getLoginIdDefaultNull();
        if (existingLoginIdObj != null) {
            UserDO current = userMapper.selectById(String.valueOf(existingLoginIdObj));
            if (current != null && ROLE_GUEST.equals(current.getRole())) {
                return buildLoginVO(current);
            }
        }
        // 游客铸造防刷：按 IP 每窗口限量铸造（游客实际用量由 T8 日配额约束，此为批量铸号闸）
        loginRateLimiter.tryAcquire("ragent:rl:guest:", resolveClientIp(), 10, java.time.Duration.ofSeconds(300));
        UserDO guest = UserDO.builder()
                .username("guest-" + IdUtil.fastSimpleUUID().substring(0, 12))
                // 随机双 UUID 加密落库：guest 不走密码登录，此值仅满足非空约束
                .password(passwordCodec.encode(IdUtil.fastSimpleUUID() + IdUtil.fastSimpleUUID()))
                .role(ROLE_GUEST)
                .build();
        userMapper.insert(guest);
        if (guest.getId() == null) {
            throw new ClientException("游客账号创建失败");
        }
        StpUtil.login(guest.getId().toString());
        return buildLoginVO(guest);
    }

    private LoginVO buildLoginVO(UserDO user) {
        String avatar = StrUtil.isBlank(user.getAvatar()) ? DEFAULT_AVATAR_URL : user.getAvatar();
        return new LoginVO(user.getId().toString(), user.getRole(), StpUtil.getTokenValue(), avatar);
    }

    private String resolveClientIp() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return "unknown";
        }
        HttpServletRequest request = attrs.getRequest();
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            return forwardedFor.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        return StringUtils.hasText(realIp) ? realIp : request.getRemoteAddr();
    }
}

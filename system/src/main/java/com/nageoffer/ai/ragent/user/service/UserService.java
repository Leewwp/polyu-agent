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

package com.nageoffer.ai.ragent.user.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.user.controller.request.ChangePasswordRequest;
import com.nageoffer.ai.ragent.user.controller.request.EmailChangeConfirmRequest;
import com.nageoffer.ai.ragent.user.controller.request.EmailChangeRequest;
import com.nageoffer.ai.ragent.user.controller.request.UserCreateRequest;
import com.nageoffer.ai.ragent.user.controller.request.UserPageRequest;
import com.nageoffer.ai.ragent.user.controller.request.UserUpdateRequest;
import com.nageoffer.ai.ragent.user.controller.vo.CurrentUserVO;
import com.nageoffer.ai.ragent.user.controller.vo.UserVO;

public interface UserService {

    /**
     * 分页查询用户列表
     */
    IPage<UserVO> pageQuery(UserPageRequest requestParam);

    /**
     * 创建用户
     */
    String create(UserCreateRequest requestParam);

    /**
     * 更新用户
     */
    void update(String id, UserUpdateRequest requestParam);

    /**
     * 删除用户
     */
    void delete(String id);

    /**
     * 修改当前用户密码
     */
    void changePassword(ChangePasswordRequest requestParam);

    /**
     * 当前登录用户资料（#104 个人中心）：/user/me 的扩展口径，补 email/emailVerified/createTime
     */
    CurrentUserVO currentUserDetail();

    /**
     * 改邮箱第一步（#104）：当前密码校验 + 新邮箱占用检查 + 发 scene=change 验证码 + 老邮箱通知信
     */
    void requestEmailChange(EmailChangeRequest requestParam);

    /**
     * 改邮箱第二步（#104）：验码落库（email + email_verified=1）+ 老邮箱成功通知信
     */
    void confirmEmailChange(EmailChangeConfirmRequest requestParam);
}

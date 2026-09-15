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

package com.nageoffer.ai.ragent.rag.service;

import com.nageoffer.ai.ragent.rag.controller.vo.PublicShareVO;
import com.nageoffer.ai.ragent.rag.controller.vo.ShareCreatedVO;
import com.nageoffer.ai.ragent.rag.controller.vo.ShareMineItemVO;

import java.util.List;

/**
 * 公开答案分享服务
 *
 * <p>不可变 Q&A 快照：创建时值复制，公开读绝不回链实时消息。
 */
public interface AnswerShareService {

    /**
     * 创建分享快照
     *
     * @param messageId 要分享的 assistant 消息 ID
     * @param userId    当前登录用户 ID（须与消息归属一致）
     * @return token 与过期时刻
     */
    ShareCreatedVO createShare(String messageId, String userId);

    /**
     * 匿名读取公开快照（不存在/已撤销/已过期统一同一异常语义，防 token 探测）
     */
    PublicShareVO getPublicShare(String token);

    /**
     * 撤销分享（owner 本人或管理员覆盖）
     */
    void revokeShare(String token, String userId, boolean adminOverride);

    /**
     * 本人分享列表
     */
    List<ShareMineItemVO> listMine(String userId);
}

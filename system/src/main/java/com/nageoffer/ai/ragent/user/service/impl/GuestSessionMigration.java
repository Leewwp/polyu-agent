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

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 游客会话升级迁移（#103）：游客态登录正式账号时，把游客名下 agent+RAG 两族会话
 * 改挂到正式账号（四表 user_id 平移）。
 *
 * <p>设计拍板（grilling Q10）：触发点=登录时（注册流程不建登录态，验证完仍需登录；
 * 登录时天然持有 guest 会话身份，且覆盖老用户从游客态登录场景）；记忆/状态不迁——
 * 游客期提取的记忆随壳销毁，正式账号从零积累；guest 壳留给既有 30 天游客清理任务
 * 自然回收，不写即时清理代码（迁移后其名下会话已清零，清理不会误伤已迁移数据）。
 *
 * <p>跨模块表（rag 的 t_conversation/t_message、agent 的 t_agent_*）经 JdbcTemplate
 * 直 SQL 触达——system 模块被 rag 依赖不能反向引用其 mapper（口径同
 * {@link AccountDeletionCascade}）。
 */
@Component
@RequiredArgsConstructor
public class GuestSessionMigration {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 四表 user_id 平移（同一事务）。目标账号已有会话天然共存（追加不改写）。
     *
     * @return 迁移的总行数（0=游客名下本就没有会话）
     */
    @Transactional(rollbackFor = Exception.class)
    public int migrate(String guestUserId, String targetUserId) {
        int moved = 0;
        moved += jdbcTemplate.update("UPDATE t_agent_conversation SET user_id = ? WHERE user_id = ?",
                targetUserId, guestUserId);
        moved += jdbcTemplate.update("UPDATE t_agent_message SET user_id = ? WHERE user_id = ?",
                targetUserId, guestUserId);
        moved += jdbcTemplate.update("UPDATE t_conversation SET user_id = ? WHERE user_id = ?",
                targetUserId, guestUserId);
        moved += jdbcTemplate.update("UPDATE t_message SET user_id = ? WHERE user_id = ?",
                targetUserId, guestUserId);
        return moved;
    }
}

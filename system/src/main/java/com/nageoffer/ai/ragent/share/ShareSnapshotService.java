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

package com.nageoffer.ai.ragent.share;

import java.util.Date;
import java.util.List;

/**
 * 统一分享快照机制（issue #124）：答案分享与会话分享共用同一状态机
 * （token 熵、ACTIVE/REVOKED、撤销幂等、过期数学、防枚举语义），粒度只差载荷形状。
 *
 * <p>payload 完全不透明：module 收 JSON 字符串原样存 JSONB 零解析，载荷类型与
 * 序列化归各粒度 adapter（rag/agent 各一）——过期策略由 {@link ShareProperties}
 * 自持，不进本接口。
 */
public interface ShareSnapshotService {

    /**
     * 创建分享快照
     *
     * @param kind          快照粒度
     * @param ownerUserId   创建者用户 ID（归属校验与撤销用）
     * @param conversationId 来源会话 ID（内部溯源，不进公开载荷）
     * @param lang          语言启发标记（zh/en）
     * @param payloadJson   不透明载荷 JSON（含粒度侧 contentVersion，由 adapter 序列化）
     * @return token 与过期时刻
     */
    ShareTicket create(ShareKind kind, String ownerUserId, String conversationId, String lang, String payloadJson);

    /**
     * 按 token 公开读（不存在/已撤销/已过期统一抛同一 ClientException，防 token 探测侧信道）
     */
    SharePublicView readByToken(String token);

    /**
     * 撤销分享（owner 归属校验 + 管理员覆盖 + 幂等：已 REVOKED 直接返回）
     */
    void revoke(String token, RevocationActor actor);

    /**
     * 注销级联：名下全部 ACTIVE 分享软撤销（行保留，随保留期由 {@link #purgeExpired} 清理）
     */
    void revokeOwnedBy(String userId);

    /**
     * 保留任务：硬删 expire_time 到期行（含 REVOKED 行），返回删除行数
     */
    int purgeExpired(Date before);

    /**
     * 本人分享列表（kind 为 null 时返回两种粒度，按创建时间倒序）
     */
    List<ShareOwnedView> listByOwner(String userId, ShareKind kind);

    /**
     * 管理面列表（kind 为 null 时返回两种粒度，按创建时间倒序）
     */
    List<ShareAdminView> adminList(ShareKind kind);
}

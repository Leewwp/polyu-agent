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

package com.nageoffer.ai.ragent.share.impl;

import cn.hutool.core.lang.Assert;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.share.RevocationActor;
import com.nageoffer.ai.ragent.share.ShareAdminView;
import com.nageoffer.ai.ragent.share.ShareKind;
import com.nageoffer.ai.ragent.share.ShareOwnedView;
import com.nageoffer.ai.ragent.share.ShareProperties;
import com.nageoffer.ai.ragent.share.SharePublicView;
import com.nageoffer.ai.ragent.share.ShareSnapshotService;
import com.nageoffer.ai.ragent.share.ShareTicket;
import com.nageoffer.ai.ragent.share.dao.entity.ShareSnapshotDO;
import com.nageoffer.ai.ragent.share.dao.mapper.ShareSnapshotMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * 统一分享快照机制实现（issue #124）：收编原 AnswerShareServiceImpl 与
 * AgentConversationShareServiceImpl 的全部共同不变量——token 熵（SecureRandom
 * 32B Base64URL）、ACTIVE/REVOKED 状态机、撤销幂等、过期数学、防枚举语义
 * （不存在/已撤销/已过期统一同一错误）。payload 零解析：JSON 字符串原样进出 JSONB。
 */
@Service
@RequiredArgsConstructor
public class ShareSnapshotServiceImpl implements ShareSnapshotService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_REVOKED = "REVOKED";
    private static final String KIND_CONVERSATION = "conversation";
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ShareSnapshotMapper shareSnapshotMapper;
    private final ShareProperties shareProperties;

    @Override
    public ShareTicket create(ShareKind kind, String ownerUserId, String conversationId, String lang, String payloadJson) {
        Date now = new Date();
        int defaultExpireDays = shareProperties.getDefaultExpireDays();
        Date expireTime = defaultExpireDays > 0 ? new Date(now.getTime() + defaultExpireDays * 86_400_000L) : null;
        ShareSnapshotDO share = ShareSnapshotDO.builder()
                .token(generateToken())
                .ownerUserId(ownerUserId)
                .kind(kind.getCode())
                .conversationId(conversationId)
                .lang(lang)
                .status(STATUS_ACTIVE)
                .expireTime(expireTime)
                .payload(payloadJson)
                .build();
        shareSnapshotMapper.insert(share);
        return ShareTicket.builder().token(share.getToken()).id(share.getId()).expireAt(expireTime).build();
    }

    @Override
    public SharePublicView readByToken(String token) {
        ShareSnapshotDO share = selectByToken(token);
        // 不存在/已撤销/已过期统一同一语义，防 token 探测侧信道
        if (share == null || STATUS_REVOKED.equals(share.getStatus()) || isExpired(share)) {
            throw new ClientException("分享链接无效或已撤销");
        }
        return SharePublicView.builder()
                .kind(toKind(share.getKind()))
                .lang(share.getLang())
                .payload(share.getPayload())
                .createTime(share.getCreateTime())
                .expireTime(share.getExpireTime())
                .build();
    }

    @Override
    public void revoke(String token, RevocationActor actor) {
        boolean adminOverride = actor != null && actor.isAdminOverride();
        if (!adminOverride) {
            Assert.notNull(actor, () -> new ClientException("未获取到当前登录用户"));
            Assert.notBlank(actor.getUserId(), () -> new ClientException("未获取到当前登录用户"));
        }
        ShareSnapshotDO share = selectByToken(token);
        Assert.notNull(share, () -> new ClientException("分享不存在"));
        if (!adminOverride && !Objects.equals(actor.getUserId(), share.getOwnerUserId())) {
            throw new ClientException("无权撤销该分享");
        }
        if (STATUS_REVOKED.equals(share.getStatus())) {
            return;
        }
        ShareSnapshotDO update = new ShareSnapshotDO();
        update.setId(share.getId());
        update.setStatus(STATUS_REVOKED);
        update.setRevokedTime(new Date());
        shareSnapshotMapper.updateById(update);
    }

    @Override
    public void revokeOwnedBy(String userId) {
        Date now = new Date();
        shareSnapshotMapper.update(null, new LambdaUpdateWrapper<ShareSnapshotDO>()
                .eq(ShareSnapshotDO::getOwnerUserId, userId)
                .eq(ShareSnapshotDO::getStatus, STATUS_ACTIVE)
                .set(ShareSnapshotDO::getStatus, STATUS_REVOKED)
                .set(ShareSnapshotDO::getRevokedTime, now)
                // 对齐原级联裸 SQL：软撤销同时回填 update_time（wrapper 更新不走字段自动填充）
                .set(ShareSnapshotDO::getUpdateTime, now));
    }

    @Override
    public int purgeExpired(Date before) {
        return shareSnapshotMapper.physicalDeleteExpired(before);
    }

    @Override
    public List<ShareOwnedView> listByOwner(String userId, ShareKind kind) {
        Assert.notBlank(userId, () -> new ClientException("未获取到当前登录用户"));
        LambdaQueryWrapper<ShareSnapshotDO> wrapper = new LambdaQueryWrapper<ShareSnapshotDO>()
                .eq(ShareSnapshotDO::getOwnerUserId, userId)
                .orderByDesc(ShareSnapshotDO::getCreateTime);
        if (kind != null) {
            wrapper.eq(ShareSnapshotDO::getKind, kind.getCode());
        }
        return shareSnapshotMapper.selectList(wrapper).stream()
                .map(share -> ShareOwnedView.builder()
                        .token(share.getToken())
                        .kind(toKind(share.getKind()))
                        .status(share.getStatus())
                        .payload(share.getPayload())
                        .expireTime(share.getExpireTime())
                        .createTime(share.getCreateTime())
                        .build())
                .toList();
    }

    @Override
    public List<ShareAdminView> adminList(ShareKind kind) {
        LambdaQueryWrapper<ShareSnapshotDO> wrapper = new LambdaQueryWrapper<ShareSnapshotDO>()
                .orderByDesc(ShareSnapshotDO::getCreateTime);
        if (kind != null) {
            wrapper.eq(ShareSnapshotDO::getKind, kind.getCode());
        }
        return shareSnapshotMapper.selectList(wrapper).stream()
                .map(share -> ShareAdminView.builder()
                        .token(share.getToken())
                        .kind(toKind(share.getKind()))
                        .ownerUserId(share.getOwnerUserId())
                        .status(share.getStatus())
                        .payload(share.getPayload())
                        .expireTime(share.getExpireTime())
                        .createTime(share.getCreateTime())
                        .build())
                .toList();
    }

    private ShareSnapshotDO selectByToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return shareSnapshotMapper.selectOne(new LambdaQueryWrapper<ShareSnapshotDO>()
                .eq(ShareSnapshotDO::getToken, token));
    }

    private boolean isExpired(ShareSnapshotDO share) {
        return share.getExpireTime() != null && share.getExpireTime().before(new Date());
    }

    /**
     * 256-bit 加密随机 token（Base64URL 无填充，43 字符），不可枚举
     */
    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private ShareKind toKind(String code) {
        return KIND_CONVERSATION.equals(code) ? ShareKind.CONVERSATION : ShareKind.ANSWER;
    }
}

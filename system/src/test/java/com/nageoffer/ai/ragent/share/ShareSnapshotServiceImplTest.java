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

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.share.dao.entity.ShareSnapshotDO;
import com.nageoffer.ai.ragent.share.dao.mapper.ShareSnapshotMapper;
import com.nageoffer.ai.ragent.share.impl.ShareSnapshotServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 统一分享快照 module 单元测试（issue #124）：token 形状与唯一性、
 * 防枚举三态统一语义、撤销归属/幂等/管理员覆盖、注销级联软撤销、
 * 保留任务硬删、过期策略（default-days 与 NULL=不过期）。
 */
class ShareSnapshotServiceImplTest {

    private static final String UNIFIED_INVALID_MESSAGE = "分享链接无效或已撤销";

    private ShareSnapshotMapper shareSnapshotMapper;
    private ShareProperties shareProperties;
    private ShareSnapshotServiceImpl shareSnapshotService;

    @BeforeEach
    void setUp() {
        shareSnapshotMapper = mock(ShareSnapshotMapper.class);
        shareProperties = new ShareProperties();
        shareProperties.setDefaultExpireDays(90);
        shareSnapshotService = new ShareSnapshotServiceImpl(shareSnapshotMapper, shareProperties);
        // LambdaUpdateWrapper.set 立即解析列名，纯单测无 MP 启动期缓存须手工初始化（仓内先例：AgentConversationServiceImplTest）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ShareSnapshotDO.class);
    }

    private ShareSnapshotDO stored(String status, Date expireTime) {
        return ShareSnapshotDO.builder()
                .id("s1")
                .token("t".repeat(43))
                .ownerUserId("u1")
                .kind("answer")
                .conversationId("c1")
                .lang("zh")
                .status(status)
                .expireTime(expireTime)
                .payload("{\"question\":\"q\"}")
                .createTime(new Date())
                .build();
    }

    @Test
    void createIssuesUrlSafeTokenAndOpaquePayload() {
        when(shareSnapshotMapper.insert(any(ShareSnapshotDO.class))).thenAnswer(invocation -> {
            ShareSnapshotDO share = invocation.getArgument(0);
            share.setId("snowflake-1");
            return 1;
        });

        ShareTicket first = shareSnapshotService.create(ShareKind.ANSWER, "u1", "c1", "zh", "{\"question\":\"q\"}");
        ShareTicket second = shareSnapshotService.create(ShareKind.CONVERSATION, "u1", "c2", "en", "{\"title\":\"t\"}");

        // token 形状：43 字符 Base64URL、两次生成互异（加密随机不可枚举）
        assertEquals(43, first.getToken().length());
        assertTrue(first.getToken().matches("[A-Za-z0-9_-]+"));
        assertEquals(43, second.getToken().length());
        assertNotEquals(first.getToken(), second.getToken());
        assertNotNull(first.getExpireAt());
        assertEquals("snowflake-1", first.getId());

        ArgumentCaptor<ShareSnapshotDO> captor = ArgumentCaptor.forClass(ShareSnapshotDO.class);
        verify(shareSnapshotMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        ShareSnapshotDO answerShare = captor.getAllValues().get(0);
        assertEquals("ACTIVE", answerShare.getStatus());
        assertEquals("answer", answerShare.getKind());
        assertEquals("u1", answerShare.getOwnerUserId());
        assertEquals("c1", answerShare.getConversationId());
        // payload 不透明：create 传入什么就落什么，module 零解析
        assertEquals("{\"question\":\"q\"}", answerShare.getPayload());
        assertNotNull(answerShare.getExpireTime());
        assertEquals("conversation", captor.getAllValues().get(1).getKind());
    }

    @Test
    void noExpiryWhenDaysNonPositive() {
        shareProperties.setDefaultExpireDays(0);
        when(shareSnapshotMapper.insert(any(ShareSnapshotDO.class))).thenReturn(1);

        ShareTicket ticket = shareSnapshotService.create(ShareKind.ANSWER, "u1", "c1", "zh", "{}");
        assertNull(ticket.getExpireAt());
    }

    @Test
    void readByTokenReturnsViewForActiveShare() {
        when(shareSnapshotMapper.selectOne(any())).thenReturn(stored("ACTIVE", null));

        SharePublicView view = shareSnapshotService.readByToken("t".repeat(43));
        assertEquals(ShareKind.ANSWER, view.getKind());
        assertEquals("zh", view.getLang());
        assertEquals("{\"question\":\"q\"}", view.getPayload());
        assertNotNull(view.getCreateTime());
        assertNull(view.getExpireTime());
    }

    @Test
    void revokedExpiredAndMissingShareOneSemantics() {
        when(shareSnapshotMapper.selectOne(any())).thenReturn(null);
        ClientException missing = assertThrows(ClientException.class, () -> shareSnapshotService.readByToken("nope"));
        assertEquals(UNIFIED_INVALID_MESSAGE, missing.getMessage());

        when(shareSnapshotMapper.selectOne(any())).thenReturn(stored("REVOKED", null));
        ClientException revoked = assertThrows(ClientException.class, () -> shareSnapshotService.readByToken("t".repeat(43)));
        assertEquals(UNIFIED_INVALID_MESSAGE, revoked.getMessage());

        when(shareSnapshotMapper.selectOne(any())).thenReturn(stored("ACTIVE", new Date(System.currentTimeMillis() - 1000)));
        ClientException expired = assertThrows(ClientException.class, () -> shareSnapshotService.readByToken("t".repeat(43)));
        assertEquals(UNIFIED_INVALID_MESSAGE, expired.getMessage());

        // 空白 token 同语义（selectByToken 对 blank 直接判 null，不发查询）
        when(shareSnapshotMapper.selectOne(any())).thenReturn(null);
        ClientException blank = assertThrows(ClientException.class, () -> shareSnapshotService.readByToken("  "));
        assertEquals(UNIFIED_INVALID_MESSAGE, blank.getMessage());
    }

    @Test
    void revokeChecksOwnershipAndAdminOverride() {
        when(shareSnapshotMapper.selectOne(any())).thenReturn(stored("ACTIVE", null));

        // 非 owner 且非管理员：拒绝且不落任何更新
        ClientException foreign = assertThrows(ClientException.class,
                () -> shareSnapshotService.revoke("t".repeat(43), RevocationActor.owner("u2")));
        assertEquals("无权撤销该分享", foreign.getMessage());
        verify(shareSnapshotMapper, never()).updateById(any(ShareSnapshotDO.class));

        // owner：撤销成功
        shareSnapshotService.revoke("t".repeat(43), RevocationActor.owner("u1"));
        ArgumentCaptor<ShareSnapshotDO> captor = ArgumentCaptor.forClass(ShareSnapshotDO.class);
        verify(shareSnapshotMapper).updateById(captor.capture());
        assertEquals("REVOKED", captor.getValue().getStatus());
        assertNotNull(captor.getValue().getRevokedTime());

        // 管理员覆盖：跳过 owner 校验（非 owner 也能撤）
        shareSnapshotService.revoke("t".repeat(43), RevocationActor.admin());
        verify(shareSnapshotMapper, org.mockito.Mockito.times(2)).updateById(any(ShareSnapshotDO.class));
    }

    @Test
    void revokeMissingShareAndBlankUserHaveStableMessages() {
        when(shareSnapshotMapper.selectOne(any())).thenReturn(null);
        ClientException missing = assertThrows(ClientException.class,
                () -> shareSnapshotService.revoke("nope", RevocationActor.owner("u1")));
        assertEquals("分享不存在", missing.getMessage());

        when(shareSnapshotMapper.selectOne(any())).thenReturn(stored("ACTIVE", null));
        ClientException anonymous = assertThrows(ClientException.class,
                () -> shareSnapshotService.revoke("t".repeat(43), RevocationActor.owner("")));
        assertEquals("未获取到当前登录用户", anonymous.getMessage());
    }

    @Test
    void revokeIsIdempotentOnRevokedShare() {
        when(shareSnapshotMapper.selectOne(any())).thenReturn(stored("REVOKED", new Date()));
        shareSnapshotService.revoke("t".repeat(43), RevocationActor.owner("u1"));
        verify(shareSnapshotMapper, never()).updateById(any(ShareSnapshotDO.class));
    }

    @Test
    void revokeOwnedByDelegatesSingleStatementToDao() {
        shareSnapshotService.revokeOwnedBy("u1");
        // 一条 UPDATE 收编此前两段手写表名 SQL（#104 级联补洞的教训：fork 一侧静默落后）
        verify(shareSnapshotMapper).update(isNull(), any());
    }

    @Test
    void purgeExpiredPhysicallyDeletesViaDedicatedStatement() {
        // 物理删（非 BaseMapper.delete 的 @TableLogic 软删）——保留任务硬删语义
        when(shareSnapshotMapper.physicalDeleteExpired(any(Date.class))).thenReturn(3);
        Date before = new Date();
        assertEquals(3, shareSnapshotService.purgeExpired(before));
        verify(shareSnapshotMapper).physicalDeleteExpired(before);
    }

    @Test
    void listByOwnerRejectsBlankUser() {
        assertThrows(ClientException.class, () -> shareSnapshotService.listByOwner(" ", null));
    }
}

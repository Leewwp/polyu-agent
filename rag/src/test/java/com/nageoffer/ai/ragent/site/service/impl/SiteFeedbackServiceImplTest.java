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

package com.nageoffer.ai.ragent.site.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.site.controller.vo.SiteFeedbackVO;
import com.nageoffer.ai.ragent.site.dao.entity.SiteFeedbackDO;
import com.nageoffer.ai.ragent.site.dao.mapper.SiteFeedbackMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SiteFeedbackServiceImplTest {

    private final SiteFeedbackMapper mapper = mock(SiteFeedbackMapper.class);
    private final SiteFeedbackServiceImpl service = new SiteFeedbackServiceImpl(mapper);

    private static final String VALID_CONTENT = "这是一个长度合法的反馈内容，超过十个字。";

    @Test
    void submitsWithMaskedDefaultsAndCountsDailyQuota() {
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        service.submit(VALID_CONTENT, " me@example.com ", "1.2.3.4");

        ArgumentCaptor<SiteFeedbackDO> captor = ArgumentCaptor.forClass(SiteFeedbackDO.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo(VALID_CONTENT);
        assertThat(captor.getValue().getContact()).isEqualTo("me@example.com");
        assertThat(captor.getValue().getClientIp()).isEqualTo("1.2.3.4");
        assertThat(captor.getValue().getStatus()).isZero();
    }

    /**
     * D1 IP 日限：当日已有 5 条（上限）时第 6 条拒绝且不落库
     */
    @Test
    void rejectsSixthSubmissionSameDay() {
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(5L);

        assertThatThrownBy(() -> service.submit(VALID_CONTENT, null, "1.2.3.4"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("上限");
        verify(mapper, never()).insert(any(SiteFeedbackDO.class));
    }

    @Test
    void rejectsContentShorterThanTenChars() {
        assertThatThrownBy(() -> service.submit("太短", null, "1.2.3.4"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("10–2000");
        verify(mapper, never()).insert(any(SiteFeedbackDO.class));
    }

    @Test
    void rejectsContentLongerThanTwoThousandChars() {
        String tooLong = "字".repeat(2001);
        assertThatThrownBy(() -> service.submit(tooLong, null, "1.2.3.4"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("10–2000");
    }

    @Test
    void rejectsContactLongerThanHundredChars() {
        String longContact = "c".repeat(101);
        assertThatThrownBy(() -> service.submit(VALID_CONTENT, longContact, "1.2.3.4"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("100");
    }

    @Test
    void allowsOptionalContactToBeNull() {
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        service.submit(VALID_CONTENT, null, "1.2.3.4");

        ArgumentCaptor<SiteFeedbackDO> captor = ArgumentCaptor.forClass(SiteFeedbackDO.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getContact()).isNull();
    }

    @Test
    void updatesStatusWithValidationAndExistence() {
        SiteFeedbackDO existing = SiteFeedbackDO.builder().id(7L).status(0).build();
        when(mapper.selectById(7L)).thenReturn(existing);

        service.updateStatus(7L, 1);

        ArgumentCaptor<SiteFeedbackDO> captor = ArgumentCaptor.forClass(SiteFeedbackDO.class);
        verify(mapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(1);

        assertThatThrownBy(() -> service.updateStatus(7L, 9))
                .isInstanceOf(ClientException.class);
        when(mapper.selectById(404L)).thenReturn(null);
        assertThatThrownBy(() -> service.updateStatus(404L, 1))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void pageQueryMapsIpToMaskedForm() {
        SiteFeedbackDO row = SiteFeedbackDO.builder()
                .id(1L).content("内容").contact(null).clientIp("10.20.30.40").status(0)
                .build();
        when(mapper.selectPage(any(IPage.class), any(Wrapper.class)))
                .thenAnswer(invocation -> {
                    IPage<SiteFeedbackDO> page = invocation.getArgument(0);
                    page.setRecords(List.of(row));
                    page.setTotal(1);
                    return page;
                });

        IPage<SiteFeedbackVO> result = service.pageQuery(1, 10, null);

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().get(0).getClientIpMasked()).isEqualTo("10.20.*.*");
    }

    @Test
    void deleteDelegatesById() {
        when(mapper.deleteById(anyLong())).thenReturn(1);
        service.delete(9L);
        verify(mapper).deleteById(9L);
    }
}

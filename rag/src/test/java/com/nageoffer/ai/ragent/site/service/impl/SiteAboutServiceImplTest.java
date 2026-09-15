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

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import com.nageoffer.ai.ragent.site.controller.vo.SiteAboutVO;
import com.nageoffer.ai.ragent.site.dao.entity.SiteAboutDO;
import com.nageoffer.ai.ragent.site.dao.mapper.SiteAboutMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SiteAboutServiceImplTest {

    private final SiteAboutMapper mapper = mock(SiteAboutMapper.class);
    private final FileStorageService storage = mock(FileStorageService.class);
    private final SiteAboutServiceImpl service = new SiteAboutServiceImpl(mapper, storage);

    @Test
    void getAboutReturnsEmptyContentWhenRowMissing() {
        when(mapper.selectById(1L)).thenReturn(null);

        SiteAboutVO vo = service.getAbout();

        assertThat(vo.getContent()).isEmpty();
        assertThat(vo.getQrImageUrl()).isNull();
        assertThat(vo.getQrImageUrlAlt()).isNull();
    }

    @Test
    void saveInsertsWhenAbsent() {
        when(mapper.selectById(1L)).thenReturn(null);

        service.saveAbout("第一版内容", "first version", null, null);

        ArgumentCaptor<SiteAboutDO> captor = ArgumentCaptor.forClass(SiteAboutDO.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(1L);
        assertThat(captor.getValue().getContent()).isEqualTo("第一版内容");
        assertThat(captor.getValue().getContentEn()).isEqualTo("first version");
    }

    /**
     * upsert 幂等：已有行时走 update 整段覆盖，不再 insert
     */
    @Test
    void saveUpdatesWhenPresent() {
        when(mapper.selectById(1L)).thenReturn(
                SiteAboutDO.builder().id(1L).content("旧内容").build());

        service.saveAbout("新内容", null, "https://assets/qr1.png", "https://assets/qr2.png");

        ArgumentCaptor<SiteAboutDO> captor = ArgumentCaptor.forClass(SiteAboutDO.class);
        verify(mapper).updateById(captor.capture());
        verify(mapper, never()).insert(any(SiteAboutDO.class));
        assertThat(captor.getValue().getContent()).isEqualTo("新内容");
        assertThat(captor.getValue().getQrImageUrl()).isEqualTo("https://assets/qr1.png");
        assertThat(captor.getValue().getQrImageUrlAlt()).isEqualTo("https://assets/qr2.png");
        // 英文内容传 null=清空，实体 ALWAYS 策略保证写回（同 qr 两列口径）
        assertThat(captor.getValue().getContentEn()).isNull();
    }

    @Test
    void uploadQrRejectsNonImageType() {
        assertThatThrownBy(() -> service.uploadQr(new byte[]{1}, "a.gif", "image/gif"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("png");
    }

    @Test
    void uploadQrRejectsOversizedFile() {
        byte[] tooBig = new byte[2 * 1024 * 1024 + 1];
        assertThatThrownBy(() -> service.uploadQr(tooBig, "big.png", "image/png"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("2MB");
    }

    @Test
    void uploadQrReturnsAssetUrl() {
        // uploadAsset 返回裸对象 key（生产形态），service 必须经 getPublicUrl 换成公网 URL
        StoredFileDTO stored = new StoredFileDTO();
        stored.setUrl("assets/site/61298a0f.png");
        when(storage.uploadAsset(any(), any(), any())).thenReturn(stored);
        when(storage.getPublicUrl("assets/site/61298a0f.png"))
                .thenReturn("http://localhost:9000/ragent-assets/assets/site/61298a0f.png");

        String url = service.uploadQr(new byte[]{1, 2}, "qr.png", "image/png");

        assertThat(url).isEqualTo("http://localhost:9000/ragent-assets/assets/site/61298a0f.png");
    }
}

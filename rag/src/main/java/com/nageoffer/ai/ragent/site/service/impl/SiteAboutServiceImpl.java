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

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import com.nageoffer.ai.ragent.site.controller.vo.SiteAboutVO;
import com.nageoffer.ai.ragent.site.dao.entity.SiteAboutDO;
import com.nageoffer.ai.ragent.site.dao.mapper.SiteAboutMapper;
import com.nageoffer.ai.ragent.site.service.SiteAboutService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 关于页实现：单行 upsert（id 固定 1，无行则插）；QR 上传走既有资产桶
 */
@Service
@RequiredArgsConstructor
public class SiteAboutServiceImpl implements SiteAboutService {

    /**
     * 二维码大小上限（应用层，全链路限额已核：Spring 50MB / nginx 100m 均在其上）
     */
    private static final long QR_MAX_BYTES = 2 * 1024 * 1024;
    private static final Set<String> QR_ALLOWED_TYPES = Set.of("image/png", "image/jpeg", "image/webp");
    private static final long SINGLE_ROW_ID = 1L;

    private final SiteAboutMapper siteAboutMapper;
    private final FileStorageService fileStorageService;

    @Override
    public SiteAboutVO getAbout() {
        SiteAboutDO entity = siteAboutMapper.selectById(SINGLE_ROW_ID);
        if (entity == null) {
            return SiteAboutVO.builder().content("").qrImageUrl(null).qrImageUrlAlt(null).build();
        }
        return SiteAboutVO.builder()
                .content(StrUtil.nullToEmpty(entity.getContent()))
                .qrImageUrl(entity.getQrImageUrl())
                .qrImageUrlAlt(entity.getQrImageUrlAlt())
                .updateTime(entity.getUpdateTime())
                .build();
    }

    @Override
    public void saveAbout(String content, String qrImageUrl, String qrImageUrlAlt) {
        Assert.notNull(content, () -> new ClientException("关于页内容不能为空"));
        Assert.isTrue(content.length() <= 20_000, () -> new ClientException("关于页内容过长"));
        SiteAboutDO existing = siteAboutMapper.selectById(SINGLE_ROW_ID);
        if (existing == null) {
            siteAboutMapper.insert(SiteAboutDO.builder()
                    .id(SINGLE_ROW_ID)
                    .content(content)
                    .qrImageUrl(qrImageUrl)
                    .qrImageUrlAlt(qrImageUrlAlt)
                    .build());
            return;
        }
        SiteAboutDO update = new SiteAboutDO();
        update.setId(SINGLE_ROW_ID);
        update.setContent(content);
        update.setQrImageUrl(qrImageUrl);
        update.setQrImageUrlAlt(qrImageUrlAlt);
        siteAboutMapper.updateById(update);
    }

    @Override
    public String uploadQr(byte[] content, String filename, String contentType) {
        Assert.isTrue(content != null && content.length > 0, () -> new ClientException("上传内容为空"));
        Assert.isTrue(content.length <= QR_MAX_BYTES, () -> new ClientException("二维码图片不能超过 2MB"));
        String type = StrUtil.blankToDefault(contentType, "");
        Assert.isTrue(QR_ALLOWED_TYPES.contains(type.toLowerCase()),
                () -> new ClientException("仅支持 png / jpg / webp 图片"));
        StoredFileDTO stored = fileStorageService.uploadAsset(content,
                StrUtil.blankToDefault(filename, "site-qr.png"), contentType);
        return stored.getUrl();
    }
}

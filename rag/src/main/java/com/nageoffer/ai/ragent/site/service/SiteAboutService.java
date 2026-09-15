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

package com.nageoffer.ai.ragent.site.service;

import com.nageoffer.ai.ragent.site.controller.vo.SiteAboutVO;

/**
 * 关于页内容服务（doc 25）：单行 upsert + 二维码上传
 */
public interface SiteAboutService {

    /**
     * 公开只读（未配置时返回空内容态，不抛错）
     */
    SiteAboutVO getAbout();

    /**
     * upsert：无行则插，有行则整段覆盖（内容与两码 URL 一起保存）
     */
    void saveAbout(String content, String qrImageUrl, String qrImageUrlAlt);

    /**
     * 二维码上传：校验 image/{png,jpeg,webp}、≤2MB → 资产桶公共 URL；
     * 调用方拿 URL 再走 saveAbout 落表
     */
    String uploadQr(byte[] content, String filename, String contentType);
}

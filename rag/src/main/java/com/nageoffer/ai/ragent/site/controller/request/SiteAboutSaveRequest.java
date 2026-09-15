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

package com.nageoffer.ai.ragent.site.controller.request;

import lombok.Data;

/**
 * 关于页内容保存请求（upsert：无行则插，有行则整段覆盖）
 */
@Data
public class SiteAboutSaveRequest {

    /**
     * markdown 内容
     */
    private String content;

    /**
     * 英文 markdown（可空=前台英文档回落中文）
     */
    private String contentEn;

    /**
     * 赞赏二维码 URL（可空）
     */
    private String qrImageUrl;

    /**
     * 第二张赞赏二维码 URL（可空）
     */
    private String qrImageUrlAlt;
}

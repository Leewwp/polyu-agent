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

package com.nageoffer.ai.ragent.site.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 关于页公开只读 VO（两码 URL 皆空时前端赞赏区整区不渲染）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SiteAboutVO {

    /**
     * markdown 内容（未配置时为空串，前端出空态）
     */
    private String content;

    /**
     * 赞赏二维码 URL
     */
    private String qrImageUrl;

    /**
     * 第二张赞赏二维码 URL
     */
    private String qrImageUrlAlt;

    private Date updateTime;
}

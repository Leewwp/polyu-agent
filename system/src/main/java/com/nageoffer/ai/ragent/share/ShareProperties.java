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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * 统一分享机制配置（share.*，issue #124 归一两套旧 flag 块）。
 * 总开关 share.enabled 由控制器 @ConditionalOnProperty 消费（不进本类）；
 * 粒度侧内容版本标记维持各粒度自持（rag.share.content-version / agent.share.content-version）。
 */
@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "share")
public class ShareProperties {

    /**
     * 分享默认过期天数；0 或负数 = 不过期（终值由部署方确定，建议 90 天或不过期）
     */
    private int defaultExpireDays = 90;
}

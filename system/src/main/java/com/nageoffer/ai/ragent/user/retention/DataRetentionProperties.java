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

package com.nageoffer.ai.ragent.user.retention;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import org.springframework.context.annotation.Configuration;

/**
 * 数据保留期清理配置（U6，doc 15 §2.2.5 八类决议）。扫描间隔由 @Scheduled 占位符
 * {@code ragent.retention.scan-delay-ms / initial-delay-ms} 承载，不入本类。
 */
@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "ragent.retention")
public class DataRetentionProperties {

    /**
     * 总开关：保留期清理是 doc 15 §2.2.11 点名的合规缺口（原状零清理任务），默认开；关闭仅限应急
     */
    private boolean enabled = true;

    /**
     * 注销软删冷静期天数：delete_time 早于 now-该值 即到期硬删；
     * 与 AccountLifecycleServiceImpl.DELETE_GRACE_DAYS（恢复窗口）同值同源，改值须两处同步
     */
    private int purgeGraceDays = 30;

    /**
     * guest 账号保留天数：自铸号（create_time）起算，到期连同名下对话/匿名化反馈级联清理（决议 30 天）
     */
    private int guestRetentionDays = 30;

    /**
     * 反馈记录保留天数：create_time 早于 now-该值 即整行删除（comment 自由文本按 PII 对待；决议 400 天）
     */
    private int feedbackRetentionDays = 400;
}

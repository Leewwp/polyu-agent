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

package com.nageoffer.ai.ragent;

import com.mzt.logapi.starter.annotation.EnableLogRecord;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Ragent 核心应用启动类
 */
@SpringBootApplication
@EnableScheduling
@EnableLogRecord(tenant = "ragent", proxyTargetClass = true)
@MapperScan(basePackages = {
        "com.nageoffer.ai.ragent.rag.dao.mapper",
        "com.nageoffer.ai.ragent.ingestion.dao.mapper",
        "com.nageoffer.ai.ragent.knowledge.dao.mapper",
        "com.nageoffer.ai.ragent.user.dao.mapper",
        "com.nageoffer.ai.ragent.audit.dao.mapper",
        "com.nageoffer.ai.ragent.sample.dao.mapper",
        "com.nageoffer.ai.ragent.agent.dao.mapper",
        "com.nageoffer.ai.ragent.news.dao.mapper",
        "com.nageoffer.ai.ragent.site.dao.mapper",
        // 会话分享自有新包（issue #82）：MapperScan 会把包内全部接口注册为 mapper，
        // 服务接口混扫会与 @Service 实现撞双 bean——mapper 独占 dao 子包，扫描只指子包
        "com.nageoffer.ai.ragent.agent.share.dao"
})
public class RagentApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagentApplication.class, args);
    }
}

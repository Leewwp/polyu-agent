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

package com.nageoffer.ai.ragent.mcp;

import com.nageoffer.ai.ragent.mcp.rag.RagPublicApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #219 生产启动失败的回归门：真实 {@link McpServerApplication} 全组件扫描上下文必须能起。
 * <p>
 * McpHttpProtocolTest 用手搓最小上下文直构 executor（不经 @Component 扫描），恰好放过
 * 「容器缺 bean」类接线缺陷——RagPublicApiClient 曾构造注入 RestClient.Builder，Boot 4.1
 * 模块化 autoconfigure 后容器默认不提供该 bean，生产崩溃循环而当时全部测试绿。
 * 本测试按生产同款装配起上下文，任何 @Component 接线缺陷在此先行变红。
 */
@SpringBootTest(classes = McpServerApplication.class)
class McpServerApplicationContextTests {

    @Autowired
    private RagPublicApi ragPublicApi;

    @Test
    void contextBootsWithRealComponentWiring() {
        assertThat(ragPublicApi).isNotNull();
    }
}

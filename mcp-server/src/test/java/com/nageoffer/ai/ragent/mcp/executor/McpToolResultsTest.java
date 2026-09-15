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

package com.nageoffer.ai.ragent.mcp.executor;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolResultsTest {

    private static final String TOOL_ID = "query_order";

    /**
     * 键名带反向 DNS 前缀是规范要求，且必须与调用方那份常量逐字符一致，改一侧就断
     */
    @Test
    void userIdKeyMustStayInSyncWithCaller() {
        assertEquals("com.nageoffer.ragent/userId", McpToolResults.META_USER_ID);
    }

    @Test
    void shouldReadUserIdFromMeta() {
        assertEquals("2001", McpToolResults.userId(request(Map.of(McpToolResults.META_USER_ID, "2001"))));
    }

    /**
     * 取不到一律 null：没带、带了空串、带了个非字符串、键名写成裸 userId，对用户态工具是同一件事
     */
    @Test
    void shouldReturnNullWhenIdentityIsUnusable() {
        assertNull(McpToolResults.userId(request(null)));
        assertNull(McpToolResults.userId(request(Map.of())));
        assertNull(McpToolResults.userId(request(Map.of(McpToolResults.META_USER_ID, "   "))));
        assertNull(McpToolResults.userId(request(Map.of(McpToolResults.META_USER_ID, 2001))));
        assertNull(McpToolResults.userId(request(Map.of("userId", "2001"))));
        assertNull(McpToolResults.userId(null));
    }

    /**
     * 回绝要走 isError，否则模型会把这句话当正常业务结论转述成「你没有订单」
     */
    @Test
    void identityRequiredMustBeAnError() {
        CallToolResult result = McpToolResults.identityRequired(TOOL_ID);

        assertTrue(result.isError());
        assertTrue(((TextContent) result.content().get(0)).text().contains("身份"));
    }

    /**
     * 不许把这句话说成「请重新登录」：用户本来就在登录态，断的是调用链，重登治不好，只会把排查引到死路上
     */
    @Test
    void identityRequiredMustNotBlameTheUser() {
        String text = ((TextContent) McpToolResults.identityRequired(TOOL_ID).content().get(0)).text();

        assertFalse(text.contains("登录"));
    }

    private CallToolRequest request(Map<String, Object> meta) {
        return new CallToolRequest(TOOL_ID, new HashMap<>(), meta);
    }
}

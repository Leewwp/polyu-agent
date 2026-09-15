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

package com.nageoffer.ai.ragent.rag.core.mcp;

import cn.hutool.core.util.StrUtil;

import java.util.Map;

/**
 * MCP 调用的 {@code _meta} 约定
 * <p>
 * 只放主体引用（谁在调），不放部门、角色、权限这类授权属性——那些是判定结果，得由服务端自己解析：
 * 从调用方带过去等于把判定权交到了数据持有方的对侧，而且一次 run 内它是快照，中途被收回也不生效。
 * 身份同样不进 inputSchema，一旦进去真值来源就从登录态变成对话内容，谁都能以谁的名义提交。
 * 键名的反向 DNS 前缀是规范要求，含 mcp / modelcontextprotocol 的标签段被协议自身保留。
 * mcp-server 是独立进程、不依赖本模块，那侧有一份同值常量，改这里必须同步改那里
 */
public final class McpCallMeta {

    /**
     * 当前登录用户，服务端据此圈定数据范围
     */
    public static final String USER_ID = "com.nageoffer.ragent/userId";

    private McpCallMeta() {
    }

    /**
     * 身份缺失时给空表而不是塞 null 值，服务端只需判「取不到」一种情况
     * <p>
     * 每次调用都带一遍而不是挂到会话上：McpSyncClient 是全进程共享的单例，身份绑在连接上就是跨用户串号
     */
    public static Map<String, Object> ofUser(String userId) {
        return StrUtil.isBlank(userId) ? Map.of() : Map.of(USER_ID, StrUtil.trim(userId));
    }

    /**
     * 日志取主体引用只走这里，meta 里其余内容一律不该进日志
     */
    public static String userIdOf(Map<String, Object> meta) {
        Object value = meta == null ? null : meta.get(USER_ID);
        return value instanceof String userId ? userId : null;
    }
}

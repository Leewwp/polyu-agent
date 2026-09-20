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

package com.nageoffer.ai.ragent.audit.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nageoffer.ai.ragent.audit.constant.BizChangeBizType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Set;

/**
 * 审计快照读时脱敏（L44/#95）：bizType=USER 的变更前/后快照与 diff 含
 * 登录邮箱（username）、联系邮箱（email）、密码散列等 PII，管理端审计页
 * 整段渲染+复制即整段外流——按拍板在后端读路径掩码，前端拿到的即脱敏文本。
 * 仅 USER 生效，其余 bizType 原样透传；掩码 best-effort，形状意外不阻断读路径。
 */
@Component
@RequiredArgsConstructor
public class AuditSnapshotMasker {

    /**
     * USER 快照 PII 键（写入侧=UserDO 序列化形态；用户实体新增 PII 字段时在此维护）
     */
    private static final Set<String> USER_PII_FIELDS = Set.of("username", "email");

    /**
     * 无论长短整体掩码的字段（密码散列不保留任何片段）
     */
    private static final Set<String> FULL_MASK_FIELDS = Set.of("password");

    private static final String FULL_MASK = "******";

    private final ObjectMapper objectMapper;

    /**
     * 掩码变更前/后快照 JSON（顶层 PII 键）
     */
    public String maskSnapshot(String bizType, String snapshot) {
        if (!isUserBiz(bizType) || !StringUtils.hasText(snapshot)) {
            return snapshot;
        }
        try {
            JsonNode root = objectMapper.readTree(snapshot);
            if (!root.isObject()) {
                return snapshot;
            }
            ObjectNode object = (ObjectNode) root;
            USER_PII_FIELDS.forEach(field -> maskObjectField(object, field, false));
            FULL_MASK_FIELDS.forEach(field -> maskObjectField(object, field, true));
            return objectMapper.writeValueAsString(object);
        } catch (Exception e) {
            return snapshot;
        }
    }

    /**
     * 掩码 changeDiff（数组项 {field:"/username", before, after}——按字段路径末段识别 PII）
     */
    public String maskDiff(String bizType, String diff) {
        if (!isUserBiz(bizType) || !StringUtils.hasText(diff)) {
            return diff;
        }
        try {
            JsonNode root = objectMapper.readTree(diff);
            if (!root.isArray()) {
                return diff;
            }
            for (JsonNode entry : root) {
                if (!entry.isObject()) {
                    continue;
                }
                JsonNode field = entry.get("field");
                if (field == null || !field.isTextual()) {
                    continue;
                }
                String leaf = leafSegment(field.asText());
                if (USER_PII_FIELDS.contains(leaf)) {
                    maskObjectField((ObjectNode) entry, "before", false);
                    maskObjectField((ObjectNode) entry, "after", false);
                } else if (FULL_MASK_FIELDS.contains(leaf)) {
                    maskObjectField((ObjectNode) entry, "before", true);
                    maskObjectField((ObjectNode) entry, "after", true);
                }
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return diff;
        }
    }

    private boolean isUserBiz(String bizType) {
        return BizChangeBizType.USER.equals(bizType);
    }

    private void maskObjectField(ObjectNode object, String field, boolean fullMask) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) {
            return;
        }
        object.put(field, fullMask ? FULL_MASK : maskText(value.isTextual() ? value.asText() : value.toString()));
    }

    /**
     * 部分掩码：保留首二尾二便于人工辨认「改的是哪个邮箱」，中段一律不可见
     */
    private String maskText(String value) {
        if (value.length() <= 2) {
            return "**";
        }
        if (value.length() < 6) {
            return value.charAt(0) + "***";
        }
        return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }

    private String leafSegment(String jsonPointerPath) {
        int slash = jsonPointerPath.lastIndexOf('/');
        return slash >= 0 ? jsonPointerPath.substring(slash + 1) : jsonPointerPath;
    }
}

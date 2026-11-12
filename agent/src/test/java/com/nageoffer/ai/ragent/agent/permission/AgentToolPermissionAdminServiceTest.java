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

package com.nageoffer.ai.ragent.agent.permission;

import com.nageoffer.ai.ragent.agent.dao.entity.AgentToolPermissionDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentToolPermissionMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端 CRUD 测试（#487 验收 9，mock mapper）：校验语义/新增/更新/唯一冲突翻译/删除。
 * 路径面由 AdminPathPatternsTest（匹配力）与 bootstrap AdminSurfaceContractTest（覆盖面）钉住。
 */
class AgentToolPermissionAdminServiceTest {

    private AgentToolPermissionMapper mapper;
    private AgentToolPermissionAdminService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AgentToolPermissionMapper.class);
        service = new AgentToolPermissionAdminService(mapper);
    }

    private static AgentToolPermissionSaveRequest request(String id, String role, String toolId,
                                                          String decision, Integer enabled) {
        AgentToolPermissionSaveRequest req = new AgentToolPermissionSaveRequest();
        req.setId(id);
        req.setRole(role);
        req.setToolId(toolId);
        req.setDecision(decision);
        req.setEnabled(enabled);
        return req;
    }

    @Test
    void 新增_合法请求规范化后落库_通配哨兵与精确名均可() {
        service.save(request(null, "guest", " mcp:* ", "DENY", null));
        service.save(request(null, "user", "search_news", "ASK", 0));

        ArgumentCaptor<AgentToolPermissionDO> row = ArgumentCaptor.forClass(AgentToolPermissionDO.class);
        verify(mapper, org.mockito.Mockito.times(2)).insert(row.capture());
        AgentToolPermissionDO first = row.getAllValues().get(0);
        AgentToolPermissionDO second = row.getAllValues().get(1);
        // tool_id trim 规范化；enabled 缺省 1、显式 0 照收
        assertThat(first.getToolId()).isEqualTo("mcp:*");
        assertThat(first.getRole()).isEqualTo("guest");
        assertThat(first.getDecision()).isEqualTo("DENY");
        assertThat(first.getEnabled()).isEqualTo(1);
        assertThat(second.getToolId()).isEqualTo("search_news");
        assertThat(second.getEnabled()).isZero();
    }

    @Test
    void 新增_唯一索引冲突翻译为友好报错() {
        when(mapper.insert(any(AgentToolPermissionDO.class))).thenThrow(new DuplicateKeyException(
                "ERROR: duplicate key value violates unique constraint "
                        + "\"uk_agent_tool_permission_role_tool\""));

        assertThatThrownBy(() -> service.save(request(null, "user", "search_news", "DENY", 1)))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    void 校验_role三值_decision三值_通配仅限mcp冒号星() {
        assertThatThrownBy(() -> service.save(request(null, "system", "search_news", "DENY", 1)))
                .isInstanceOf(ClientException.class).hasMessageContaining("role");
        assertThatThrownBy(() -> service.save(request(null, "user", "search_news", "OBEY", 1)))
                .isInstanceOf(ClientException.class).hasMessageContaining("decision");
        // 非 mcp:* 的通配写法会静默永不匹配，必须拦下
        assertThatThrownBy(() -> service.save(request(null, "user", "search_*", "DENY", 1)))
                .isInstanceOf(ClientException.class).hasMessageContaining("mcp:*");
        assertThatThrownBy(() -> service.save(request(null, "user", " ", "DENY", 1)))
                .isInstanceOf(ClientException.class).hasMessageContaining("tool_id");
        verify(mapper, never()).insert(any(AgentToolPermissionDO.class));
    }

    @Test
    void 更新_按id改写并保留首建时间语义_冲突同样翻译() {
        when(mapper.selectById("id-1")).thenReturn(AgentToolPermissionDO.builder()
                .id("id-1").role("user").toolId("search_news").decision("ALLOW")
                .enabled(1).build());

        service.save(request("id-1", "guest", "mcp:*", "ASK", 0));

        ArgumentCaptor<AgentToolPermissionDO> row = ArgumentCaptor.forClass(AgentToolPermissionDO.class);
        verify(mapper).updateById(row.capture());
        assertThat(row.getValue().getRole()).isEqualTo("guest");
        assertThat(row.getValue().getToolId()).isEqualTo("mcp:*");
        assertThat(row.getValue().getDecision()).isEqualTo("ASK");
        assertThat(row.getValue().getEnabled()).isZero();

        when(mapper.updateById(any(AgentToolPermissionDO.class))).thenThrow(new DuplicateKeyException(
                "violates unique constraint \"uk_agent_tool_permission_role_tool\""));
        assertThatThrownBy(() -> service.save(request("id-1", "user", "search_news", "DENY", 1)))
                .isInstanceOf(ClientException.class).hasMessageContaining("已存在");
    }

    @Test
    void 更新_规则不存在报错() {
        when(mapper.selectById("id-404")).thenReturn(null);
        assertThatThrownBy(() -> service.save(request("id-404", "user", "search_news", "DENY", 1)))
                .isInstanceOf(ClientException.class).hasMessageContaining("不存在");
    }

    @Test
    void 更新_enabled未传沿用库内现值_不静默翻回启用() {
        when(mapper.selectById("id-2")).thenReturn(AgentToolPermissionDO.builder()
                .id("id-2").role("user").toolId("search_news").decision("ALLOW")
                .enabled(0).build());

        service.save(request("id-2", "user", "search_news", "DENY", null));

        ArgumentCaptor<AgentToolPermissionDO> row = ArgumentCaptor.forClass(AgentToolPermissionDO.class);
        verify(mapper).updateById(row.capture());
        // 原行 enabled=0（停用），部分更新未传 enabled 须沿用 0——不得静默翻回 1
        assertThat(row.getValue().getEnabled()).isZero();
        assertThat(row.getValue().getDecision()).isEqualTo("DENY");
    }

    @Test
    void 删除_删不到报错_删到放行() {
        when(mapper.deleteById("id-1")).thenReturn(1);
        service.remove("id-1");

        when(mapper.deleteById("id-404")).thenReturn(0);
        assertThatThrownBy(() -> service.remove("id-404"))
                .isInstanceOf(ClientException.class).hasMessageContaining("不存在");
    }

    @Test
    void 列表_按角色与工具id稳定排序() {
        when(mapper.selectList(any())).thenReturn(List.of(
                AgentToolPermissionDO.builder().role("user").toolId("search_news").build(),
                AgentToolPermissionDO.builder().role("admin").toolId("mcp:*").build(),
                AgentToolPermissionDO.builder().role("guest").toolId("query_key_dates").build(),
                AgentToolPermissionDO.builder().role("admin").toolId("search_exam_papers").build()));

        List<AgentToolPermissionDO> listed = service.list();
        assertThat(listed).extracting(AgentToolPermissionDO::getRole)
                .containsExactly("admin", "admin", "guest", "user");
        assertThat(listed.get(0).getToolId()).isEqualTo("mcp:*");
        assertThat(listed.get(1).getToolId()).isEqualTo("search_exam_papers");
    }
}

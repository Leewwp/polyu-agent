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

package com.nageoffer.ai.ragent.agent.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.agent.dto.AgentBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 本地回放验证（#486 验收 9，零成本不进 CI）：行构建纯函数对本地库既有含 tool 块消息
 * 全量回放 → 零异常，行数/decision 分布统计留痕（RAGENT_AUDIT_REPLAY=true 时运行）。
 * 数据源取 bootstrap application.yaml + application-local.yaml 叠加（本地栈 PG，凭据只入本地件）。
 */
@EnabledIfEnvironmentVariable(named = "RAGENT_AUDIT_REPLAY", matches = "true")
class AgentToolCallAuditReplayTest {

    @Test
    void replaysAllToolBlockMessagesWithZeroExceptions() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(localDataSource());
        List<Map<String, Object>> messages = jdbc.queryForList(
                "SELECT id, conversation_id, user_id, blocks FROM t_agent_message "
                        + "WHERE role = 'assistant' AND jsonb_typeof(blocks) = 'array' ORDER BY id");

        ObjectMapper mapper = new ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        long toolBlockCount = 0;
        long rowCount = 0;
        Map<String, Long> decisionDistribution = new TreeMap<>();
        Map<String, Long> statusDistribution = new TreeMap<>();
        int replayedMessages = 0;
        for (Map<String, Object> message : messages) {
            List<AgentBlock> blocks = mapper.readValue(
                    String.valueOf(message.get("blocks")),
                    mapper.getTypeFactory().constructCollectionType(List.class, AgentBlock.class));
            long toolBlocks = blocks.stream()
                    .filter(block -> block != null && AgentBlock.KIND_TOOL.equals(block.getKind()))
                    .count();
            if (toolBlocks == 0) {
                continue;
            }
            replayedMessages++;
            List<AgentToolCallAuditRow> rows = AgentToolCallAuditRowFactory.rowsOf(
                    new AgentToolCallAuditRowFactory.RunMetadata(
                            String.valueOf(message.get("conversation_id")),
                            String.valueOf(message.get("user_id")),
                            null, String.valueOf(message.get("id"))),
                    blocks, id -> null);
            // 每条消息：行数恰等于 tool 块数；同输入重算行集相等（幂等）
            if (rows.size() != toolBlocks) {
                throw new AssertionError("行数与 tool 块数不符, message=" + message.get("id"));
            }
            if (!rows.equals(AgentToolCallAuditRowFactory.rowsOf(
                    new AgentToolCallAuditRowFactory.RunMetadata(
                            String.valueOf(message.get("conversation_id")),
                            String.valueOf(message.get("user_id")),
                            null, String.valueOf(message.get("id"))),
                    blocks, id -> null))) {
                throw new AssertionError("重算行集不等, message=" + message.get("id"));
            }
            toolBlockCount += toolBlocks;
            rowCount += rows.size();
            rows.forEach(row -> {
                decisionDistribution.merge(row.decision(), 1L, Long::sum);
                statusDistribution.merge(row.status(), 1L, Long::sum);
            });
        }

        String decisions = decisionDistribution.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(", "));
        String statuses = statusDistribution.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(", "));
        System.out.println("[audit-replay] 含 tool 块消息=" + replayedMessages
                + ", 审计行=" + rowCount + ", tool 块总数=" + toolBlockCount
                + ", decision 分布={" + decisions + "}, status 分布={" + statuses + "}");
    }

    private static javax.sql.DataSource localDataSource() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        var loader = new YamlPropertySourceLoader();
        // 先装 application.yaml（低位），再把 application-local.yaml 插到最前（本地栈端口/凭据覆盖生效）
        for (String name : List.of("application.yaml", "application-local.yaml")) {
            Path path = root().resolve("bootstrap/src/main/resources/" + name);
            if (!java.nio.file.Files.exists(path)) {
                continue;
            }
            for (var source : loader.load(name, new FileSystemResource(path))) {
                if (name.equals("application-local.yaml")) {
                    environment.getPropertySources().addFirst(source);
                } else {
                    environment.getPropertySources().addLast(source);
                }
            }
        }
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.postgresql.Driver");
        source.setUrl(environment.getRequiredProperty("spring.datasource.url"));
        source.setUsername(environment.getRequiredProperty("spring.datasource.username"));
        source.setPassword(environment.getRequiredProperty("spring.datasource.password"));
        return source;
    }

    private static Path root() {
        // agent 模块测试工作目录=agent/，仓根在上一级
        return Path.of("").toAbsolutePath().getParent();
    }
}

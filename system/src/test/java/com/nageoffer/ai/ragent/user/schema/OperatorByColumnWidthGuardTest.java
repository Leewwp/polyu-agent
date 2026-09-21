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

package com.nageoffer.ai.ragent.user.schema;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 操作人列宽度护栏（#100）：所有承载 username 的 *_by 列不得窄于 t_user.username
 * <p>
 * 260909 把 t_user.username 放宽到 255（注册用户 username=email），但 11 张表的
 * created_by/updated_by/create_by/update_by 漏网仍为 20，长邮箱账号进管理面即
 * value too long 整单失败。本护栏防同族再漏：新表/新列再写出窄操作人列直接红。
 * <p>
 * 解析对象只有 schema_pg.sql（新库现态权威，纪律要求与 upgrades 终态同步）；
 * 历史 upgrades 里的旧宽度声明是既成事实不回改，不参与断言。
 * t_agent_memory.superseded_by 语义是「取代者 ID」非 username，不在四列名单内。
 */
class OperatorByColumnWidthGuardTest {

    private static final List<String> OPERATOR_COLUMNS = List.of("created_by", "updated_by", "create_by", "update_by");

    private static final Pattern CREATE_TABLE = Pattern.compile("^CREATE TABLE (\\w+) \\($");

    private static final Pattern COLUMN_VARCHAR = Pattern.compile("^\\s{4}(\\w+)\\s+VARCHAR\\((\\d+)\\)");

    @Test
    void operatorByColumnsAreAtLeastAsWideAsUsername() throws IOException {
        Map<String, Map<String, Integer>> schema = parseSchema(locateSchemaFile());

        Map<String, Integer> userColumns = schema.get("t_user");
        assertThat(userColumns).as("schema_pg.sql 里应能解析到 t_user").isNotNull();
        Integer usernameWidth = userColumns.get("username");
        assertThat(usernameWidth).as("t_user.username 应是 VARCHAR").isNotNull();

        Map<String, Integer> violations = new LinkedHashMap<>();
        int operatorColumnCount = 0;
        for (Map.Entry<String, Map<String, Integer>> table : schema.entrySet()) {
            for (Map.Entry<String, Integer> column : table.getValue().entrySet()) {
                if (!OPERATOR_COLUMNS.contains(column.getKey())) {
                    continue;
                }
                operatorColumnCount++;
                if (column.getValue() < usernameWidth) {
                    violations.put(table.getKey() + "." + column.getKey(), column.getValue());
                }
            }
        }
        // 解析器失配（比如列定义改成别的写法）时不许静默变成空断言
        assertThat(operatorColumnCount)
                .as("操作人列族应至少 22 列（#100 放宽后的存量），实际解析到 %d 列，疑似解析失配", operatorColumnCount)
                .isGreaterThanOrEqualTo(22);
        assertThat(violations)
                .as("以下操作人列窄于 t_user.username(%d)，须随 username 一并放宽：", usernameWidth)
                .isEmpty();
    }

    private static Path locateSchemaFile() {
        // mvn 从模块目录跑测试，仓库根在上一级；从仓库根直跑（个别 IDE）时两级路径都试
        List<Path> candidates = List.of(
                Path.of("..", "resources", "database", "schema_pg.sql"),
                Path.of("resources", "database", "schema_pg.sql"));
        return candidates.stream()
                .filter(Files::exists)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("找不到 resources/database/schema_pg.sql，候选="
                        + candidates));
    }

    private static Map<String, Map<String, Integer>> parseSchema(Path schemaFile) throws IOException {
        Map<String, Map<String, Integer>> schema = new LinkedHashMap<>();
        String currentTable = null;
        for (String line : Files.readAllLines(schemaFile)) {
            if (currentTable == null) {
                Matcher tableStart = CREATE_TABLE.matcher(line);
                if (tableStart.matches()) {
                    currentTable = tableStart.group(1);
                    schema.put(currentTable, new LinkedHashMap<>());
                }
                continue;
            }
            if (line.startsWith(")")) {
                currentTable = null;
                continue;
            }
            Matcher column = COLUMN_VARCHAR.matcher(line);
            if (column.find()) {
                schema.get(currentTable).put(column.group(1), Integer.parseInt(column.group(2)));
            }
        }
        return schema;
    }
}

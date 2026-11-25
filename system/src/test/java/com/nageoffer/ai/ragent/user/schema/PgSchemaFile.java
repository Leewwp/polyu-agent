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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 现行 schema 文件（resources/database/schema_pg.sql）的测试侧只读解析：表名 → 列名（声明序）。
 * 只回答「现行 schema 有哪些表、每张表有哪些列」，供守卫类测试与可选真库测试共用；
 * 列类型、约束与索引不在解析范围。schema 文件的定位与仓库根目录的推算也只在这里。
 *
 * <p>调用方须对解析结果带数量下限：解析失配（比如建表换了写法）时下限先红，
 * 不让断言落到空集合上空过。
 */
public final class PgSchemaFile {

    private static final Pattern CREATE_TABLE = Pattern.compile("^CREATE TABLE (\\w+) \\(\\s*$");

    private static final Pattern COLUMN = Pattern.compile("^\\s+(\\w+)\\s+\\w+");

    /**
     * 表级约束行的起始关键字：与列定义同样缩进，按首词排除
     */
    private static final Set<String> TABLE_CONSTRAINT_KEYWORDS =
            Set.of("CONSTRAINT", "PRIMARY", "UNIQUE", "FOREIGN", "CHECK", "EXCLUDE", "LIKE");

    private PgSchemaFile() {
    }

    /**
     * 定位 schema 文件：mvn 从模块目录跑测试，仓库根在上一级；从仓库根直跑（个别 IDE）时两级路径都试
     */
    public static Path locate() {
        List<Path> candidates = List.of(
                Path.of("..", "resources", "database", "schema_pg.sql"),
                Path.of("resources", "database", "schema_pg.sql"));
        return candidates.stream()
                .filter(Files::exists)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("找不到 resources/database/schema_pg.sql，候选="
                        + candidates));
    }

    /**
     * @return 仓库根目录：schema 文件固定在仓库根下的 resources/database 里
     */
    public static Path repoRoot() {
        Path schemaFile = locate().toAbsolutePath().normalize();
        return schemaFile.getParent().getParent().getParent();
    }

    /**
     * @return 表名 → 列名（声明序），表按文件内出现顺序
     */
    public static Map<String, List<String>> parseTables(Path schemaFile) throws IOException {
        Map<String, List<String>> schema = new LinkedHashMap<>();
        String currentTable = null;
        for (String line : Files.readAllLines(schemaFile)) {
            if (currentTable == null) {
                Matcher tableStart = CREATE_TABLE.matcher(line);
                if (tableStart.matches()) {
                    currentTable = tableStart.group(1);
                    schema.put(currentTable, new ArrayList<>());
                }
                continue;
            }
            if (line.startsWith(")")) {
                currentTable = null;
                continue;
            }
            Matcher column = COLUMN.matcher(line);
            if (column.find() && !TABLE_CONSTRAINT_KEYWORDS.contains(column.group(1))) {
                schema.get(currentTable).add(column.group(1));
            }
        }
        return schema;
    }
}

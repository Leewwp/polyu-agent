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

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 表引用守卫的检测逻辑自测（#550）：输入是测试里构造的源码与表集合，不读写真实文件。
 * 伪造表名在测试内部拼接出来，验证「引用了不存在的表 → 报出文件、行号与表名」，
 * 防止守卫静默失效。
 */
class TableReferenceScannerTest {

    private static final Set<String> SCHEMA = Set.of("t_user", "t_message");

    @Test
    void plantedReferenceToNonExistentTableFails() {
        String plantedTable = "t_" + "planted_no_such_table";
        String source = ""
                + "class UserDao {\n"
                + "    void purge(Long userId) {\n"
                + "        jdbcTemplate.update(\"DELETE FROM " + plantedTable + " WHERE user_id = ?\", userId);\n"
                + "    }\n"
                + "}\n";

        TableReferenceScanner.Report report = TableReferenceScanner.scan(
                SCHEMA, List.of(new TableReferenceScanner.SourceFile("UserDao.java", source)));

        assertThat(report.violations())
                .containsExactly(new TableReferenceScanner.Violation(
                        "UserDao.java", 3, TableReferenceScanner.Kind.UNKNOWN_TABLE, plantedTable));
    }

    @Test
    void referenceToKnownTablePasses() {
        TableReferenceScanner.Report report = scan(""
                + "class UserDao {\n"
                + "    void purge(Long userId) {\n"
                + "        jdbcTemplate.update(\"DELETE FROM t_user WHERE id = ?\", userId);\n"
                + "    }\n"
                + "}\n");

        assertThat(report.violations()).isEmpty();
    }

    @Test
    void referenceMatchingTableNameIgnoringCasePasses() {
        // PostgreSQL 对不带引号的标识符不区分大小写
        TableReferenceScanner.Report report = scan("String sql = \"DELETE FROM T_USER WHERE id = ?\";\n");

        assertThat(report.violations()).isEmpty();
    }

    @Test
    void bareTablePrefixInsideStringLiteralFails() {
        // 按前缀拼出来的表名守卫确认不了，不许静默放过，须显式豁免
        TableReferenceScanner.Report report = scan("String table = \"t_agent_\" + kind;\n");

        assertThat(report.violations())
                .containsExactly(new TableReferenceScanner.Violation(
                        "UserDao.java", 1, TableReferenceScanner.Kind.UNKNOWN_TABLE, "t_agent_"));
    }

    @Test
    void countsEveryTableMentionInsideStringLiterals() {
        TableReferenceScanner.Report report = scan(""
                + "String BY_ID = \"SELECT * FROM t_user WHERE id = ?\";\n"
                + "String BY_USER = \"SELECT m.* FROM t_message m JOIN t_user u ON u.id = m.user_id\";\n");

        assertThat(report.literalTableMentions()).isEqualTo(3);
    }

    @Test
    void tableMentionInsideTextBlockIsDetected() {
        String plantedTable = "t_" + "planted_no_such_table";
        // 文本块里先是奇数个内嵌引号（SQL 单引号串里的一个双引号），后面还有转义引号：
        // 只会按单行字符串读的词法会在内嵌引号处提前收尾，后面的内容全落到代码态
        TableReferenceScanner.Report report = scan(""
                + "class UserDao {\n"
                + "    String sql = \"\"\"\n"
                + "            SELECT count(*)\n"
                + "            FROM t_user u\n"
                + "            WHERE u.note = 'say \"hi'\n"
                + "              AND u.id IN (SELECT id FROM " + plantedTable + ")\n"
                + "              AND u.name != \\\"t_message\\\"\n"
                + "            \"\"\";\n"
                + "}\n");

        assertThat(report.violations())
                .containsExactly(new TableReferenceScanner.Violation(
                        "UserDao.java", 6, TableReferenceScanner.Kind.UNKNOWN_TABLE, plantedTable));
        assertThat(report.literalTableMentions()).isEqualTo(3);
    }

    private static TableReferenceScanner.Report scan(String source) {
        return TableReferenceScanner.scan(
                SCHEMA, List.of(new TableReferenceScanner.SourceFile("UserDao.java", source)));
    }
}

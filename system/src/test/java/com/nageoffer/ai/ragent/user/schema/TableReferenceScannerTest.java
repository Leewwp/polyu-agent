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
import static org.assertj.core.api.Assertions.tuple;

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

    @Test
    void tableMentionInsideLineCommentIsIgnored() {
        String plantedTable = "t_" + "planted_no_such_table";
        // 行注释里有一个历史表名和一个孤引号：不先识别注释，孤引号会把下一行的 SQL 吃掉
        TableReferenceScanner.Report report = scan(""
                + "// 旧表 t_answer_share 的语句已删，这里的 \" 只作说明\n"
                + "String sql = \"DELETE FROM " + plantedTable + " WHERE id = ?\";\n");

        assertThat(report.violations())
                .containsExactly(new TableReferenceScanner.Violation(
                        "UserDao.java", 2, TableReferenceScanner.Kind.UNKNOWN_TABLE, plantedTable));
        assertThat(report.literalTableMentions()).isEqualTo(1);
    }

    @Test
    void tableMentionInsideBlockCommentIsIgnored() {
        String plantedTable = "t_" + "planted_no_such_table";
        // Javadoc 里带着引用了历史表名的示例 SQL（带引号），注释内容一律不参与
        TableReferenceScanner.Report report = scan(""
                + "/**\n"
                + " * 旧表 t_answer_share 已并入 t_user，旧语句形如 \"DELETE FROM t_answer_share\"。\n"
                + " */\n"
                + "String sql = \"DELETE FROM " + plantedTable + " WHERE id = ?\";\n");

        assertThat(report.violations())
                .containsExactly(new TableReferenceScanner.Violation(
                        "UserDao.java", 4, TableReferenceScanner.Kind.UNKNOWN_TABLE, plantedTable));
        assertThat(report.literalTableMentions()).isEqualTo(1);
    }

    @Test
    void doubleSlashInsideStringLiteralDoesNotStartComment() {
        String plantedTable = "t_" + "planted_no_such_table";
        // URL 里的 // 不是注释起点，同一行后面的 SQL 必须照常扫描
        TableReferenceScanner.Report report = scan(""
                + "String url = \"https://example.test//not-a-comment\";\n"
                + "String sql = \"DELETE FROM " + plantedTable + " WHERE id = ?\";\n");

        assertThat(report.violations())
                .containsExactly(new TableReferenceScanner.Violation(
                        "UserDao.java", 2, TableReferenceScanner.Kind.UNKNOWN_TABLE, plantedTable));
    }

    @Test
    void doubleQuoteInsideCharLiteralDoesNotOpenString() {
        String plantedTable = "t_" + "planted_no_such_table";
        TableReferenceScanner.Report report = scan(""
                + "char quote = '\"';\n"
                + "char backslash = '\\\\';\n"
                + "char single = '\\'';\n"
                + "String sql = \"DELETE FROM " + plantedTable + " WHERE id = ?\";\n");

        assertThat(report.violations())
                .containsExactly(new TableReferenceScanner.Violation(
                        "UserDao.java", 4, TableReferenceScanner.Kind.UNKNOWN_TABLE, plantedTable));
        assertThat(report.literalTableMentions()).isEqualTo(1);
    }

    @Test
    void explicitTableNameIsCheckedAgainstSchema() {
        String plantedTable = "t_" + "planted_no_such_table";
        TableReferenceScanner.Report report = scan(""
                + "@TableName(\"" + plantedTable + "\")\n"
                + "class PlantedDO {\n"
                + "}\n"
                + "@TableName(value = \"t_user\", autoResultMap = true)\n"
                + "class UserDO {\n"
                + "}\n");

        assertThat(report.violations())
                .containsExactly(new TableReferenceScanner.Violation(
                        "UserDao.java", 1, TableReferenceScanner.Kind.UNKNOWN_TABLE, plantedTable));
        assertThat(report.tableNameAnnotations()).isEqualTo(2);
        // @TableName 的实参单独计数，不与 SQL 字符串里的表名出现次数混在一起
        assertThat(report.literalTableMentions()).isZero();
    }

    @Test
    void tableNameMentionInsideCommentIsNotAnOccurrence() {
        TableReferenceScanner.Report report = scan(""
                + "/** 实体上写 {@code @TableName} 指定表名 */\n"
                + "@TableName(\"t_user\")\n"
                + "class UserDO {\n"
                + "}\n");

        assertThat(report.violations()).isEmpty();
        assertThat(report.tableNameAnnotations()).isEqualTo(1);
    }

    @Test
    void tableNameWithoutLiteralTableFails() {
        // 不写表名、靠命名策略推导的 @TableName 守卫看不到，必须报出来
        TableReferenceScanner.Report report = scan(""
                + "@TableName\n"
                + "class ShareDO {\n"
                + "}\n");

        assertThat(report.violations())
                .containsExactly(new TableReferenceScanner.Violation(
                        "UserDao.java", 1, TableReferenceScanner.Kind.TABLE_NAME_NOT_LITERAL, ""));
        assertThat(report.tableNameAnnotations()).isEqualTo(1);
    }

    @Test
    void tableNameTakenFromAnythingButALiteralFails() {
        TableReferenceScanner.Report report = TableReferenceScanner.scan(SCHEMA, List.of(
                new TableReferenceScanner.SourceFile("NoArgs.java", "@TableName()\nclass A {\n}\n"),
                new TableReferenceScanner.SourceFile("OtherArgument.java", "@TableName(autoResultMap = true)\nclass B {\n}\n"),
                new TableReferenceScanner.SourceFile("Constant.java", ""
                        + "class C {\n"
                        + "    static final String TABLE = \"t_user\";\n"
                        + "\n"
                        + "    @TableName(value = TABLE)\n"
                        + "    static final String UNUSED = \"\";\n"
                        + "}\n"),
                new TableReferenceScanner.SourceFile("MultiLine.java", ""
                        + "@TableName(\n"
                        + "        autoResultMap = true)\n"
                        + "class D {\n}\n"),
                new TableReferenceScanner.SourceFile("Qualified.java", ""
                        + "@com.baomidou.mybatisplus.annotation.TableName\n"
                        + "class E {\n}\n")));

        // 违规行报在注解所在行，而不是实参所在的后续行
        assertThat(report.violations())
                .extracting(TableReferenceScanner.Violation::file, TableReferenceScanner.Violation::line,
                        TableReferenceScanner.Violation::kind)
                .containsExactly(
                        tuple("NoArgs.java", 1, TableReferenceScanner.Kind.TABLE_NAME_NOT_LITERAL),
                        tuple("OtherArgument.java", 1, TableReferenceScanner.Kind.TABLE_NAME_NOT_LITERAL),
                        tuple("Constant.java", 4, TableReferenceScanner.Kind.TABLE_NAME_NOT_LITERAL),
                        tuple("MultiLine.java", 1, TableReferenceScanner.Kind.TABLE_NAME_NOT_LITERAL),
                        tuple("Qualified.java", 1, TableReferenceScanner.Kind.TABLE_NAME_NOT_LITERAL));
        assertThat(report.tableNameAnnotations()).isEqualTo(5);
    }

    @Test
    void explicitTableNameSyntaxVariantsPass() {
        TableReferenceScanner.Report report = TableReferenceScanner.scan(SCHEMA, List.of(
                new TableReferenceScanner.SourceFile("Positional.java", "@TableName(\"t_user\")\nclass A {\n}\n"),
                new TableReferenceScanner.SourceFile("NamedAfterOtherArgument.java", ""
                        + "@TableName(autoResultMap = true, value = \"t_user\")\nclass B {\n}\n"),
                // 非表名实参上的字符串不按表名核对
                new TableReferenceScanner.SourceFile("WithSchema.java", ""
                        + "@TableName(schema = \"t_not_the_table\", value = \"t_user\")\nclass C {\n}\n"),
                new TableReferenceScanner.SourceFile("MultiLine.java", ""
                        + "@TableName(\n"
                        + "        value = \"t_user\",\n"
                        + "        autoResultMap = true)\n"
                        + "class D {\n}\n"),
                new TableReferenceScanner.SourceFile("Qualified.java", ""
                        + "@com.baomidou.mybatisplus.annotation.TableName(\"t_user\")\nclass E {\n}\n"),
                new TableReferenceScanner.SourceFile("CommentedArgument.java", ""
                        + "@TableName(/* 表名 */ \"t_user\")\nclass F {\n}\n")));

        assertThat(report.violations()).isEmpty();
        assertThat(report.tableNameAnnotations()).isEqualTo(6);
        assertThat(report.literalTableMentions()).isZero();
    }

    private static TableReferenceScanner.Report scan(String source) {
        return TableReferenceScanner.scan(
                SCHEMA, List.of(new TableReferenceScanner.SourceFile("UserDao.java", source)));
    }
}

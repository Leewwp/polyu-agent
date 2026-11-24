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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 表引用守卫的检测逻辑自测（#550）：输入是测试里构造的源码与表集合，不读写真实文件。
 * 伪造表名在测试内部拼接出来，验证「引用了不存在的表 → 报出文件、行号与表名」，
 * 以及词法、{@code @TableName}、豁免清单的各类情形，防止守卫静默失效。
 */
class TableReferenceScannerTest {

    private static final Set<String> SCHEMA = Set.of("t_user", "t_message");

    /**
     * 伪造的不存在的表名
     */
    private static final String PLANTED_TABLE = "t_" + "planted_no_such_table";

    @Test
    void plantedReferenceToNonExistentTableFails() {
        assertPlantedOnly(3, ""
                + "class UserDao {\n"
                + "    void purge(Long userId) {\n"
                + "        jdbcTemplate.update(\"DELETE FROM " + PLANTED_TABLE + " WHERE user_id = ?\", userId);\n"
                + "    }\n"
                + "}\n");
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
        // 文本块里先是奇数个内嵌引号（SQL 单引号串里的一个双引号），后面还有转义引号：
        // 只会按单行字符串读的词法会在内嵌引号处提前收尾，后面的内容全落到代码态
        TableReferenceScanner.Report report = assertPlantedOnly(6, ""
                + "class UserDao {\n"
                + "    String sql = \"\"\"\n"
                + "            SELECT count(*)\n"
                + "            FROM t_user u\n"
                + "            WHERE u.note = 'say \"hi'\n"
                + "              AND u.id IN (SELECT id FROM " + PLANTED_TABLE + ")\n"
                + "              AND u.name != \\\"t_message\\\"\n"
                + "            \"\"\";\n"
                + "}\n");

        assertThat(report.literalTableMentions()).isEqualTo(3);
    }

    @Test
    void tableMentionInsideLineCommentIsIgnored() {
        // 行注释里有一个历史表名和一个孤引号：不先识别注释，孤引号会把下一行的 SQL 吃掉
        TableReferenceScanner.Report report = assertPlantedOnly(2, ""
                + "// 旧表 t_answer_share 的语句已删，这里的 \" 只作说明\n"
                + "String sql = \"DELETE FROM " + PLANTED_TABLE + " WHERE id = ?\";\n");

        assertThat(report.literalTableMentions()).isEqualTo(1);
    }

    @Test
    void tableMentionInsideBlockCommentIsIgnored() {
        // Javadoc 里带着引用了历史表名的示例 SQL（带引号），注释内容一律不参与
        TableReferenceScanner.Report report = assertPlantedOnly(4, ""
                + "/**\n"
                + " * 旧表 t_answer_share 已并入 t_user，旧语句形如 \"DELETE FROM t_answer_share\"。\n"
                + " */\n"
                + "String sql = \"DELETE FROM " + PLANTED_TABLE + " WHERE id = ?\";\n");

        assertThat(report.literalTableMentions()).isEqualTo(1);
    }

    @Test
    void doubleSlashInsideStringLiteralDoesNotStartComment() {
        // URL 里的 // 不是注释起点，同一行后面的 SQL 必须照常扫描
        assertPlantedOnly(2, ""
                + "String url = \"https://example.test//not-a-comment\";\n"
                + "String sql = \"DELETE FROM " + PLANTED_TABLE + " WHERE id = ?\";\n");
    }

    @Test
    void doubleQuoteInsideCharLiteralDoesNotOpenString() {
        TableReferenceScanner.Report report = assertPlantedOnly(4, ""
                + "char quote = '\"';\n"
                + "char backslash = '\\\\';\n"
                + "char single = '\\'';\n"
                + "String sql = \"DELETE FROM " + PLANTED_TABLE + " WHERE id = ?\";\n");

        assertThat(report.literalTableMentions()).isEqualTo(1);
    }

    @Test
    void explicitTableNameIsCheckedAgainstSchema() {
        TableReferenceScanner.Report report = assertPlantedOnly(1, ""
                + "@TableName(\"" + PLANTED_TABLE + "\")\n"
                + "class PlantedDO {\n"
                + "}\n"
                + "@TableName(value = \"t_user\", autoResultMap = true)\n"
                + "class UserDO {\n"
                + "}\n");

        assertThat(report.tableNameAnnotations()).isEqualTo(2);
        // @TableName 的实参单独计数，不与 SQL 字符串里的表名出现次数混在一起
        assertThat(report.literalTableMentions()).isZero();
    }

    @Test
    void explicitTableNameWithoutTablePrefixIsCheckedAgainstSchema() {
        // 表名实参上的字面量整个就是表名：现行 schema 的表全部带表前缀，不带前缀或前缀写错的表名同样报出来
        TableReferenceScanner.Report report = TableReferenceScanner.scan(SCHEMA, Map.of(), List.of(
                new TableReferenceScanner.SourceFile("NoPrefix.java", "@TableName(\"user\")\nclass A {\n}\n"),
                new TableReferenceScanner.SourceFile("MisspeltPrefix.java", ""
                        + "@TableName(value = \"tse_user\", autoResultMap = true)\nclass B {\n}\n")));

        assertThat(report.violations())
                .containsExactly(
                        new TableReferenceScanner.Violation(
                                "NoPrefix.java", 1, TableReferenceScanner.Kind.UNKNOWN_TABLE, "user"),
                        new TableReferenceScanner.Violation(
                                "MisspeltPrefix.java", 1, TableReferenceScanner.Kind.UNKNOWN_TABLE, "tse_user"));
        assertThat(report.tableNameAnnotations()).isEqualTo(2);
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
        TableReferenceScanner.Report report = TableReferenceScanner.scan(SCHEMA, Map.of(), List.of(
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
        TableReferenceScanner.Report report = TableReferenceScanner.scan(SCHEMA, Map.of(), List.of(
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
                        + "@TableName(/* 表名 */ \"t_user\")\nclass F {\n}\n"),
                // 表名实参上的字面量按表名核对时同样不区分大小写
                new TableReferenceScanner.SourceFile("UpperCase.java", ""
                        + "@TableName(\"T_USER\")\nclass G {\n}\n")));

        assertThat(report.violations()).isEmpty();
        assertThat(report.tableNameAnnotations()).isEqualTo(7);
        assertThat(report.literalTableMentions()).isZero();
    }

    @Test
    void exemptedTableReferencePasses() {
        TableReferenceScanner.Report report = scan(
                Map.of(PLANTED_TABLE, "由外部系统维护，现行 schema 文件里没有它"),
                "String sql = \"DELETE FROM " + PLANTED_TABLE + " WHERE id = ?\";\n");

        assertThat(report.violations()).isEmpty();
        assertThat(report.literalTableMentions()).isEqualTo(1);
    }

    @Test
    void exemptionWithoutReasonIsNotHonoured() {
        // 没写理由的豁免等于没有豁免
        assertPlantedOnly(1, Map.of(PLANTED_TABLE, "  "),
                "String sql = \"DELETE FROM " + PLANTED_TABLE + " WHERE id = ?\";\n");
    }

    @Test
    void exemptionCoversTableNameAnnotationAndIgnoresCase() {
        TableReferenceScanner.Report report = scan(Map.of(PLANTED_TABLE.toUpperCase(Locale.ROOT), "由外部系统维护"),
                "@TableName(\"" + PLANTED_TABLE + "\")\nclass ArchiveDO {\n}\n");

        assertThat(report.violations()).isEmpty();
        assertThat(report.tableNameAnnotations()).isEqualTo(1);
    }

    @Test
    void tablePrefixedJavaIdentifierOutsideStringLiteralIsIgnored() {
        // 字段名、形参名带表前缀只是 Java 标识符，不是表引用
        TableReferenceScanner.Report report = scan(""
                + "class UserDao {\n"
                + "    Object " + PLANTED_TABLE + ";\n"
                + "    Object t_user;\n"
                + "    void save(Object " + PLANTED_TABLE + ") {\n"
                + "        this." + PLANTED_TABLE + " = " + PLANTED_TABLE + ";\n"
                + "        log.debug(\"{}\", " + PLANTED_TABLE + ");\n"
                + "    }\n"
                + "}\n");

        assertThat(report.violations()).isEmpty();
        assertThat(report.literalTableMentions()).isZero();
    }

    @Test
    void unterminatedLiteralFailsInsteadOfSkippingTheRest() {
        // 词法失配时必须报错，不能把文件后半段悄悄跳过
        String source = ""
                + "String broken = \"SELECT * FROM t_user;\n"
                + "String sql = \"DELETE FROM t_message\";\n";

        assertThatThrownBy(() -> scan(source))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UserDao.java:1")
                .hasMessageContaining("没有收尾定界符");
    }

    private static TableReferenceScanner.Report scan(String source) {
        return scan(Map.of(), source);
    }

    private static TableReferenceScanner.Report scan(Map<String, String> exemptions, String source) {
        return TableReferenceScanner.scan(SCHEMA, exemptions,
                List.of(new TableReferenceScanner.SourceFile("UserDao.java", source)));
    }

    /**
     * 扫描 UserDao.java：违规恰好一处，即第 line 行引用了伪造表
     *
     * @return 扫描报告，供调用方继续断言出现次数
     */
    private static TableReferenceScanner.Report assertPlantedOnly(int line, String source) {
        return assertPlantedOnly(line, Map.of(), source);
    }

    private static TableReferenceScanner.Report assertPlantedOnly(int line, Map<String, String> exemptions,
            String source) {
        TableReferenceScanner.Report report = scan(exemptions, source);
        assertThat(report.violations())
                .containsExactly(new TableReferenceScanner.Violation(
                        "UserDao.java", line, TableReferenceScanner.Kind.UNKNOWN_TABLE, PLANTED_TABLE));
        return report;
    }
}

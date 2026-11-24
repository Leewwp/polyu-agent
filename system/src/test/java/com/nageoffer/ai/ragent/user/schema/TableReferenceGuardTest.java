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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 表引用完整性守卫（#550）：主代码引用的每一个表名都必须存在于现行 schema。
 * <p>
 * 以 resources/database/schema_pg.sql 解析出的表集合为准，依次断言：
 * <ol>
 *   <li>表集合不少于下限——解析失配时先红，不让后面的断言落到空集合上空过；</li>
 *   <li>扫描结果不少于下限（@TableName 处数、字符串里的表名次数）——文件遍历或词法失配时同样先红；</li>
 *   <li>主代码没有任何表引用违规。</li>
 * </ol>
 * 违规有两类：引用了表集合与豁免清单之外的表名；{@code @TableName} 没有显式写字面量表名
 * （靠命名策略或常量推导的表名守卫看不到）。
 * <p>
 * <b>扫描口径</b>：各模块 src/main/java 目录下的 .java 源文件，逐字符按 Java 词法处理
 * （字符串字面量、文本块、字符字面量、注释各自成态）。
 * <ul>
 *   <li>字符串字面量与文本块里带表前缀 {@code t_} 的标识符。不解析 SQL，也不判断字符串是不是 SQL：
 *       JdbcTemplate 的 SQL、MyBatis 注解 SQL、拆成多段拼接的 SQL 片段、以字符串形式传参的表名都在其中；</li>
 *   <li>{@code @TableName} 的表名实参（位置实参或 {@code value}）上的字符串字面量：整个字面量就是表名，
 *       带不带表前缀都对照表集合；</li>
 *   <li>只切出前缀、切不出完整名字的记号（如 {@code t_}、{@code t_agent_}）同样算违规：
 *       按前缀拼出来的表名守卫确认不了；</li>
 *   <li>表名比较不区分大小写（PostgreSQL 对不带引号的标识符不区分大小写）。</li>
 * </ul>
 * 不参与扫描：注释（行注释、块注释、Javadoc）、测试代码、resources/database/upgrades 下的历史迁移脚本、
 * YAML 与 properties 等非 Java 文件。遇到无法收尾的字面量或注释时直接报错并指出文件与行号，
 * 不跳过文件后半段。
 * <p>
 * <b>已知边界</b>：
 * <ul>
 *   <li>动态拼接的表名不可见：字面量里没有完整 {@code t_} 记号的拼接（前缀与名字分别来自常量、配置或运行时）扫不到；</li>
 *   <li>字符串字面量里不带表前缀的标识符不在范围内，系统目录（{@code information_schema.*}、{@code pg_catalog.*}）
 *       等不受检查；{@code @TableName} 表名实参上的字面量不受此限；</li>
 *   <li>在 Java 之外配置的表名（YAML、SQL 脚本）不扫描；</li>
 *   <li>只核对表名，不核对列、索引与约束。</li>
 * </ul>
 * 检测逻辑是 {@link TableReferenceScanner}，喂内存里构造的源码做自测，见 {@link TableReferenceScannerTest}。
 */
class TableReferenceGuardTest {

    private static final int MIN_SCHEMA_TABLES = 56;

    private static final int MIN_TABLE_NAME_ANNOTATIONS = 51;

    private static final int MIN_LITERAL_TABLE_MENTIONS = 60;

    /**
     * 豁免清单：表名 → 理由。只有现行 schema 之外、又确需保留的主代码表引用才登记在这里，
     * 每条必须写明理由（理由为空不生效）。清单之外的未知表名一律失败；引用删掉后对应条目也要删。
     */
    private static final Map<String, String> EXEMPTIONS = Map.of();

    @Test
    void mainCodeReferencesOnlyTablesOfTheCurrentSchema() throws IOException {
        Set<String> schemaTables = PgSchemaFile.parseTables(PgSchemaFile.locate()).keySet();
        // 解析器失配（比如建表换了写法）时不许静默变成空断言
        assertThat(schemaTables)
                .as("schema_pg.sql 至少应解析出 %d 张表，实际 %d 张，疑似解析失配",
                        MIN_SCHEMA_TABLES, schemaTables.size())
                .hasSizeGreaterThanOrEqualTo(MIN_SCHEMA_TABLES);

        List<TableReferenceScanner.SourceFile> sources = mainJavaSources();
        TableReferenceScanner.Report report = TableReferenceScanner.scan(schemaTables, EXEMPTIONS, sources);

        // 文件遍历或词法失配时不许扫到空集合空过
        assertThat(report.tableNameAnnotations())
                .as("主代码里 @TableName 至少应扫到 %d 处，实际 %d 处（扫描 %d 个文件），疑似文件遍历或词法失配",
                        MIN_TABLE_NAME_ANNOTATIONS, report.tableNameAnnotations(), sources.size())
                .isGreaterThanOrEqualTo(MIN_TABLE_NAME_ANNOTATIONS);
        assertThat(report.literalTableMentions())
                .as("字符串字面量与文本块里至少应扫到 %d 处表名，实际 %d 处（扫描 %d 个文件），疑似文件遍历或词法失配",
                        MIN_LITERAL_TABLE_MENTIONS, report.literalTableMentions(), sources.size())
                .isGreaterThanOrEqualTo(MIN_LITERAL_TABLE_MENTIONS);

        assertThat(report.violations())
                .as("主代码的表引用有违规（文件:行 说明：表名）。改表名或删掉这条引用；"
                        + "确需保留的表引用登记到 EXEMPTIONS 并写明理由：%n%s", report.violations())
                .isEmpty();
    }

    /**
     * 各模块 src/main/java 目录下的全部 .java 源文件，路径标签为仓库相对路径
     */
    private static List<TableReferenceScanner.SourceFile> mainJavaSources() throws IOException {
        Path repoRoot = PgSchemaFile.locate().toAbsolutePath().normalize().getParent().getParent().getParent();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> modules = Files.list(repoRoot)) {
            List<Path> mainJavaDirs = modules.map(module -> module.resolve("src").resolve("main").resolve("java"))
                    .filter(Files::isDirectory)
                    .sorted()
                    .toList();
            for (Path mainJava : mainJavaDirs) {
                try (Stream<Path> javaFiles = Files.walk(mainJava)) {
                    javaFiles.filter(path -> path.toString().endsWith(".java")).sorted().forEach(files::add);
                }
            }
        }
        List<TableReferenceScanner.SourceFile> sources = new ArrayList<>(files.size());
        for (Path file : files) {
            sources.add(new TableReferenceScanner.SourceFile(
                    repoRoot.relativize(file).toString().replace('\\', '/'), Files.readString(file)));
        }
        return sources;
    }
}

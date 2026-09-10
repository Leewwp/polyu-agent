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

package com.nageoffer.ai.ragent.core.parser;

import com.nageoffer.ai.ragent.core.parser.model.CodeBlock;
import com.nageoffer.ai.ragent.core.parser.model.HeadingBlock;
import com.nageoffer.ai.ragent.core.parser.model.ListBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;
import com.nageoffer.ai.ragent.core.parser.model.ParagraphBlock;
import com.nageoffer.ai.ragent.core.parser.model.TableBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MarkdownDocumentParser 渲染冒烟（批次三 #32 commonmark 0.22→0.30 针对性回归）：
 * 自建最小 markdown fixture 覆盖标题/段落（行内代码与链接拼接）/有序无序列表/GFM 表格/
 * 围栏代码块五类 Block 产物，锁定升级后的 AST→Block 结构语义不漂移。
 */
@DisplayName("MarkdownDocumentParser 结构化解析（commonmark 0.30 冒烟）")
class MarkdownDocumentParserTest {

    private final MarkdownDocumentParser parser = new MarkdownDocumentParser();

    private ParsedDocument parse(String markdown) {
        return parser.parseStructured(markdown.getBytes(StandardCharsets.UTF_8), "text/markdown",
                Map.of("sourceFile", "fixture.md"));
    }

    private List<HeadingBlock> headings(ParsedDocument doc) {
        return doc.blocks().stream()
                .filter(b -> b instanceof HeadingBlock)
                .map(b -> (HeadingBlock) b)
                .collect(Collectors.toList());
    }

    private List<ParagraphBlock> paragraphs(ParsedDocument doc) {
        return doc.blocks().stream()
                .filter(b -> b instanceof ParagraphBlock)
                .map(b -> (ParagraphBlock) b)
                .collect(Collectors.toList());
    }

    private List<ListBlock> lists(ParsedDocument doc) {
        return doc.blocks().stream()
                .filter(b -> b instanceof ListBlock)
                .map(b -> (ListBlock) b)
                .collect(Collectors.toList());
    }

    private List<TableBlock> tables(ParsedDocument doc) {
        return doc.blocks().stream()
                .filter(b -> b instanceof TableBlock)
                .map(b -> (TableBlock) b)
                .collect(Collectors.toList());
    }

    @Test
    @DisplayName("标题与段落：层级正确，行内代码与链接保留拼接形态")
    void headingAndParagraphWithInlines() {
        ParsedDocument doc = parse("""
                # 学生服务指南

                详情见 [学校官网](https://www.polyu.edu.hk)，或使用课程代码 `COMP101` 查询。

                ## 图书馆开放时间
                """);

        assertThat(headings(doc)).extracting(HeadingBlock::level, HeadingBlock::text)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, "学生服务指南"),
                        org.assertj.core.groups.Tuple.tuple(2, "图书馆开放时间"));
        assertThat(paragraphs(doc)).hasSize(1);
        assertThat(paragraphs(doc).get(0).text())
                .isEqualTo("详情见 [学校官网](https://www.polyu.edu.hk)，或使用课程代码 `COMP101` 查询。");
    }

    @Test
    @DisplayName("列表：有序与无序各自落 ListBlock，项内行内元素已拼接")
    void bulletAndOrderedLists() {
        ParsedDocument doc = parse("""
                - 首学期迎新
                - 选课与注册

                1. 提交申请
                2. 等待审核
                """);

        assertThat(lists(doc)).hasSize(2);
        assertThat(lists(doc).get(0).ordered()).isFalse();
        assertThat(lists(doc).get(0).items()).containsExactly("首学期迎新", "选课与注册");
        assertThat(lists(doc).get(1).ordered()).isTrue();
        assertThat(lists(doc).get(1).items()).containsExactly("提交申请", "等待审核");
    }

    @Test
    @DisplayName("GFM 表格：表头与数据行分列产出")
    void gfmTable() {
        ParsedDocument doc = parse("""
                | 校区 | 位置 | 开放时间 |
                | --- | --- | --- |
                | 红磡 | 主校园 | 08:30–22:00 |
                | 育才 | 西九龙 | 09:00–18:00 |
                """);

        assertThat(tables(doc)).hasSize(1);
        TableBlock table = tables(doc).get(0);
        assertThat(table.headers()).containsExactly("校区", "位置", "开放时间");
        assertThat(table.rows()).hasSize(2);
        assertThat(table.rows().get(0)).containsExactly("红磡", "主校园", "08:30–22:00");
        assertThat(table.rows().get(1)).containsExactly("育才", "西九龙", "09:00–18:00");
    }

    @Test
    @DisplayName("围栏代码块：info 语言与代码体分离")
    void fencedCodeBlock() {
        ParsedDocument doc = parse("""
                ```python
                print("hello")
                ```
                """);

        List<CodeBlock> codes = doc.blocks().stream()
                .filter(b -> b instanceof CodeBlock)
                .map(b -> (CodeBlock) b)
                .collect(Collectors.toList());
        assertThat(codes).hasSize(1);
        assertThat(codes.get(0).language()).isEqualTo("python");
        assertThat(codes.get(0).code()).isEqualTo("print(\"hello\")");
    }
}

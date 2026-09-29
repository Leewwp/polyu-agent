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

package com.nageoffer.ai.ragent.calendar.parse;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 校历页面 HTML 表格与可见文本抽取（#189 r3 回放的 Java 等价实现）。
 *
 * <p>语义对齐 replay.py 的极简 TableExtractor：取顶层 {@code <table>}（嵌套表的
 * 行/格归入顶层表）、行=tr、格=td/th 纯文本（空白折叠、NBSP 归一为普通空格）；
 * rowspan 不展开——页面 rowspan 前置格只在组首行出现，解析器按行末格/上下文
 * 组装（r3 fixture 前提，勿改成 Jsoup 自动补格）。主表=行数最多的表（每页一张
 * 年度主表；导航/装饰小表自然落选，仍由解析规则显式归类排除）。
 */
public final class CalendarHtmlTables {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private CalendarHtmlTables() {
    }

    /**
     * 顶层表列表：table → rows → cells（纯文本，空白折叠）
     */
    public static List<List<List<String>>> extractTables(String html) {
        Document doc = Jsoup.parse(html);
        List<List<List<String>>> tables = new ArrayList<>();
        for (Element table : doc.select("table")) {
            if (table.hasParent() && table.parents().stream().anyMatch(p -> "table".equals(p.tagName()))) {
                continue; // 嵌套表归入顶层表内容，不单列
            }
            List<List<String>> rows = new ArrayList<>();
            for (Element tr : table.select("tr")) {
                List<String> cells = new ArrayList<>();
                for (Element cell : tr.select("td,th")) {
                    cells.add(normalizeWhitespace(cell.text()));
                }
                rows.add(cells);
            }
            if (!rows.isEmpty()) {
                tables.add(rows);
            }
        }
        return tables;
    }

    /**
     * 主表（行数最多）；无表返回空列表
     */
    public static List<List<String>> mainRows(String html) {
        List<List<List<String>>> tables = extractTables(html);
        List<List<String>> best = List.of();
        for (List<List<String>> t : tables) {
            if (t.size() > best.size()) {
                best = t;
            }
        }
        return best;
    }

    /**
     * 页面可见文本（去 script/style 后全文空白折叠）——页面学年散文证据的检索面
     */
    public static String visibleText(String html) {
        Document doc = Jsoup.parse(html);
        doc.select("script,style").remove();
        return normalizeWhitespace(doc.body().text());
    }

    /**
     * NBSP 归一为普通空格 + 空白折叠 + 去首尾（与 Python 侧 \s+ 折叠口径一致）
     */
    static String normalizeWhitespace(String text) {
        return WHITESPACE.matcher(text.replace('\u00a0', ' ')).replaceAll(" ").strip();
    }
}

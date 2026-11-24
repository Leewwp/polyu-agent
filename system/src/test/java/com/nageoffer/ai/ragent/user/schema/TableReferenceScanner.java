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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 表引用检测的纯逻辑：输入是现行 schema 的表集合与主代码源文本，输出是违规清单。
 * 不读文件、不碰构建工具——取数由守卫测试负责，自测直接喂内存里的源码。
 * <p>
 * 识别不解析 SQL：按表前缀 {@code t_} 从字符串字面量里切出记号，记号不在表集合里即违规。
 */
public final class TableReferenceScanner {

    private TableReferenceScanner() {
    }

    /**
     * 违规类型
     */
    public enum Kind {
        /**
         * 字符串里引用了现行 schema 没有的表
         */
        UNKNOWN_TABLE("现行 schema 里不存在的表引用");

        private final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    /**
     * @param path 源文件路径（只用于报违规）
     * @param text 源文件全文
     */
    public record SourceFile(String path, String text) {
    }

    /**
     * @param file       源文件路径
     * @param line       行号（1 起）
     * @param kind       违规类型
     * @param identifier 被引用的表名记号
     */
    public record Violation(String file, int line, Kind kind, String identifier) {

        @Override
        public String toString() {
            return file + ":" + line + " " + kind.label + "：" + identifier;
        }
    }

    /**
     * @param violations           违规清单，按源文件顺序
     * @param literalTableMentions 字符串字面量与文本块里出现的表名记号次数
     */
    public record Report(List<Violation> violations, int literalTableMentions) {
    }

    public static Report scan(Set<String> schemaTables, List<SourceFile> sources) {
        Set<String> tables = lowerCase(schemaTables);
        List<Violation> violations = new ArrayList<>();
        int mentions = 0;
        for (SourceFile source : sources) {
            Scan scan = new Scan(source, tables);
            violations.addAll(scan.run());
            mentions += scan.literalTableMentions;
        }
        return new Report(List.copyOf(violations), mentions);
    }

    private static Set<String> lowerCase(Set<String> names) {
        Set<String> lowered = new java.util.HashSet<>();
        for (String name : names) {
            lowered.add(name.toLowerCase(Locale.ROOT));
        }
        return lowered;
    }

    /**
     * 单个源文件的扫描：逐字符走一遍，字符串字面量交给表名提取，其余跳过
     */
    private static final class Scan {

        private static final char QUOTE = '"';

        private final SourceFile source;
        private final String text;
        private final Set<String> tables;
        private final List<Violation> violations = new ArrayList<>();
        private int literalTableMentions;

        private Scan(SourceFile source, Set<String> tables) {
            this.source = source;
            this.text = source.text();
            this.tables = tables;
        }

        private List<Violation> run() {
            int i = 0;
            while (i < text.length()) {
                if (text.charAt(i) == QUOTE) {
                    i = stringLiteral(i);
                    continue;
                }
                i++;
            }
            return violations;
        }

        /**
         * 跳过一个字符串字面量，顺便提取里面的表名记号
         *
         * @return 收尾引号之后的下标
         */
        private int stringLiteral(int openingQuote) {
            int i = openingQuote + 1;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == QUOTE) {
                    collectTableTokens(openingQuote + 1, i);
                    return i + 1;
                }
                i++;
            }
            throw malformed(openingQuote);
        }

        /**
         * 在 [from, to) 里按表前缀切出记号并计数，不在表集合里的记号报违规
         */
        private void collectTableTokens(int from, int to) {
            for (int i = from; i < to; i++) {
                if (!isTablePrefixStart(from, to, i)) {
                    continue;
                }
                int end = i + 2;
                while (end < to && isIdentifierChar(text.charAt(end))) {
                    end++;
                }
                String token = text.substring(i, end);
                literalTableMentions++;
                if (!tables.contains(token.toLowerCase(Locale.ROOT))) {
                    violations.add(new Violation(source.path(), lineAt(i), Kind.UNKNOWN_TABLE, token));
                }
                i = end - 1;
            }
        }

        /**
         * 下标 i 处是不是表名记号的起点：{@code t_}，且前一个字符不是标识符字符
         */
        private boolean isTablePrefixStart(int from, int to, int i) {
            char c = text.charAt(i);
            if ((c != 't' && c != 'T') || i + 1 >= to || text.charAt(i + 1) != '_') {
                return false;
            }
            return i == from || !isIdentifierChar(text.charAt(i - 1));
        }

        private static boolean isIdentifierChar(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '$';
        }

        private int lineAt(int offset) {
            int line = 1;
            for (int i = 0; i < offset; i++) {
                if (text.charAt(i) == '\n') {
                    line++;
                }
            }
            return line;
        }

        private IllegalStateException malformed(int offset) {
            return new IllegalStateException(source.path() + ":" + lineAt(offset) + " 字符串字面量没有收尾引号，疑似词法失配");
        }
    }
}

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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 表引用检测的纯逻辑：输入是现行 schema 的表集合、豁免清单与源文本，输出是违规清单与扫描计数。
 * 不读文件、不碰构建工具——取数由守卫测试负责，自测直接喂内存里的源码。
 * <p>
 * 源文本逐字符按 Java 词法处理（字符串字面量、文本块、字符字面量、注释各自成态），不解析 SQL：
 * 字符串字面量与文本块里带表前缀 {@code t_} 的标识符，以及 {@code @TableName} 表名实参上的字面量
 * （整个字面量按表名核对，不看前缀），不在表集合与豁免清单里即违规；注释里的内容不参与。
 * 扫描口径与已知边界见 {@link TableReferenceGuardTest}。
 */
public final class TableReferenceScanner {

    private TableReferenceScanner() {
    }

    /**
     * 违规类型
     */
    public enum Kind {
        /**
         * 引用了现行 schema 没有的表：字符串字面量里带表前缀的记号，或 {@code @TableName} 表名实参上的字面量
         */
        UNKNOWN_TABLE("现行 schema 里不存在的表引用"),

        /**
         * {@code @TableName} 没有写字面量表名，表名由命名策略或常量推导，守卫看不到
         */
        TABLE_NAME_NOT_LITERAL("@TableName 未显式写字面量表名");

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
     * @param identifier 被引用的表名记号；{@link Kind#TABLE_NAME_NOT_LITERAL} 时为空串
     */
    public record Violation(String file, int line, Kind kind, String identifier) {

        @Override
        public String toString() {
            return file + ":" + line + " " + kind.label + (identifier.isEmpty() ? "" : "：" + identifier);
        }
    }

    /**
     * @param violations           违规清单，按源文件顺序
     * @param literalTableMentions 字符串字面量与文本块里出现的表名记号次数（不含 @TableName 实参）
     * @param tableNameAnnotations 主代码里 {@code @TableName} 的出现次数
     */
    public record Report(List<Violation> violations, int literalTableMentions, int tableNameAnnotations) {
    }

    /**
     * @param schemaTables 现行 schema 的表集合，比较时不区分大小写
     * @param exemptions   豁免清单：表名 → 理由。理由为空的条目不生效
     * @param sources      待扫描的源文件
     */
    public static Report scan(Set<String> schemaTables, Map<String, String> exemptions, List<SourceFile> sources) {
        Set<String> tables = lowerCase(schemaTables);
        Set<String> exempt = new HashSet<>();
        for (Map.Entry<String, String> exemption : exemptions.entrySet()) {
            if (exemption.getValue() != null && !exemption.getValue().isBlank()) {
                exempt.add(exemption.getKey().toLowerCase(Locale.ROOT));
            }
        }
        List<Violation> violations = new ArrayList<>();
        int mentions = 0;
        int annotations = 0;
        for (SourceFile source : sources) {
            Scan scan = new Scan(source, tables, exempt);
            violations.addAll(scan.run());
            mentions += scan.literalTableMentions;
            annotations += scan.tableNameAnnotations;
        }
        return new Report(List.copyOf(violations), mentions, annotations);
    }

    private static Set<String> lowerCase(Set<String> names) {
        Set<String> lowered = new HashSet<>();
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

        private static final String TEXT_BLOCK_DELIMITER = "\"\"\"";

        private static final String LINE_COMMENT = "//";

        private static final String BLOCK_COMMENT_OPEN = "/*";

        private static final String BLOCK_COMMENT_CLOSE = "*/";

        private static final char CHAR_QUOTE = '\'';

        /**
         * 实体类上声明表名的注解，名字取限定名的最后一段
         */
        private static final String TABLE_NAME_ANNOTATION = "TableName";

        private final SourceFile source;
        private final String text;
        private final Set<String> tables;
        private final Set<String> exempt;
        private final List<Violation> violations = new ArrayList<>();
        private int literalTableMentions;
        private int tableNameAnnotations;

        private Scan(SourceFile source, Set<String> tables, Set<String> exempt) {
            this.source = source;
            this.text = source.text();
            this.tables = tables;
            this.exempt = exempt;
        }

        private List<Violation> run() {
            int i = 0;
            while (i < text.length()) {
                int after = skipCommentOrCharLiteral(i);
                if (after != i) {
                    i = after;
                } else if (text.charAt(i) == QUOTE) {
                    Literal literal = literal(i);
                    collectTableTokens(literal.contentStart(), literal.closing());
                    i = literal.end();
                } else if (text.charAt(i) == '@') {
                    i = annotation(i);
                } else {
                    i++;
                }
            }
            return violations;
        }

        /**
         * 跳过注释与字符字面量：主扫描与 {@code @TableName} 实参列表共用这一处词法，两边的跳法不会分叉
         *
         * @return 结构之后的下标；{@code at} 处既不是注释也不是字符字面量时原样返回 {@code at}
         */
        private int skipCommentOrCharLiteral(int at) {
            char c = text.charAt(at);
            if (c == '/' && text.startsWith(LINE_COMMENT, at)) {
                return endOfLineComment(at);
            }
            if (c == '/' && text.startsWith(BLOCK_COMMENT_OPEN, at)) {
                return endOfBlockComment(at);
            }
            return c == CHAR_QUOTE ? charLiteral(at) : at;
        }

        /**
         * 行注释到行尾为止，注释里的引号与块注释起始符都不算数
         *
         * @return 换行符的下标（文件没有以换行结尾时为文本长度）
         */
        private int endOfLineComment(int start) {
            int end = text.indexOf('\n', start + LINE_COMMENT.length());
            return end < 0 ? text.length() : end;
        }

        /**
         * 块注释（含 Javadoc）到第一个 {@code *\/} 为止，不嵌套
         *
         * @return 收尾符之后的下标
         */
        private int endOfBlockComment(int start) {
            int end = text.indexOf(BLOCK_COMMENT_CLOSE, start + BLOCK_COMMENT_OPEN.length());
            if (end < 0) {
                throw malformed(start, "块注释没有收尾的 */");
            }
            return end + BLOCK_COMMENT_CLOSE.length();
        }

        /**
         * 跳过一个字符字面量：{@code 'a'}、转义形式（含 {@code '\''}、{@code '\\'}）与 unicode 转义形式
         *
         * @return 收尾引号之后的下标
         */
        private int charLiteral(int openingQuote) {
            int i = openingQuote + 1;
            if (i < text.length() && text.charAt(i) == '\\') {
                i++;
                if (i < text.length() && text.charAt(i) == 'u') {
                    while (i < text.length() && text.charAt(i) == 'u') {
                        i++;
                    }
                    i += 4;
                } else {
                    i++;
                }
            } else {
                i++;
            }
            if (i >= text.length() || text.charAt(i) != CHAR_QUOTE) {
                throw malformed(openingQuote, "字符字面量没有收尾引号");
            }
            return i + 1;
        }

        /**
         * 字符串字面量或文本块
         *
         * @param contentStart 内容起点（不含起始定界符）
         * @param closing      收尾定界符起点
         * @param textBlock    是否文本块
         */
        private record Literal(int contentStart, int closing, boolean textBlock) {

            /**
             * @return 收尾定界符之后的下标
             */
            int end() {
                return closing + (textBlock ? TEXT_BLOCK_DELIMITER.length() : 1);
            }
        }

        /**
         * 读一个字符串字面量或文本块。
         * 文本块里可以内嵌单个、成对的双引号，只有 {@code """} 收尾；
         * 单行字符串字面量不能跨行，读到换行即词法失配（否则会一路吞到下一段文本里的引号）
         */
        private Literal literal(int openingQuote) {
            boolean textBlock = text.startsWith(TEXT_BLOCK_DELIMITER, openingQuote);
            int contentStart = openingQuote + (textBlock ? TEXT_BLOCK_DELIMITER.length() : 1);
            int i = contentStart;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '\\') {
                    if (!textBlock && i + 1 < text.length() && isLineTerminator(text.charAt(i + 1))) {
                        break;
                    }
                    i += 2;
                    continue;
                }
                if (c == QUOTE && (!textBlock || text.startsWith(TEXT_BLOCK_DELIMITER, i))) {
                    return new Literal(contentStart, i, textBlock);
                }
                if (!textBlock && isLineTerminator(c)) {
                    break;
                }
                i++;
            }
            throw malformed(openingQuote, "字符串字面量或文本块没有收尾定界符");
        }

        private static boolean isLineTerminator(char c) {
            return c == '\n' || c == '\r';
        }

        /**
         * 读一个注解，名字取限定名的最后一段。是 {@code TableName} 时解析实参：
         * {@code value}（或首个位置实参）上的字符串字面量就是显式写出的表名
         *
         * @return 注解之后的下标
         */
        private int annotation(int at) {
            int i = at + 1;
            int segmentStart = i;
            while (i < text.length() && (isIdentifierChar(text.charAt(i)) || text.charAt(i) == '.')) {
                if (text.charAt(i) == '.') {
                    segmentStart = i + 1;
                }
                i++;
            }
            if (!TABLE_NAME_ANNOTATION.equals(text.substring(segmentStart, i))) {
                return i;
            }
            tableNameAnnotations++;
            int argsStart = skipWhitespace(i);
            if (argsStart < text.length() && text.charAt(argsStart) == '(') {
                return tableNameArguments(at, argsStart);
            }
            violations.add(tableNameNotLiteral(at));
            return i;
        }

        private Violation tableNameNotLiteral(int at) {
            return new Violation(source.path(), lineAt(at), Kind.TABLE_NAME_NOT_LITERAL, "");
        }

        /**
         * 解析 {@code @TableName} 的实参列表：表名实参是首个位置实参，或 {@code value =} 之后的那一个，
         * 只有它上面的字符串字面量按表名核对，其余实参（如 schema）的字符串不算。
         * 表名实参上没有字符串字面量时报违规
         *
         * @param at            注解起点（@ 所在下标），报违规用
         * @param openingParen  实参列表的左括号下标
         * @return 收尾右括号之后的下标
         */
        private int tableNameArguments(int at, int openingParen) {
            // 首个位置实参就是表名；读到「实参名 =」后，改看这个实参是不是 value
            boolean inTableArgument = true;
            boolean hasTableLiteral = false;
            int i = openingParen + 1;
            while (i < text.length() && text.charAt(i) != ')') {
                int after = skipCommentOrCharLiteral(i);
                if (after != i) {
                    i = after;
                } else if (text.charAt(i) == QUOTE) {
                    Literal literal = literal(i);
                    if (inTableArgument) {
                        hasTableLiteral = true;
                        checkTableNameLiteral(literal);
                    }
                    i = literal.end();
                } else if (isIdentifierStart(text.charAt(i))) {
                    int wordEnd = i + 1;
                    while (wordEnd < text.length() && isIdentifierChar(text.charAt(wordEnd))) {
                        wordEnd++;
                    }
                    int equals = skipWhitespace(wordEnd);
                    if (equals < text.length() && text.charAt(equals) == '=') {
                        inTableArgument = "value".equals(text.substring(i, wordEnd));
                    }
                    i = wordEnd;
                } else {
                    i++;
                }
            }
            if (i >= text.length()) {
                throw malformed(openingParen, "注解实参列表没有收尾的右括号");
            }
            if (!hasTableLiteral) {
                violations.add(tableNameNotLiteral(at));
            }
            return i + 1;
        }

        private int skipWhitespace(int from) {
            int i = from;
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            return i;
        }

        /**
         * 核对表名实参上的字面量：整个字面量就是表名，带不带表前缀都对照表集合与豁免清单（不区分大小写）
         */
        private void checkTableNameLiteral(Literal literal) {
            String tableName = text.substring(literal.contentStart(), literal.closing());
            String lowered = tableName.toLowerCase(Locale.ROOT);
            if (!tables.contains(lowered) && !exempt.contains(lowered)) {
                violations.add(new Violation(source.path(), lineAt(literal.contentStart()),
                        Kind.UNKNOWN_TABLE, tableName));
            }
        }

        /**
         * 在 [from, to) 里按表前缀切出记号：每个记号计入字符串字面量里的表名出现次数，
         * 不在表集合与豁免清单里的记号报违规
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
                String lowered = token.toLowerCase(Locale.ROOT);
                if (!tables.contains(lowered) && !exempt.contains(lowered)) {
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
            return isIdentifierStart(c) || (c >= '0' && c <= '9');
        }

        private static boolean isIdentifierStart(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$';
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

        private IllegalStateException malformed(int offset, String what) {
            return new IllegalStateException(source.path() + ":" + lineAt(offset) + " " + what + "，疑似词法失配");
        }
    }
}

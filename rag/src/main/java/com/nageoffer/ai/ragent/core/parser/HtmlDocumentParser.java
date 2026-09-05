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

import com.nageoffer.ai.ragent.core.parser.model.Block;
import com.nageoffer.ai.ragent.core.parser.model.HeadingBlock;
import com.nageoffer.ai.ragent.core.parser.model.ListBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParagraphBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;
import com.nageoffer.ai.ragent.core.parser.model.Provenance;
import com.nageoffer.ai.ragent.core.parser.model.TableBlock;
import com.nageoffer.ai.ragent.core.parser.registry.ParseProfile;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HTML 结构化解析器：Jsoup DOM 遍历，先把正文从页面模板里摘出来，再按结构产 Block，
 * 解决 Tika 拍平 HTML 的三类失真——导航/页脚噪声混入正文、标题层级与列表编号丢失、
 * 表格跨行列组（rowspan/colspan/分组行头）的归属关系被打散
 * <p>
 * 三步走：① 按通用优先级（main → article → 内容容器 → body 兜底）选定主体区域，
 * 页头/页脚/面包屑天然留在区域之外；② 区域内清洗导航类模板（菜单、分享栏、skip 链接、
 * 隐藏元素等），保留提示框与业务链接；③ DOM 顺序遍历产出 Block，交给既有 Block-aware
 * chunker 分块，本类不触碰分块与检索链路
 * <p>
 * FAQ 页的问题多以普通 {@code <p>} 出现（以问号结尾的短段落），此处将这类段落升格为
 * HeadingBlock，使后续答案块的章节路径携带问题原文，问答保持明确关联；
 * Bootstrap 手风琴的触发链接（{@code a[data-toggle=collapse]}）结构上即分节标题
 * （答案折叠在兄弟面板里），不论文本是否问句，与 details/summary 同规则升格
 */
@Slf4j
@Component
public class HtmlDocumentParser implements DocumentParser {

    /**
     * 全文档级剥离：产出物无检索价值的非内容节点，在主体选择前先删掉以缩小遍历范围
     */
    private static final String GLOBAL_STRIP = String.join(", ",
            "script", "style", "noscript", "template", "svg", "iframe", "canvas",
            "object", "embed", "link", "meta", "title", "head");

    /**
     * 主体区域候选，按优先级依次探测，第一个文本量达标的胜出；
     * {@code .region-content} 是 Drupal 站点（PolyU Library 即是）的正文区，排在通用语义标签之后
     */
    private static final List<String> CONTENT_SELECTORS = List.of(
            "main",
            "[role=main]",
            "article",
            ".region-content",
            "#content",
            "#main-content",
            ".main-content",
            "[itemprop=articleBody]",
            "#mw-content-text",
            ".post-content",
            ".entry-content");

    /**
     * 候选区域的最低文本量：低于它视为选中了错误容器（如空壳 main），继续尝试下一个候选
     */
    private static final int CONTENT_MIN_CHARS = 80;

    /**
     * 区域内导航/模板清洗：这些元素即使在主体区域内也是模板（侧栏菜单、移动端菜单弹窗、
     * 面包屑、分享栏、skip 链接、cookie 提示、对屏幕阅读器隐藏的内容、Drupal 菜单/页脚 region）。
     * 刻意不碰 {@code header}/{@code footer} 标签本身——正文 {@code <article>} 内的
     * {@code <header>} 装的是文章元信息与标题（PolyU 站实测），无条件删会丢正文；页面级
     * 页头页脚靠主体区域选择天然排除，兜底到 body 时另行走 {@link #stripBodyLevelChrome}
     */
    private static final String NAV_STRIP = String.join(", ",
            "nav", "aside",
            "[role=navigation]", "[role=banner]", "[role=contentinfo]", "[role=menu]", "[role=menubar]",
            "[role=menuitem]", "[role=dialog]", "[role=alertdialog]", "[role=search]", "[aria-modal=true]",
            ".breadcrumb", ".breadcrumbs", "#breadcrumbs", "[aria-label*=readcrumb i]",
            "[class*=share-blk]", "[class*=share-area]", "[class*=sharedrop]",
            "[class*=social-share]", "[class*=socialsharing]",
            ".skip-link", "a.skip", "[class*=skip-to]", "a[href=#main]", "a[href=#main-content]", "a[href=#content]",
            "[id*=cookie i]", "[class*=cookie i]", "#onetrust-consent-sdk",
            "[class*=side-menu]", "[id*=side-menu]",
            "[class*=mobile-menu]", "[class*=mb-mn]",
            "[class*=main-menu]", "[id*=main-menu]",
            ".region-menu", ".region-header", ".region-footer", ".region-navigation", ".region-branding",
            ".book-navigation", ".modal", ".dropdown-menu", ".search-form",
            "[hidden]", ".hidden", ".d-none", ".element-invisible", ".visually-hidden", ".sr-only",
            ".screen-reader-text", "[aria-hidden=true]",
            "[style*=display:none]", "[style*=display: none]",
            "[style*=visibility:hidden]", "[style*=visibility: hidden]");

    /**
     * 问句段落升格为 HeadingBlock 的长度上限：问题应该是短段落，长句以问号收尾多半是反问/引用而非 FAQ 问题
     */
    private static final int QUESTION_MAX_CHARS = 200;

    @Override
    public String getParserType() {
        return ParserType.HTML.getType();
    }

    @Override
    public ParsedDocument parseStructured(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) {
            return ParsedDocument.of(List.of());
        }

        Document doc;
        try {
            // charsetName 传 null：先按 UTF-8 读，遇 meta charset 声明不一致时 Jsoup 会以声明编码重读
            doc = Jsoup.parse(new ByteArrayInputStream(content), null, "");
        } catch (Exception e) {
            log.error("Jsoup HTML 解析失败，MIME 类型: {}", mimeType, e);
            return ParsedDocument.of(List.of());
        }

        doc.select(GLOBAL_STRIP).remove();

        Element region = selectContentRegion(doc);
        boolean bodyFallback = region.tagName().equals("body") || region.tagName().equals("#root");
        if (bodyFallback) {
            stripBodyLevelChrome(region);
        }
        region.select(NAV_STRIP).remove();

        Provenance prov = Provenance.ofFile(extractSourceFile(options));
        List<Block> blocks = new ArrayList<>();
        new RegionWalker(prov).walk(region, blocks);

        return ParsedDocument.of(blocks, Map.of(
                "parser", getParserType(),
                "mimeType", mimeType == null ? "" : mimeType,
                "contentRegion", bodyFallback ? "body(fallback)" : describe(region),
                "blocks", blocks.size()));
    }

    /**
     * 认领清单：Tika 让渡出 HTML 的两个精确键，本解析器是 HTML 唯一的 FAST 档解析器；
     * 注册表键冲突即启动失败，不会出现同一 MIME 被静默双注册
     */
    @Override
    public Map<ParseProfile, Set<String>> supportedMimeTypes() {
        return Map.of(ParseProfile.FAST, Set.of("text/html", "application/xhtml+xml"));
    }

    /**
     * 兜底到 body 时的顶层模板剥离：页面级页头/页脚/导航通常直接挂在 body 下，
     * 只删 body 直接子级的 chrome，嵌套在 article/section/main 内的同名标签（如文章标题头）不动
     */
    private void stripBodyLevelChrome(Element body) {
        for (Element child : body.children()) {
            String tag = child.tagName();
            if (tag.equals("header") || tag.equals("footer") || tag.equals("nav") || tag.equals("aside")) {
                child.remove();
            }
        }
    }

    /**
     * 主体区域选择：候选依次探测，文本量达标的第一个胜出；候选都在但文本量不足
     * （正文极短的页面）时取文本最长的候选，避免退回 body 引入整页噪声；
     * 完全没有候选才兜底 body，宁多带噪声也不返回空容器丢正文
     */
    private Element selectContentRegion(Document doc) {
        Element longest = null;
        int longestLen = -1;
        for (String selector : CONTENT_SELECTORS) {
            for (Element candidate : doc.select(selector)) {
                int len = normalizeWhitespace(candidate.text()).length();
                if (len >= CONTENT_MIN_CHARS) {
                    return candidate;
                }
                if (len > longestLen) {
                    longestLen = len;
                    longest = candidate;
                }
            }
        }
        if (longest != null) {
            return longest;
        }
        Element body = doc.body();
        return body == null ? doc : body;
    }

    private static String describe(Element element) {
        String id = element.id();
        return id.isEmpty() ? element.tagName() : element.tagName() + "#" + id;
    }

    private static String extractSourceFile(Map<String, Object> options) {
        if (options == null) {
            return "";
        }
        Object v = options.get("sourceFile");
        return v == null ? "" : v.toString();
    }

    /**
     * DOM 顺序遍历：产 Block 的元素（标题/段落/列表/表格/定义列表）落块并截断匿名文本缓冲，
     * 其余容器与行内元素透明递归，其文本汇入缓冲在下一个块边界成段
     */
    private final class RegionWalker {

        private final Provenance prov;

        /**
         * 匿名文本缓冲：块级边界间的零散文本（裸 div 文本、行内元素）聚合成段
         */
        private final StringBuilder buffer = new StringBuilder();

        /**
         * 最近一个真实标题的级别，FAQ 问句段落的升格级别以它为基准，问题之间保持同级
         */
        private int lastHeadingLevel = 0;

        private RegionWalker(Provenance prov) {
            this.prov = prov;
        }

        /**
         * 顶层遍历：区域走完后把残余匿名文本落段
         */
        private void walk(Node node, List<Block> blocks) {
            walkChildren(node, blocks);
            flush(blocks);
        }

        /**
         * 子节点遍历：递归不 flush——行内元素（b/strong/a）与透明容器（div）的边界
         * 不是段落边界，递归返回即截断会把一句话按行内标签切碎
         */
        private void walkChildren(Node node, List<Block> blocks) {
            for (int i = 0; i < node.childNodeSize(); i++) {
                Node child = node.childNode(i);
                if (child instanceof TextNode textNode) {
                    buffer.append(textNode.text());
                    continue;
                }
                if (child instanceof Element element) {
                    dispatch(element, blocks);
                }
            }
        }

        private void dispatch(Element element, List<Block> blocks) {
            String tag = element.tagName();
            switch (tag) {
                case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    flush(blocks);
                    int level = Math.min(6, Math.max(1, Integer.parseInt(tag.substring(1))));
                    String text = normalizeWhitespace(element.text());
                    if (!text.isEmpty()) {
                        blocks.add(new HeadingBlock(prov, level, text));
                        lastHeadingLevel = level;
                    }
                }
                case "p" -> {
                    flush(blocks);
                    String text = normalizeWhitespace(element.text());
                    if (text.isEmpty()) {
                        return;
                    }
                    if (isQuestion(text)) {
                        // FAQ 问题段落：升格为标题，后续答案块的章节路径携带问题原文
                        blocks.add(new HeadingBlock(prov, questionLevel(), text));
                    } else {
                        blocks.add(new ParagraphBlock(prov, text));
                    }
                }
                case "ul", "ol" -> {
                    flush(blocks);
                    emitList(element, tag.equals("ol"), blocks);
                }
                case "a" -> {
                    // 手风琴触发链接：升格判定只认结构标记不认文本形态——泛化到
                    // 「问号结尾的链接」会把侧栏菜单/按钮链接误升成标题（A6/S5 判例）
                    if ("collapse".equals(element.attr("data-toggle"))) {
                        flush(blocks);
                        String text = normalizeWhitespace(element.text());
                        if (!text.isEmpty()) {
                            blocks.add(new HeadingBlock(prov, questionLevel(), text));
                        }
                    } else {
                        walkChildren(element, blocks);
                    }
                }
                case "table" -> {
                    flush(blocks);
                    emitTable(element, blocks);
                }
                case "dl" -> {
                    flush(blocks);
                    emitDefinitionList(element, blocks);
                }
                case "details" -> {
                    flush(blocks);
                    // summary 已单独落标题，摘除后避免正文遍历重复产出
                    Element summary = element.selectFirst("> summary");
                    if (summary != null) {
                        String text = normalizeWhitespace(summary.text());
                        if (!text.isEmpty()) {
                            blocks.add(new HeadingBlock(prov, questionLevel(), text));
                        }
                        summary.remove();
                    }
                    walk(element, blocks);
                }
                case "figure" -> {
                    flush(blocks);
                    Element caption = element.selectFirst("figcaption");
                    if (caption != null) {
                        String text = normalizeWhitespace(caption.text());
                        if (!text.isEmpty()) {
                            blocks.add(new ParagraphBlock(prov, text));
                        }
                        caption.remove();
                    }
                    walk(element, blocks);
                }
                case "blockquote", "pre" -> {
                    flush(blocks);
                    String text = normalizeWhitespace(element.text());
                    if (!text.isEmpty()) {
                        blocks.add(new ParagraphBlock(prov, text));
                    }
                }
                case "br", "wbr" -> buffer.append(' ');
                case "hr" -> flush(blocks);
                // 表单控件与媒体产出物无检索价值；img 的 alt 多为装饰描述，不进语料
                case "img", "input", "select", "textarea", "button", "video", "audio", "map", "area" -> {
                }
                default -> walkChildren(element, blocks);
            }
        }

        /**
         * 列表产出：简单项（纯文本/行内元素）聚合为 ListBlock 交 ListChunker 补编号；
         * 富列表项（li 直接持有带实际文本的块级子元素，WYSIWYG 编辑器的 FAQ 形态）
         * 拍平会破坏项内结构——问句段落与答案段落粘连，故逐项递归走常规 dispatch
         * （问句段落照常升格标题，答案段落带上问题章节路径），列表编号让位于问答结构。
         * 只装图片的空段落不算富项（截图挂载形态），避免装饰图把普通步骤列表打散成碎段。
         * 非 li 子节点（编辑器把 span/p 等直接挂进列表，S6 宿费政策判例）不丢弃：
         * 携带正文时冲刷已聚合的列表项保住 DOM 序，再按常规遍历产出并告警留痕
         */
        private void emitList(Element list, boolean ordered, List<Block> blocks) {
            boolean richItems = false;
            for (Element li : list.children()) {
                if (li.tagName().equals("li") && hasTextualBlockChild(li)) {
                    richItems = true;
                    break;
                }
            }
            List<String> items = new ArrayList<>();
            for (Element child : list.children()) {
                if (!child.tagName().equals("li")) {
                    String text = normalizeWhitespace(child.text());
                    if (!text.isEmpty()) {
                        log.warn("列表内非 li 子节点 <{}> 携带正文 {} 字符，按常规遍历产出",
                                child.tagName(), text.length());
                        emitItems(items, ordered, blocks);
                        walk(child, blocks);
                    }
                    continue;
                }
                if (richItems) {
                    walk(child, blocks);
                } else {
                    String text = normalizeWhitespace(child.text());
                    if (!text.isEmpty()) {
                        items.add(text);
                    }
                }
            }
            emitItems(items, ordered, blocks);
        }

        /**
         * 把已聚合的列表项落成 ListBlock 并清空，供非 li 子节点把一个列表按 DOM 序切开
         */
        private void emitItems(List<String> items, boolean ordered, List<Block> blocks) {
            if (items.isEmpty()) {
                return;
            }
            blocks.add(new ListBlock(prov, ordered, new ArrayList<>(items)));
            items.clear();
        }

        /**
         * li 是否直接持有带实际文本的块级子元素
         */
        private boolean hasTextualBlockChild(Element li) {
            for (Element child : li.children()) {
                String tag = child.tagName();
                if (tag.equals("p") || tag.equals("div") || tag.equals("table") || tag.equals("pre")
                        || tag.equals("blockquote") || tag.equals("dl")) {
                    if (!normalizeWhitespace(child.text()).isEmpty()) {
                        return true;
                    }
                }
            }
            return false;
        }

        private void emitDefinitionList(Element dl, List<Block> blocks) {
            String term = null;
            List<String> definitions = new ArrayList<>();
            for (Element child : dl.children()) {
                String tag = child.tagName();
                if (tag.equals("dt")) {
                    emitDefinitionEntry(term, definitions, blocks);
                    term = normalizeWhitespace(child.text());
                    definitions = new ArrayList<>();
                } else if (tag.equals("dd")) {
                    String text = normalizeWhitespace(child.text());
                    if (!text.isEmpty()) {
                        definitions.add(text);
                    }
                }
            }
            emitDefinitionEntry(term, definitions, blocks);
        }

        /**
         * 术语与解释合成单段，保证 dt/dd 不被分块拆散后各自失去对方
         */
        private void emitDefinitionEntry(String term, List<String> definitions, List<Block> blocks) {
            if ((term == null || term.isEmpty()) && definitions.isEmpty()) {
                return;
            }
            String body = String.join("; ", definitions);
            String text = term == null || term.isEmpty() ? body : (body.isEmpty() ? term : term + ": " + body);
            blocks.add(new ParagraphBlock(prov, text));
        }

        private void flush(List<Block> blocks) {
            String text = normalizeWhitespace(buffer.toString());
            buffer.setLength(0);
            if (!text.isEmpty()) {
                blocks.add(new ParagraphBlock(prov, text));
            }
        }

        private boolean isQuestion(String text) {
            String trimmed = text.strip();
            return trimmed.length() <= QUESTION_MAX_CHARS
                    && (trimmed.endsWith("?") || trimmed.endsWith("？"));
        }

        private int questionLevel() {
            return Math.min(6, Math.max(2, lastHeadingLevel + 1));
        }

        // ==================== 表格解析 ====================

        /**
         * 表格行分类：thead 来源、分组行头、候选表头、数据行
         */
        private enum RowKind {HEADER, GROUP, DATA}

        /**
         * rowspan 覆盖中：某列上方单元格向下延续的值与剩余行数；
         * colspan 展开的次列单独登记空值延续，避免跨列值在后续行被重复渲染
         */
        private record PendingSpan(String value, int remainingRows) {
        }

        /**
         * 表格产出：caption 落段，主体落一个 TableBlock；分组行头（全宽标题单元格，
         * 如开放时间表按学期分段）并入后续每行的首列，rowspan 行头展开填充到每行——
         * 两类列组归属失真（0B-2A 报告判据③）在此一并修复，由 TableChunker 以「列名: 值」渲染
         */
        private void emitTable(Element table, List<Block> blocks) {
            Element caption = table.selectFirst("> caption");
            if (caption != null) {
                String text = normalizeWhitespace(caption.text());
                if (!text.isEmpty()) {
                    blocks.add(new ParagraphBlock(prov, text));
                }
            }

            List<Element> rows = collectDirectRows(table);
            if (rows.isEmpty()) {
                return;
            }
            List<List<String>> grid = expandGrid(rows);
            int maxCols = grid.stream().mapToInt(List::size).max().orElse(0);
            if (maxCols == 0) {
                return;
            }

            List<RowKind> kinds = classifyRows(rows, maxCols);
            List<String> headers = flattenHeaders(grid, kinds);

            List<List<String>> dataRows = new ArrayList<>();
            String currentGroup = null;
            boolean hasGroups = false;
            for (int r = 0; r < rows.size(); r++) {
                if (kinds.get(r) == RowKind.GROUP) {
                    currentGroup = soleNonBlankCell(grid.get(r));
                    hasGroups = true;
                    continue;
                }
                if (kinds.get(r) != RowKind.DATA || isBlankRow(grid.get(r))) {
                    continue;
                }
                List<String> out = new ArrayList<>();
                if (hasGroups) {
                    out.add(currentGroup == null ? "" : currentGroup);
                }
                out.addAll(grid.get(r));
                dataRows.add(out);
            }

            if (hasGroups) {
                headers.add(0, "Group");
            }
            if (headers.isEmpty() && dataRows.isEmpty()) {
                return;
            }
            blocks.add(new TableBlock(prov, headers, dataRows));
        }

        /**
         * 收集直属行：按文档序单趟遍历，嵌套表格的行不入内（thead 物理上写在表格前部，文档序即阅读序）
         */
        private List<Element> collectDirectRows(Element table) {
            List<Element> rows = new ArrayList<>();
            for (Element child : table.children()) {
                String tag = child.tagName();
                if (tag.equals("tr")) {
                    rows.add(child);
                } else if (tag.equals("thead") || tag.equals("tbody") || tag.equals("tfoot")) {
                    appendDirectRows(child, rows);
                }
            }
            return rows;
        }

        private void appendDirectRows(Element parent, List<Element> rows) {
            for (Element child : parent.children()) {
                if (child.tagName().equals("tr")) {
                    rows.add(child);
                }
            }
        }

        /**
         * 二维网格展开：colspan 首列落值、次列留空；rowspan 的值沿列向下延续到后续行，
         * 让跨行组名（如用户组、服务大类）出现在它覆盖的每一行里
         */
        private List<List<String>> expandGrid(List<Element> rows) {
            List<List<String>> grid = new ArrayList<>();
            Map<Integer, PendingSpan> pending = new HashMap<>();
            for (Element tr : rows) {
                List<String> row = new ArrayList<>();
                int col = 0;
                for (Element cell : tr.children()) {
                    String tag = cell.tagName();
                    if (!tag.equals("td") && !tag.equals("th")) {
                        continue;
                    }
                    col = drainPending(pending, row, col);
                    String text = normalizeWhitespace(cell.text());
                    int colspan = parseSpan(cell, "colspan");
                    int rowspan = parseSpan(cell, "rowspan");
                    for (int i = 0; i < colspan; i++) {
                        setCell(row, col + i, i == 0 ? text : "");
                    }
                    if (rowspan > 1) {
                        for (int i = 0; i < colspan; i++) {
                            pending.put(col + i, new PendingSpan(i == 0 ? text : "", rowspan - 1));
                        }
                    }
                    col += colspan;
                }
                drainPending(pending, row, col);
                grid.add(row);
            }
            return grid;
        }

        /**
         * 落位上方 rowspan 延续下来的单元格并推进列号
         */
        private int drainPending(Map<Integer, PendingSpan> pending, List<String> row, int col) {
            while (pending.containsKey(col)) {
                PendingSpan span = pending.get(col);
                setCell(row, col, span.value());
                if (span.remainingRows() <= 1) {
                    pending.remove(col);
                } else {
                    pending.put(col, new PendingSpan(span.value(), span.remainingRows() - 1));
                }
                col++;
            }
            return col;
        }

        /**
         * 行分类：thead 来源判表头；其后「单格覆盖全宽」判分组行头（优先于表头特征，
         * 开放时间表的学期行只含一个 colspan 全宽单元格）；再后，数据行出现之前的
         * 「全 th」或「非空单元格皆以 h1-h6 为主体」的行判表头（后者覆盖无 th 的
         * 列名写在 td 内 h6 的形态）；其余为数据行
         */
        private List<RowKind> classifyRows(List<Element> rows, int maxCols) {
            List<RowKind> kinds = new ArrayList<>();
            boolean dataSeen = false;
            for (Element tr : rows) {
                boolean inThead = tr.parent() != null && tr.parent().tagName().equals("thead");
                if (inThead) {
                    kinds.add(RowKind.HEADER);
                    continue;
                }
                if (isGroupHeaderRow(tr, maxCols)) {
                    kinds.add(RowKind.GROUP);
                    continue;
                }
                if (!dataSeen && looksLikeHeaderRow(tr)) {
                    kinds.add(RowKind.HEADER);
                    continue;
                }
                kinds.add(RowKind.DATA);
                dataSeen = true;
            }
            return kinds;
        }

        /**
         * 分组行头判定：整行只有一个单元格且其 colspan 覆盖表格全宽
         * （如「Term Time 31 Aug - 22 Nov 2026」独占 colspan=3 的一行）；
         * 单列表格除外——列数为一时任何单元格的 colspan 都不小于全宽，
         * 全判组头会让数据行尽数丢失
         */
        private boolean isGroupHeaderRow(Element tr, int maxCols) {
            if (maxCols <= 1) {
                return false;
            }
            List<Element> cells = dataCells(tr);
            if (cells.size() != 1) {
                return false;
            }
            Element cell = cells.get(0);
            return !normalizeWhitespace(cell.text()).isEmpty() && parseSpan(cell, "colspan") >= maxCols;
        }

        private boolean looksLikeHeaderRow(Element tr) {
            List<Element> cells = dataCells(tr);
            if (cells.isEmpty()) {
                return false;
            }
            boolean allTh = cells.stream().allMatch(c -> c.tagName().equals("th"));
            if (allTh) {
                return true;
            }
            long nonBlank = cells.stream().filter(c -> !normalizeWhitespace(c.text()).isEmpty()).count();
            long headingCells = cells.stream()
                    .filter(c -> !normalizeWhitespace(c.text()).isEmpty())
                    .filter(c -> !c.select("h1,h2,h3,h4,h5,h6").isEmpty())
                    .count();
            return nonBlank > 0 && headingCells == nonBlank;
        }

        /**
         * 多行表头展平为单行：每列的非空表头值以竖线拼接，rowspan 表头延续产生的重复去重，
         * 与 ExcelTableNormalizer 的多行表头约定一致
         */
        private List<String> flattenHeaders(List<List<String>> grid, List<RowKind> kinds) {
            int maxCols = grid.stream().mapToInt(List::size).max().orElse(0);
            List<String> headers = new ArrayList<>();
            for (int c = 0; c < maxCols; c++) {
                Set<String> parts = new LinkedHashSet<>();
                for (int r = 0; r < grid.size(); r++) {
                    if (kinds.get(r) == RowKind.HEADER) {
                        String value = cellAt(grid.get(r), c);
                        if (!value.isEmpty()) {
                            parts.add(value);
                        }
                    }
                }
                headers.add(String.join("|", parts));
            }
            return headers;
        }

        private List<Element> dataCells(Element tr) {
            List<Element> cells = new ArrayList<>();
            for (Element child : tr.children()) {
                String tag = child.tagName();
                if (tag.equals("td") || tag.equals("th")) {
                    cells.add(child);
                }
            }
            return cells;
        }

        private String soleNonBlankCell(List<String> row) {
            for (String cell : row) {
                if (cell != null && !cell.isEmpty()) {
                    return cell;
                }
            }
            return "";
        }

        private boolean isBlankRow(List<String> row) {
            for (String cell : row) {
                if (cell != null && !cell.isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        private void setCell(List<String> row, int col, String value) {
            while (row.size() <= col) {
                row.add("");
            }
            row.set(col, value == null ? "" : value);
        }

        private String cellAt(List<String> row, int col) {
            return col < row.size() && row.get(col) != null ? row.get(col) : "";
        }

        private int parseSpan(Element cell, String attr) {
            try {
                return Math.max(1, Integer.parseInt(cell.attr(attr).trim()));
            } catch (NumberFormatException e) {
                return 1;
            }
        }
    }

    /**
     * 空白规范化：NBSP 转普通空格、任意空白序列压成单空格——单元格与段落的展示对齐都交给下游
     */
    private static String normalizeWhitespace(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }
}

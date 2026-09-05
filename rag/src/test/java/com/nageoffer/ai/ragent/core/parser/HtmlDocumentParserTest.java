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
import com.nageoffer.ai.ragent.core.parser.model.TableBlock;
import com.nageoffer.ai.ragent.core.parser.registry.ParseProfile;
import com.nageoffer.ai.ragent.core.parser.registry.ParserRegistry;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HtmlDocumentParser 单元测试：全部用自建最小 HTML fixture，不依赖网络与第三方网页源码；
 * 断言落在 Block 产物上（结构语义），不落在 chunk 文本上（那是 chunker 的职责）
 */
@DisplayName("HtmlDocumentParser 结构化解析")
class HtmlDocumentParserTest {

    private final HtmlDocumentParser parser = new HtmlDocumentParser();

    private ParsedDocument parse(String html) {
        return parser.parseStructured(html.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8",
                Map.of("sourceFile", "fixture.html"));
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

    private String allText(ParsedDocument doc) {
        StringBuilder sb = new StringBuilder();
        for (Block block : doc.blocks()) {
            if (block instanceof HeadingBlock h) {
                sb.append(h.text()).append('\n');
            } else if (block instanceof ParagraphBlock p) {
                sb.append(p.text()).append('\n');
            } else if (block instanceof ListBlock l) {
                l.items().forEach(i -> sb.append(i).append('\n'));
            } else if (block instanceof TableBlock t) {
                t.headers().forEach(h -> sb.append(h).append('\n'));
                t.rows().forEach(r -> sb.append(String.join("|", r)).append('\n'));
            }
        }
        return sb.toString();
    }

    // ==================== 场景 1：导航/页头/页脚/模板删除 ====================

    @Test
    @DisplayName("页头、页脚、导航、面包屑、分享栏、skip 链接、cookie 提示全部删除，正文保留")
    void stripsChromeOutsideAndInsideMain() {
        String html = """
                <!DOCTYPE html><html><head><title>Site</title><style>.a{}</style></head>
                <body>
                <a href="#main" class="skip-link">Skip to main content</a>
                <div id="cookie-consent">We use cookies</div>
                <header class="page-head"><nav><ul><li><a href="/">Home</a></li><li><a href="/find">Find</a></li></ul></nav></header>
                <div class="breadcrumb"><a href="/">Home</a> / Services</div>
                <main id="maincontainer" role="main">
                  <div class="side-menu"><ul><li><a href="/services/a">Service A</a></li></ul></div>
                  <article>
                    <div class="region region-content">
                      <h2>Renew &amp; Request</h2>
                      <p>You can renew your loans online via myRecord on the Library homepage.</p>
                    </div>
                  </article>
                </main>
                <div class="fn-blk share-blk"><button>Share</button></div>
                <footer class="page-foot"><p>Copyright PolyU Library</p></footer>
                </body></html>
                """;
        ParsedDocument doc = parse(html);
        String text = allText(doc);

        assertThat(text).contains("renew your loans online via myRecord");
        assertThat(text).doesNotContain("Skip to main content")
                .doesNotContain("We use cookies")
                .doesNotContain("Service A")
                .doesNotContain("Home")
                .doesNotContain("Share")
                .doesNotContain("Copyright");
        assertThat(doc.metadata()).containsEntry("contentRegion", "main#maincontainer");
    }

    // ==================== 场景 2：主体内容选择优先级 ====================

    @Test
    @DisplayName("main 优先于 article 与 region-content，正文从 main 提取")
    void prefersMainOverOtherRegions() {
        String html = """
                <html><body>
                <div class="region-content"><p>Region content text that is long enough to be selected as content region.</p></div>
                <main><p>Main content is the primary region for extraction, and this sentence is padded to exceed the minimum length threshold of eighty characters.</p></main>
                </body></html>
                """;
        ParsedDocument doc = parse(html);
        assertThat(allText(doc)).contains("Main content is the primary region");
        assertThat(allText(doc)).doesNotContain("Drupal region content").doesNotContain("Region content text");
    }

    @Test
    @DisplayName("无 main 时回退 article，再退 region-content")
    void fallsBackThroughSelectors() {
        String html = """
                <html><body>
                <div><p>Ignored sidebar text.</p></div>
                <div class="region-content"><p>Drupal region content text here.</p></div>
                <article><p>Article body is preferred when main is absent.</p></article>
                </body></html>
                """;
        ParsedDocument doc = parse(html);
        assertThat(allText(doc)).contains("Article body is preferred");
        assertThat(allText(doc)).doesNotContain("Drupal region content");
    }

    // ==================== 场景 8：主体选择器不存在时的安全回退 ====================

    @Test
    @DisplayName("无任何候选选择器时回退 body，body 顶层页头页脚删除而正文保留")
    void fallsBackToBodyAndStripsTopLevelChrome() {
        String html = """
                <html><body>
                <header><nav><a href="/menu">Global Menu</a></nav></header>
                <div class="content-wrapper"><h1>Fallback Page</h1><p>Body fallback keeps the real content text.</p></div>
                <footer><p>Footer text</p></footer>
                </body></html>
                """;
        ParsedDocument doc = parse(html);
        String text = allText(doc);
        assertThat(text).contains("Body fallback keeps the real content");
        assertThat(text).doesNotContain("Global Menu").doesNotContain("Footer text");
        assertThat(doc.metadata()).containsEntry("contentRegion", "body(fallback)");
    }

    // ==================== 场景 3：标题层级 ====================

    @Test
    @DisplayName("h1–h6 逐级产 HeadingBlock，级别与文本保真")
    void preservesHeadingLevels() {
        String html = """
                <html><body><main>
                <h1>Level One</h1><h2>Level Two</h2><h3>Level Three</h3>
                <h4>Level Four</h4><h5>Level Five</h5><h6>Level Six</h6>
                <p>Body paragraph after headings.</p>
                </main></body></html>
                """;
        List<HeadingBlock> hs = headings(parse(html));
        assertThat(hs).extracting(HeadingBlock::level).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(hs).extracting(HeadingBlock::text)
                .containsExactly("Level One", "Level Two", "Level Three", "Level Four", "Level Five", "Level Six");
    }

    // ==================== 场景 4：有序与无序列表 ====================

    @Test
    @DisplayName("ol 产有序 ListBlock、ul 产无序 ListBlock，项序保留")
    void preservesOrderedAndUnorderedLists() {
        String html = """
                <html><body><main>
                <h2>Request steps</h2>
                <ol>
                  <li>Search the item in the catalogue</li>
                  <li>Click the Request button</li>
                  <li>Collect the item from the loan counter</li>
                </ol>
                <h2>Non-renewable items</h2>
                <ul>
                  <li>Reserve items</li>
                  <li>AV items on 3-hour loan</li>
                </ul>
                </main></body></html>
                """;
        List<ListBlock> ls = lists(parse(html));
        assertThat(ls).hasSize(2);
        assertThat(ls.get(0).ordered()).isTrue();
        assertThat(ls.get(0).items()).containsExactly(
                "Search the item in the catalogue",
                "Click the Request button",
                "Collect the item from the loan counter");
        assertThat(ls.get(1).ordered()).isFalse();
        assertThat(ls.get(1).items()).containsExactly("Reserve items", "AV items on 3-hour loan");
    }

    // ==================== 场景 5：FAQ 问答关联 ====================

    @Test
    @DisplayName("FAQ 问句段落升格为标题块，答案保持普通段落，问答相邻且可关联")
    void promotesQuestionParagraphsToHeadings() {
        String html = """
                <html><body><main>
                <h2>Off-campus Access FAQ</h2>
                <p>Any setup requirement for my PC at home?</p>
                <p>You will need an Internet connection and a browser to access EZproxy. No client software is required.</p>
                <p>I can't log in with my username and password. What do I do?</p>
                <p>Please check your NetID and NetPassword, or call the ITS helpdesk for assistance.</p>
                </main></body></html>
                """;
        ParsedDocument doc = parse(html);
        List<HeadingBlock> hs = headings(doc);
        List<ParagraphBlock> ps = paragraphs(doc);

        assertThat(hs).extracting(HeadingBlock::text).containsExactly(
                "Off-campus Access FAQ",
                "Any setup requirement for my PC at home?",
                "I can't log in with my username and password. What do I do?");
        // 两问同级（基于 h2 基准升 1 级），答案块紧随其后
        assertThat(hs.get(1).level()).isEqualTo(hs.get(2).level());
        assertThat(ps).extracting(ParagraphBlock::text).containsExactly(
                "You will need an Internet connection and a browser to access EZproxy. No client software is required.",
                "Please check your NetID and NetPassword, or call the ITS helpdesk for assistance.");
    }

    // ==================== 场景 6：普通表格 ====================

    @Test
    @DisplayName("普通表格：thead 表头 + tbody 数据行，空单元格不引起列错位")
    void parsesPlainTable() {
        String html = """
                <html><body><main>
                <table>
                  <thead><tr><th>Loan item</th><th>Loan period</th><th>Maximum renewal</th></tr></thead>
                  <tbody>
                    <tr><td>AV items</td><td></td><td>21 days</td></tr>
                    <tr><td>Books</td><td>30 days</td><td>90 days</td></tr>
                  </tbody>
                </table>
                </main></body></html>
                """;
        List<TableBlock> ts = tables(parse(html));
        assertThat(ts).hasSize(1);
        TableBlock table = ts.get(0);
        assertThat(table.headers()).containsExactly("Loan item", "Loan period", "Maximum renewal");
        assertThat(table.rows()).hasSize(2);
        // 空单元格按列位对齐：AV items 行的第 2 列为空、第 3 列仍是 21 days
        assertThat(table.rows().get(0)).containsExactly("AV items", "", "21 days");
        assertThat(table.rows().get(1)).containsExactly("Books", "30 days", "90 days");
    }

    // ==================== 场景 7：多列组 / 分组表格 ====================

    @Test
    @DisplayName("rowspan 行头展开填充：组名出现在覆盖的每一行")
    void expandsRowspanIntoEveryCoveredRow() {
        String html = """
                <html><body><main>
                <table>
                  <thead><tr><th>Loan item</th><th>Loan period</th><th>Max renewal</th></tr></thead>
                  <tbody>
                    <tr><td rowspan="2">Books</td><td>1 term</td><td>N/A</td></tr>
                    <tr><td>28 days</td><td>84 days</td></tr>
                    <tr><td rowspan="2">HKALL books</td><td>15 days</td><td>45 days</td></tr>
                    <tr><td>30 days</td><td>90 days</td></tr>
                  </tbody>
                </table>
                </main></body></html>
                """;
        List<TableBlock> ts = tables(parse(html));
        assertThat(ts).hasSize(1);
        List<List<String>> rows = ts.get(0).rows();
        assertThat(rows).hasSize(4);
        assertThat(rows.get(0)).containsExactly("Books", "1 term", "N/A");
        assertThat(rows.get(1)).containsExactly("Books", "28 days", "84 days");
        assertThat(rows.get(2)).containsExactly("HKALL books", "15 days", "45 days");
        assertThat(rows.get(3)).containsExactly("HKALL books", "30 days", "90 days");
    }

    @Test
    @DisplayName("分组行头（全宽 colspan 标题单元格）并入后续每行首列，表头前插 Group 列")
    void mergesGroupHeaderRowIntoFollowingRows() {
        // PolyU 开放时间表形态：无 thead，首行全宽 h6 学期标题，次行 h6 列名，正文 td 内空首格
        String html = """
                <html><body><main>
                <table>
                  <tbody>
                    <tr><td colspan="3"><h6>Term Time 31 Aug 2026 - 22 Nov 2026</h6></td></tr>
                    <tr><td>&nbsp;</td><td><h6>Library Opening Hours</h6></td><td><h6>P/F Service Counter Hours</h6></td></tr>
                    <tr><td>Mon - Fri</td><td>08:30 - 23:00</td><td>09:00 - 21:00</td></tr>
                    <tr><td>Sun</td><td>12:00 - 23:00</td><td>12:00 - 19:00</td></tr>
                  </tbody>
                </table>
                </main></body></html>
                """;
        List<TableBlock> ts = tables(parse(html));
        assertThat(ts).hasSize(1);
        TableBlock table = ts.get(0);
        assertThat(table.headers()).containsExactly(
                "Group", "", "Library Opening Hours", "P/F Service Counter Hours");
        assertThat(table.rows()).hasSize(2);
        assertThat(table.rows().get(0)).containsExactly(
                "Term Time 31 Aug 2026 - 22 Nov 2026", "Mon - Fri", "08:30 - 23:00", "09:00 - 21:00");
        assertThat(table.rows().get(1)).containsExactly(
                "Term Time 31 Aug 2026 - 22 Nov 2026", "Sun", "12:00 - 23:00", "12:00 - 19:00");
    }

    @Test
    @DisplayName("无 thead 时首行全 th 判表头（借期大表形态），rowspan+colspan 交叉展开")
    void expandsComplexLoanTable() {
        // PolyU 借期大表形态：tbody 首行 7 个 th，数据区多重 rowspan/colspan 交叉
        String html = """
                <html><body><main>
                <table>
                  <tbody>
                    <tr><th>User Type</th><th>Material Type</th><th>Loan Period</th><th>Loan Quota</th><th>Hold</th><th>Maximum Renewal</th><th>Fines</th></tr>
                    <tr>
                      <td rowspan="2">Undergraduate Students</td>
                      <td>Books</td><td>28 days</td><td>30</td>
                      <td rowspan="2">10</td><td>84 days</td><td>$2/day</td>
                    </tr>
                    <tr><td>AV Items</td><td>7 days</td><td>5</td><td>N/A</td><td>$2/hour</td></tr>
                    <tr>
                      <td colspan="2">Interlibrary Loan</td><td>15 days</td>
                      <td colspan="2" rowspan="1">15</td><td>$2/day</td>
                    </tr>
                  </tbody>
                </table>
                </main></body></html>
                """;
        List<TableBlock> ts = tables(parse(html));
        assertThat(ts).hasSize(1);
        TableBlock table = ts.get(0);
        assertThat(table.headers()).containsExactly(
                "User Type", "Material Type", "Loan Period", "Loan Quota", "Hold", "Maximum Renewal", "Fines");
        List<List<String>> rows = table.rows();
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).containsExactly(
                "Undergraduate Students", "Books", "28 days", "30", "10", "84 days", "$2/day");
        assertThat(rows.get(1)).containsExactly(
                "Undergraduate Students", "AV Items", "7 days", "5", "10", "N/A", "$2/hour");
        assertThat(rows.get(2)).containsExactly(
                "Interlibrary Loan", "", "15 days", "15", "", "$2/day");
    }

    @Test
    @DisplayName("数据行的 colspan 单元格不误判为分组行头")
    void doesNotTreatDataColspanRowAsGroup() {
        // PolyU 价格表形态：Scanning 行首格 colspan=2 但行内还有 Size/Price 值
        String html = """
                <html><body><main>
                <table>
                  <tbody>
                    <tr><th>Services</th><th colspan="1"></th><th>Size/Unit</th><th>Unit Price</th></tr>
                    <tr><td rowspan="2">Photocopying</td><td>Normal size</td><td>A4</td><td>$0.2</td></tr>
                    <tr><td colspan="2">Colour Scanning</td><td>Size up to A0</td><td>Free of Charge</td></tr>
                  </tbody>
                </table>
                </main></body></html>
                """;
        List<TableBlock> ts = tables(parse(html));
        assertThat(ts).hasSize(1);
        List<List<String>> rows = ts.get(0).rows();
        assertThat(rows).hasSize(2);
        // colspan=2 首格落值、次格留空，后两列正常对齐
        assertThat(rows.get(1)).containsExactly("Photocopying", "Colour Scanning", "", "Size up to A0", "Free of Charge");
    }

    @Test
    @DisplayName("单列表格不把数据行判为全宽组头，行内容不丢失")
    void keepsSingleColumnTableRows() {
        // 列数为一时任何单元格 colspan 都不小于全宽，曾被全判分组行头导致数据行尽数丢弃
        String html = """
                <html><body><main>
                <p>Padding paragraph so the content region passes the minimum text threshold.</p>
                <table><tbody>
                  <tr><td>Alpha row content</td></tr>
                  <tr><td>Beta row content</td></tr>
                </tbody></table>
                </main></body></html>
                """;
        List<TableBlock> ts = tables(parse(html));
        assertThat(ts).hasSize(1);
        assertThat(ts.get(0).rows()).containsExactly(
                List.of("Alpha row content"), List.of("Beta row content"));
    }

    @Test
    @DisplayName("li 内嵌套列表的文本并入该项，不丢失内容")
    void keepsNestedListTextInsideItem() {
        String html = """
                <html><body><main>
                <ul>
                  <li>Renew in person
                    <ul><li>Bring your student card</li><li>Visit the loan counter</li></ul>
                  </li>
                  <li>Renew online</li>
                </ul>
                </main></body></html>
                """;
        List<ListBlock> ls = lists(parse(html));
        assertThat(ls).hasSize(1);
        assertThat(ls.get(0).items()).hasSize(2);
        assertThat(ls.get(0).items().get(0)).contains("Renew in person", "Bring your student card", "Visit the loan counter");
        assertThat(ls.get(0).items().get(1)).isEqualTo("Renew online");
    }

    // ==================== dl/dt/dd ====================

    @Test
    @DisplayName("dl 术语与解释合并成段，关系不拆散")
    void mergesDefinitionTermsWithDescriptions() {
        String html = """
                <html><body><main>
                <dl>
                  <dt>NetID</dt><dd>The staff/student account identifier</dd>
                  <dt>HKALL</dt><dd>Hong Kong Academic Library Link</dd><dd>A joint loan service</dd>
                </dl>
                </main></body></html>
                """;
        List<ParagraphBlock> ps = paragraphs(parse(html));
        assertThat(ps).extracting(ParagraphBlock::text).containsExactly(
                "NetID: The staff/student account identifier",
                "HKALL: Hong Kong Academic Library Link; A joint loan service");
    }

    // ==================== 场景 9：空页面 / 纯导航页 ====================

    @Test
    @DisplayName("空内容返回空 Block 列表")
    void returnsEmptyBlocksForEmptyContent() {
        assertThat(parser.parseStructured(new byte[0], "text/html", null).blocks()).isEmpty();
    }

    @Test
    @DisplayName("纯导航页（无正文）产出为空或接近空，不产出导航文本")
    void returnsNoBlocksForNavigationOnlyPage() {
        String html = """
                <html><body>
                <header><nav><a href="/">Home</a><a href="/find">Find</a></nav></header>
                <main role="main"><nav class="main-menu"><a href="/a">A</a><a href="/b">B</a></nav></main>
                <footer><p>Footer</p></footer>
                </body></html>
                """;
        ParsedDocument doc = parse(html);
        assertThat(allText(doc)).doesNotContain("Home").doesNotContain("Footer").doesNotContain("/a");
    }

    // ==================== 场景 10：实体、特殊字符、繁简中文 ====================

    @Test
    @DisplayName("HTML 实体、NBSP、引号与繁简中文不乱码")
    void keepsEntitiesAndChineseIntact() {
        String html = """
                <html><head><meta charset="utf-8"></head><body><main>
                <h2>館藏借用服務</h2>
                <p>借閱期限為 28 天&amp;續借上限 84 天&nbsp;（本科生）</p>
                <p>“智能卡”與‘校園’— 印表機 A4 每頁 $1.5</p>
                <p>简体中文测试:续借图书与预约服务。</p>
                </main></body></html>
                """;
        String text = allText(parse(html));
        assertThat(text).contains("館藏借用服務");
        assertThat(text).contains("借閱期限為 28 天&續借上限 84 天 （本科生）");
        assertThat(text).contains("“智能卡”與‘校園’— 印表機 A4 每頁 $1.5");
        assertThat(text).contains("简体中文测试:续借图书与预约服务。");
    }

    @Test
    @DisplayName("GBK 声明编码的页面按 meta charset 正确解码")
    void reReadsEncodingFromMetaCharset() {
        // GBK 编码字节（「香港理工大學圖書館」），由 meta charset 声明触发 Jsoup 重读
        byte[] gbkBytes = ("<html><head><meta charset=\"GBK\"></head><body><main>"
                + "<p>香港理工大學圖書館開放時間</p>"
                + "<p>這是一段足夠長的繁體中文內容用於通過主體區域的文本量下限檢查。</p>"
                + "</main></body></html>").getBytes(java.nio.charset.Charset.forName("GBK"));
        ParsedDocument doc = parser.parseStructured(gbkBytes, "text/html", null);
        assertThat(allText(doc)).contains("香港理工大學圖書館開放時間");
    }

    // ==================== 场景 11：ParserRegistry 路由唯一且确定 ====================

    @Test
    @DisplayName("注册表路由：text/html 与 xhtml 唯一命中 HtmlDocumentParser，键冲突启动失败")
    void registryRoutesHtmlUniquely() {
        HtmlDocumentParser html = new HtmlDocumentParser();
        TikaDocumentParser tika = new TikaDocumentParser();
        MarkdownDocumentParser markdown = new MarkdownDocumentParser();
        ParserRegistry registry = new ParserRegistry(List.of(tika, html, markdown));

        assertThat(registry.require("text/html", ParseProfile.FAST)).isSameAs(html);
        assertThat(registry.require("application/xhtml+xml", ParseProfile.FAST)).isSameAs(html);
        // 同一 MIME 带 charset 参数也能命中
        assertThat(registry.require("text/html; charset=utf-8", ParseProfile.FAST)).isSameAs(html);
        // HTML 的认领从 Tika 让渡后，Tika 仍兜 text/* 长尾但精确键不再覆盖 HTML
        assertThat(registry.find("text/rtf-latin1-literal", ParseProfile.FAST)).containsSame(tika);
        // Markdown 与纯文本不受影响
        assertThat(registry.require("text/plain", ParseProfile.FAST)).isSameAs(markdown);
        assertThat(registry.require("text/x-web-markdown", ParseProfile.FAST)).isSameAs(markdown);

        // 双注册同一 MIME：注册表显式报键冲突（不静默覆盖）
        TikaDocumentParser legacyClaim = new TikaDocumentParser() {
            @Override
            public Map<ParseProfile, java.util.Set<String>> supportedMimeTypes() {
                return Map.of(ParseProfile.FAST, java.util.Set.of("text/html"));
            }
        };
        assertThatThrownBy(() -> new ParserRegistry(List.of(html, legacyClaim)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("text/html");
    }

    // ==================== 场景 12：Tika / Markdown 解析器不回归 ====================

    @Test
    @DisplayName("Tika 对纯文本/JSON/RTF 的解析行为不变")
    void tikaStillParsesPlainFormats() {
        TikaDocumentParser tika = new TikaDocumentParser();
        ParsedDocument text = tika.parseStructured("First para.\n\nSecond para.".getBytes(StandardCharsets.UTF_8),
                "text/plain", null);
        assertThat(text.blocks()).hasSize(2);
        assertThat(text.blocks().get(0)).isInstanceOf(ParagraphBlock.class);

        ParsedDocument json = tika.parseStructured("{\"a\": 1}".getBytes(StandardCharsets.UTF_8),
                "application/json", null);
        assertThat(json.blocks()).isNotEmpty();
    }

    @Test
    @DisplayName("Markdown 解析器产 Block 行为不变（标题/列表/表格）")
    void markdownParserUnaffected() {
        MarkdownDocumentParser markdown = new MarkdownDocumentParser();
        String md = "# Title\n\nParagraph one.\n\n- item a\n- item b\n\n| H1 | H2 |\n| --- | --- |\n| a | b |\n";
        ParsedDocument doc = markdown.parseStructured(md.getBytes(StandardCharsets.UTF_8), "text/x-web-markdown", null);
        assertThat(doc.blocks()).isNotEmpty();
        assertThat(doc.blocks().stream().anyMatch(b -> b instanceof HeadingBlock)).isTrue();
        assertThat(doc.blocks().stream().anyMatch(b -> b instanceof ListBlock)).isTrue();
        assertThat(doc.blocks().stream().anyMatch(b -> b instanceof TableBlock)).isTrue();
    }

    // ==================== 补充：details/figure/嵌套与链接保留 ====================

    @Test
    @DisplayName("details/summary 折叠问答：问题成标题、答案成段落且不重复")
    void handlesDetailsSummary() {
        String html = """
                <html><body><main>
                <details><summary>How do I book a discussion room?</summary>
                  <p>Book online via the Library space booking system.</p>
                </details>
                </main></body></html>
                """;
        ParsedDocument doc = parse(html);
        assertThat(headings(doc)).extracting(HeadingBlock::text)
                .containsExactly("How do I book a discussion room?");
        assertThat(paragraphs(doc)).extracting(ParagraphBlock::text)
                .containsExactly("Book online via the Library space booking system.");
    }

    @Test
    @DisplayName("正文中的业务链接文字保留")
    void keepsBusinessLinkTexts() {
        String html = """
                <html><body><main>
                <p>Visit the <a href="/services/borrowing">borrowing services</a> page for details,
                or see <a href="/facilities/printing">printing services</a>.</p>
                </main></body></html>
                """;
        assertThat(allText(parse(html)))
                .contains("borrowing services")
                .contains("printing services");
    }

    @Test
    @DisplayName("主体中的提示框（alert）与联系方式保留，不因清洗误删")
    void keepsAlertsAndContactInfoInMain() {
        String html = """
                <html><body><main>
                <div class="alert alert-warning" role="alert"><p>The Library will close early on 1 Oct.</p></div>
                <p>Contact the service counter at libemail@polyu.edu.hk or (852) 2766-5900.</p>
                </main></body></html>
                """;
        String text = allText(parse(html));
        assertThat(text).contains("The Library will close early on 1 Oct.");
        assertThat(text).contains("(852) 2766-5900");
    }

    @Test
    @DisplayName("嵌套在表格单元格中的链接文字并入单元格文本")
    void keepsLinkTextInsideTableCells() {
        String html = """
                <html><body><main>
                <table><tbody>
                  <tr><th>Service</th><th>Price</th></tr>
                  <tr><td><a href="/scanning">Colour Scanning</a></td><td>Free of Charge</td></tr>
                </tbody></table>
                </main></body></html>
                """;
        List<TableBlock> ts = tables(parse(html));
        assertThat(ts.get(0).rows().get(0)).containsExactly("Colour Scanning", "Free of Charge");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<html><body><main><p>Only text no tags closing",
            "<html><body><main><div><div><p>Deep nesting</p></div></div></main></body></html>",
            "<html><body><main><table><tr><td>rowless table</td></tr></table></main></body></html>"
    })
    @DisplayName("畸形/非规范 HTML 不抛异常")
    void toleratesMalformedHtml(String html) {
        ParsedDocument doc = parser.parseStructured(html.getBytes(StandardCharsets.UTF_8), "text/html", null);
        assertThat(doc).isNotNull();
    }

    @Test
    @DisplayName("认领清单：HTML 的两个精确键、不含通配")
    void claimsExactMimeTypesOnly() {
        Map<ParseProfile, java.util.Set<String>> claims = parser.supportedMimeTypes();
        assertThat(claims).containsOnlyKeys(ParseProfile.FAST);
        assertThat(claims.get(ParseProfile.FAST)).containsExactlyInAnyOrder("text/html", "application/xhtml+xml");
    }

    // ==================== 回归：行内标签不切碎段落 / 截图空段落不打散列表 ====================

    @Test
    @DisplayName("行内加粗/链接边界不把句子切碎成多段")
    void doesNotSplitParagraphAtInlineTags() {
        String html = """
                <html><body><main>
                <div class="alert alert-warning" role="alert">The <b>Technical Support Desk</b> at 4/F i-Space
                and <b>WhatsApp Enquiry Service</b> are available during office hours, from 09:00 to 17:00 on weekdays.</div>
                </main></body></html>
                """;
        List<ParagraphBlock> ps = paragraphs(parse(html));
        assertThat(ps).hasSize(1);
        assertThat(ps.get(0).text()).isEqualTo(
                "The Technical Support Desk at 4/F i-Space and WhatsApp Enquiry Service are available during office hours, from 09:00 to 17:00 on weekdays.");
    }

    @Test
    @DisplayName("li 内仅装截图的空段落不触发富列表项，步骤列表保持 ListBlock")
    void keepsSimpleListWhenLiContainsOnlyImageParagraph() {
        String html = """
                <html><body><main>
                <ul>
                  <li>Find the item in the <strong><a href="/onesearch">OneSearch</a></strong> and click "SIGN IN".</li>
                  <li>Click "Request" under "Options".
                    <p><img alt="Screen dump" src="https://example.edu.hk/request.png"/></p>
                  </li>
                  <li>Click "REQUEST" to confirm.</li>
                </ul>
                </main></body></html>
                """;
        List<ListBlock> ls = lists(parse(html));
        assertThat(ls).hasSize(1);
        assertThat(ls.get(0).ordered()).isFalse();
        assertThat(ls.get(0).items()).hasSize(3);
        assertThat(ls.get(0).items().get(0)).isEqualTo("Find the item in the OneSearch and click \"SIGN IN\".");
    }

    @Test
    @DisplayName("li 内问答段落触发富列表项：问题升标题、答案成段")
    void promotesFaqEntriesInsideRichListItems() {
        String html = """
                <html><body><main>
                <h2>Access FAQ</h2>
                <ol>
                  <li><p>Any setup requirement for my PC at home?</p><p>You need an Internet connection and a browser.</p></li>
                  <li><p>Cookie error message?</p><p>Change the browser back to the default setting to ACCEPT COOKIES.</p></li>
                </ol>
                </main></body></html>
                """;
        ParsedDocument doc = parse(html);
        assertThat(headings(doc)).extracting(HeadingBlock::text).containsExactly(
                "Access FAQ", "Any setup requirement for my PC at home?", "Cookie error message?");
        assertThat(paragraphs(doc)).extracting(ParagraphBlock::text).containsExactly(
                "You need an Internet connection and a browser.",
                "Change the browser back to the default setting to ACCEPT COOKIES.");
    }

    @Test
    @DisplayName("book-navigation 上一页/下一页分页导航删除")
    void stripsBookNavigationPager() {
        String html = """
                <html><body><main>
                <div class="region-content"><p>Main body content of the current book page goes here.</p></div>
                <div id="book-navigation-1250" class="book-navigation">
                  <div class="page-links clearfix">
                    <a href="/prev" class="page-previous">‹ Previous Section</a>
                    <a href="/up" class="page-up">up</a>
                    <a href="/next" class="page-next">Next Section ›</a>
                  </div>
                </div>
                </main></body></html>
                """;
        String text = allText(parse(html));
        assertThat(text).contains("Main body content of the current book page");
        assertThat(text).doesNotContain("Previous Section").doesNotContain("Next Section");
    }

    // ==================== 回归：列表内非 li 子节点 / 手风琴触发升格（S6 宿费政策、A4 FAQ 判例） ====================

    @Test
    @DisplayName("ol 内非 li 子节点（span 分节标签/p 政策段）不再丢弃，按 DOM 序常规产出")
    void keepsNonLiChildrenInsideLists() {
        // S6 宿费页形态：CKEditor 把 span 分节标签与整段政策文本直接挂进 ol
        String html = """
                <html><body><main>
                <h2>Hall Fees</h2>
                <ol style="margin-top: 0.470588em;">
                  <span style="font-size: 1em;">B) Hall Caution Money ($900)</span>
                  <p style="margin-left: 40px;">All students are required to pay the Hall Caution Money upon the acceptance of the offer of hall residence.</p>
                  <li>It will be forfeited if the hall residence is terminated under the Hall Regulations.</li>
                </ol>
                </main></body></html>
                """;
        ParsedDocument doc = parse(html);
        assertThat(paragraphs(doc)).extracting(ParagraphBlock::text).containsExactly(
                "B) Hall Caution Money ($900)",
                "All students are required to pay the Hall Caution Money upon the acceptance of the offer of hall residence.");
        assertThat(lists(doc)).hasSize(1);
        assertThat(lists(doc).get(0).ordered()).isTrue();
        assertThat(lists(doc).get(0).items())
                .containsExactly("It will be forfeited if the hall residence is terminated under the Hall Regulations.");
        // DOM 序：span 段 → p 段 → 列表项
        assertThat(doc.blocks()).extracting(b -> b.getClass().getSimpleName())
                .containsExactly("HeadingBlock", "ParagraphBlock", "ParagraphBlock", "ListBlock");
    }

    @Test
    @DisplayName("li 与非 li 子节点交错时列表在段落处切开，DOM 序保持")
    void splitsListAtNonLiChildPreservingDomOrder() {
        String html = """
                <html><body><main>
                <ol>
                  <li>First item of the ordered list</li>
                  <p>Interstitial policy paragraph pasted directly inside the list by the campus editor.</p>
                  <li>Second item of the ordered list</li>
                </ol>
                </main></body></html>
                """;
        ParsedDocument doc = parse(html);
        assertThat(lists(doc)).hasSize(2);
        assertThat(lists(doc).get(0).items()).containsExactly("First item of the ordered list");
        assertThat(lists(doc).get(1).items()).containsExactly("Second item of the ordered list");
        assertThat(paragraphs(doc)).extracting(ParagraphBlock::text)
                .containsExactly("Interstitial policy paragraph pasted directly inside the list by the campus editor.");
        assertThat(doc.blocks()).extracting(b -> b.getClass().getSimpleName())
                .containsExactly("ListBlock", "ParagraphBlock", "ListBlock");
    }

    @Test
    @DisplayName("a[data-toggle=collapse] 手风琴触发升格标题（不论文本是否问句），普通链接不升格")
    void promotesAccordionTriggersToHeadings() {
        String html = """
                <html><body><main>
                <h2>Non-local Students</h2>
                <div class="collapse-wrap">
                  <div class="plus-collapse__header">
                    <a aria-expanded="false" class="plus-collapse__trigger" data-toggle="collapse" href="#!" role="button">What is the definition of a non-local student?</a>
                  </div>
                  <div class="collapse plus-collapse__content">
                    <div class="plus-collapse__inner"><p>A non-local student is a person who needs a student visa to study in Hong Kong.</p></div>
                  </div>
                </div>
                <div class="collapse-wrap">
                  <div class="plus-collapse__header">
                    <a aria-expanded="false" class="plus-collapse__trigger" data-toggle="collapse" href="#!" role="button">Entry Scholarships - awarded on the basis of admission results</a>
                  </div>
                  <div class="collapse plus-collapse__content">
                    <div class="plus-collapse__inner"><p>Details of the scholarship scheme are published each year.</p></div>
                  </div>
                </div>
                <p>Read the <a href="/ar/admissions/">admissions pages</a> for the full policy.</p>
                </main></body></html>
                """;
        ParsedDocument doc = parse(html);
        List<HeadingBlock> hs = headings(doc);
        assertThat(hs).extracting(HeadingBlock::text).containsExactly(
                "Non-local Students",
                "What is the definition of a non-local student?",
                "Entry Scholarships - awarded on the basis of admission results");
        // 两个触发同级（h2 基准升 1 级），问句（A4 形态）与非问句（S4 分节形态）一视同仁
        assertThat(hs.get(1).level()).isEqualTo(3);
        assertThat(hs.get(1).level()).isEqualTo(hs.get(2).level());
        assertThat(paragraphs(doc)).extracting(ParagraphBlock::text).containsExactly(
                "A non-local student is a person who needs a student visa to study in Hong Kong.",
                "Details of the scholarship scheme are published each year.",
                "Read the admissions pages for the full policy.");
    }
}

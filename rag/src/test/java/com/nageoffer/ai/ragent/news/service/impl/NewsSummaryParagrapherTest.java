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

package com.nageoffer.ai.ragent.news.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewsSummaryParagrapherTest {

    @Test
    void shouldInsertParagraphBreaksForZhWallOfText() {
        String zh = "第一句交代核心事实。第二句补充背景与参与方。第三句给出关键数字与条件。第四句说明影响。第五句收尾。";
        String out = NewsSummaryParagrapher.reflow(zh);
        assertEquals(zh.replace("\n\n", ""), out.replace("\n\n", ""));
        assertEquals(zh.split("。")[0] + "。", out.substring(0, out.indexOf("\n\n")));
        assertTrue(out.startsWith("第一句交代核心事实。\n\n"));
        assertEquals(3, out.split("\n\n").length);
    }

    @Test
    void shouldInsertParagraphBreaksForEnWallOfText() {
        String en = "Lead sentence states the fact. Second sentence adds background detail. Third sentence gives numbers. "
                + "Fourth sentence notes impact. Fifth sentence closes the item.";
        String out = NewsSummaryParagrapher.reflow(en);
        assertEquals(en, out.replace("\n\n", ""));
        // 句间空格留在段尾（与存量重排 SQL 同形，whitespace-pre-line 渲染不可见）
        assertTrue(out.startsWith("Lead sentence states the fact. \n\n"));
    }

    @Test
    void shouldKeepModelParagraphingUntouched() {
        String already = "首句。\n\n其余两句。保持原样。";
        assertEquals(already, NewsSummaryParagrapher.reflow(already));
    }

    @Test
    void shouldNotForceShortText() {
        assertEquals("一句话。两句话。", NewsSummaryParagrapher.reflow("一句话。两句话。"));
        assertEquals("One. Two.", NewsSummaryParagrapher.reflow("One. Two."));
        assertNull(NewsSummaryParagrapher.reflow(null));
    }

    @Test
    void shouldReflowThreeSentencesIntoTwoParagraphs() {
        String three = "首句导语。第二句正文。第三句正文。";
        String out = NewsSummaryParagrapher.reflow(three);
        assertEquals("首句导语。\n\n第二句正文。第三句正文。", out);
    }
}

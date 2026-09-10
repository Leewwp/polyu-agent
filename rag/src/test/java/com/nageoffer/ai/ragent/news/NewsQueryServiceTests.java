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

package com.nageoffer.ai.ragent.news;

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.news.controller.vo.NewsItemVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsPageVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsTopicVO;
import com.nageoffer.ai.ragent.news.service.NewsQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * U12-A 公开资讯查询服务测试（A2 骨架面）
 *
 * <p>跑在 polyu 本地栈（PG 5434，测试隔离层固定 workflow 档）。断言只锚定
 * 不依赖库内容快照的形状语义——空库返回空列表、不存在 category 一定空、
 * 不存在 slug 一定 404 语义、分页参数钳制——真实抓取入位后的端到端验证归 A6。
 */
@SpringBootTest
class NewsQueryServiceTests {

    /**
     * 不可能与固定 8 类或未来扩展类撞名的哨兵 category
     */
    private static final String NON_EXISTENT_CATEGORY = "definitely-nonexistent-category-a2";

    @Autowired
    private NewsQueryService newsQueryService;

    @Test
    void listWithNonExistentCategoryReturnsEmptyPage() {
        NewsPageVO page = newsQueryService.listPublished(NON_EXISTENT_CATEGORY, 1, 20);
        assertNotNull(page);
        assertTrue(page.getRecords().isEmpty());
        assertEquals(0L, page.getTotal());
        assertEquals(1L, page.getPage());
        assertEquals(20L, page.getSize());
        assertEquals(Boolean.FALSE, page.getHasMore());
    }

    @Test
    void listNormalizesOutOfRangePaging() {
        NewsPageVO zeroPage = newsQueryService.listPublished(null, 0, 0);
        assertTrue(zeroPage.getPage() >= 1);
        assertTrue(zeroPage.getSize() >= 1 && zeroPage.getSize() <= 50);
        NewsPageVO hugeSize = newsQueryService.listPublished(null, 1, Integer.MAX_VALUE);
        assertTrue(hugeSize.getSize() <= 50);
        // 越界页码：空记录且 hasMore 收敛为 false
        NewsPageVO farPage = newsQueryService.listPublished(NON_EXISTENT_CATEGORY, 9999, 20);
        assertTrue(farPage.getRecords().isEmpty());
        assertEquals(Boolean.FALSE, farPage.getHasMore());
    }

    @Test
    void hotReturnsBoundedList() {
        List<NewsItemVO> hot = newsQueryService.listHot(10);
        assertNotNull(hot);
        assertTrue(hot.size() <= 10);
    }

    @Test
    void topicsExposeOnlyCuratedGroups() {
        List<NewsTopicVO> topics = newsQueryService.listCuratedTopics();
        assertNotNull(topics);
        Set<String> slugs = new HashSet<>();
        for (NewsTopicVO topic : topics) {
            assertNotNull(topic.getSlug());
            slugs.add(topic.getSlug());
            assertNotNull(topic.getTopicGroup());
            assertTrue(topic.getTopicGroup().equals("FACULTY")
                    || topic.getTopicGroup().equals("RESEARCH")
                    || topic.getTopicGroup().equals("STUDENT_AFFAIRS"),
                    "非法三维分组：" + topic.getTopicGroup());
            assertNotNull(topic.getItemCount());
            assertTrue(topic.getItemCount() >= 0);
        }
        assertEquals(topics.size(), slugs.size(), "主题 slug 必须唯一");
    }

    @Test
    void topicDetailWithUnknownSlugThrows() {
        ClientException ex = assertThrows(ClientException.class,
                () -> newsQueryService.getTopicDetail("definitely-nonexistent-slug-a2", 1, 20));
        assertEquals("主题不存在", ex.getErrorMessage());
    }

    @Test
    void topicDetailWithBlankSlugThrows() {
        assertThrows(ClientException.class, () -> newsQueryService.getTopicDetail("  ", 1, 20));
    }
}

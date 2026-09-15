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

package com.nageoffer.ai.ragent.news.fetch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * robots.txt 解析测试（纪律面；语义移植自 scripts/crawl/fetch_sources.py
 * 的 parse_robots/is_disallowed 及其 tests/ 用例）
 */
class NewsRobotsTxtParserTests {

    private static final String UA = "polyuguide-feed/1.0 (+https://polyuguide.com)";

    @Test
    void starGroupDisallowPrefixMatches() {
        String robots = """
                User-agent: *
                Disallow: /search-result/dept/
                Disallow: /cpa/souvenirs/
                Sitemap: https://www.polyu.edu.hk/home.xml
                """;
        RobotsRules rules = RobotsTxtParser.parse(robots, UA);
        assertTrue(rules.disallows("/search-result/dept/ise/"));
        assertTrue(rules.disallows("/cpa/souvenirs/x"));
        assertFalse(rules.disallows("/media/media-releases/"));
        assertFalse(rules.disallows("/events/"));
        assertNull(rules.crawlDelaySeconds());
    }

    @Test
    void specificGroupBeatsStarGroup() {
        String robots = """
                User-agent: polyuguide-feed
                Disallow: /private/

                User-agent: *
                Disallow: /everything/
                """;
        RobotsRules rules = RobotsTxtParser.parse(robots, UA);
        assertTrue(rules.disallows("/private/a"));
        assertFalse(rules.disallows("/everything/a"));
    }

    @Test
    void emptyDisallowMeansAllowAll() {
        String robots = """
                User-agent: *
                Disallow:
                """;
        RobotsRules rules = RobotsTxtParser.parse(robots, UA);
        assertFalse(rules.disallows("/anything/at/all"));
    }

    @Test
    void crawlDelayIsConsumed() {
        String robots = """
                User-agent: *
                Crawl-delay: 15
                Disallow: /x/
                """;
        RobotsRules rules = RobotsTxtParser.parse(robots, UA);
        assertEquals(15.0, rules.crawlDelaySeconds());
    }

    @Test
    void newGroupStartsAfterRulesAndConsecutiveUserAgentLinesShareGroup() {
        String robots = """
                User-agent: a
                User-agent: b
                Disallow: /ab/

                User-agent: *
                Disallow: /star/
                """;
        RobotsRules asA = RobotsTxtParser.parse(robots, "a/9.9");
        assertTrue(asA.disallows("/ab/1"));
        assertFalse(asA.disallows("/star/1"));
        RobotsRules asStar = RobotsTxtParser.parse(robots, "someone-else/1");
        assertTrue(asStar.disallows("/star/1"));
        assertFalse(asStar.disallows("/ab/1"));
    }

    @Test
    void commentAndBlankLinesIgnored() {
        String robots = "# comment line\n\nUser-agent: *\nDisallow: /x/ # trailing comment\n";
        RobotsRules rules = RobotsTxtParser.parse(robots, UA);
        assertTrue(rules.disallows("/x/1"));
        assertFalse(rules.disallows("/y/1"));
    }
}

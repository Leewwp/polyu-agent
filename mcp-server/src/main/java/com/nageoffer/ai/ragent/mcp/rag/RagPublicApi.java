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

package com.nageoffer.ai.ragent.mcp.rag;

import java.time.LocalDate;
import java.util.List;

/**
 * rag 公开查询面（#214）：mcp-server 无数据库层（#164 裁剪决策），search_news /
 * query_key_dates 两个工具的数据访问全走该受限公开 API（SaToken 白名单免登录），
 * 不得绕过它直连库——下架/隐藏/未过门内容的隔离由公开面统一保证。
 *
 * <p>DTO 是 rag 出参的<b>消费子集</b>（只声明工具输出用得到的字段，日期一律保持
 * 原始字符串透传不重排；多余 JSON 字段忽略）。实现唯一：{@link RagPublicApiClient}。
 */
public interface RagPublicApi {

    /**
     * 资讯受限检索：关键词 × 主题 slug × 时间窗（端点含当日），发布时间倒序分页。
     * 调用方负责 limit 有界与参数校验；服务端另有 size ≤50 钳制兜底。
     */
    NewsPage searchNews(String keyword, String topic, LocalDate dateFrom, LocalDate dateTo, int page, int size);

    /**
     * 校历关键日期看板（#193 查询口原样）：临近度排序 + 过期归档分段 + 源三态。
     * 分段裁剪与关键词过滤在调用侧做（看板本身无参数）。
     */
    KeyDateBoard keyDateBoard();

    /**
     * rag 统一返回信封（framework Result）：code="0" 成功；业务失败 code 非 0 且
     * message 为面向用户的文案（可透传给模型）
     */
    record Envelope<T>(String code, String message, T data) {

        public boolean ok() {
            return "0".equals(code);
        }
    }

    /**
     * 资讯条目消费子集：publishTime 保持服务端原样字符串（ISO-8601），输出时取前 10 位
     */
    record NewsItem(Long id, String url, String titleZh, String titleEn,
                    String summaryZh, String summaryEn, String category, String publishTime) {
    }

    record NewsPage(List<NewsItem> records, Long total, Boolean hasMore) {

        public List<NewsItem> safeRecords() {
            return records == null ? List.of() : records;
        }
    }

    /**
     * 关键日期消费子集：dateStart/dateEnd/fuzzyHint/lastFullSyncAt/today 均为原始字符串。
     * audienceText 必须透传（#193 合同 §2：官方人群限制原文不得省略）
     */
    record KeyDate(String titleZh, String titleEn, String academicYear, String term, String precision,
                   String dateStart, String dateEnd, String fuzzyHint, String audienceText,
                   String sourceUrl, String phase, Integer daysUntil) {
    }

    record KeyDateBoard(String coverageAcademicYear, String lastFullSyncAt, String today,
                        Boolean anySourceAbnormal, List<KeyDate> currentAndUpcoming,
                        List<KeyDate> recentPast, List<KeyDate> archived, List<KeyDate> undated,
                        Long archivedTotal) {

        public List<KeyDate> safeSegment(List<KeyDate> segment) {
            return segment == null ? List.of() : segment;
        }
    }
}

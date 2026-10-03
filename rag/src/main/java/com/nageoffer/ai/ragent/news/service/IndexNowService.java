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

package com.nageoffer.ai.ragent.news.service;

import java.util.List;

/**
 * IndexNow 提交服务（#213，父票 #182 r3 §RSS、发现面——P2-b；票面已批该协议）。
 *
 * <p><b>只提交本站 canonical URL</b>：入参以站点相对路径表达（如 /daily、/news/179），
 * 服务内部用 {@code rag.news.site-base-url} 拼成绝对 URL——任何不以本站前缀开头的
 * 绝对 URL 一律丢弃（外部新闻原文 URL 绝不提交，IndexNow 是站点内容发现协议不是外链投递）。
 *
 * <p>分类处理：200/202=受理（202=部分受理语义，同为成功形态）；429=退避——
 * 进程内 24h 抑制窗（对齐日报日更节奏，窗内静默跳过，不重试风暴）；其他状态
 * 与传输失败=单次 WARN 不退避（下一自然触发点重试）。
 *
 * <p>收录效果=观察项（票面明示非验收门）；本服务失败不影响调用方主流程
 * （调用方为调度钩，异常吞噬在服务内完成）。
 */
public interface IndexNowService {

    /**
     * 提交本站 URL 集到 IndexNow。
     *
     * @param sitePaths 站点相对路径列表（以 / 开头）；绝对 URL 仅当匹配本站
     *                  canonical 前缀时放行，否则丢弃
     */
    void submitSiteUrls(List<String> sitePaths);
}

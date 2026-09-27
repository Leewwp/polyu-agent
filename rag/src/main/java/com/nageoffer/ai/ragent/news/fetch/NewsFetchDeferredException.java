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

/**
 * 本轮 defer（#152，审计 F-4）：源站声明的 Crawl-delay 有效值超过系统单次等待上限，
 * 本轮不请求该 host（不 sleep、不抓取，调度线程立即让出），到期后的后续轮次再抓。
 * <p>
 * 不是失败：不计入失败滞回、不触发重试（transient=false 防内联重试睡 30s）——
 * 源是健康的，我们在遵守它的节奏。轮次层（NewsFetchJob）对本类型单独豁免滞回
 */
public class NewsFetchDeferredException extends NewsFetchException {

    public NewsFetchDeferredException(String message) {
        super(message, false);
    }
}

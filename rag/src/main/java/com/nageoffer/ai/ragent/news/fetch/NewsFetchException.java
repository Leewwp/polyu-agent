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
 * 抓取失败（U12-A A3）。瞬时/永久二分沿 scripts/crawl/fetch_sources.py 纪律合同：
 * 瞬时（超时/连接/TLS/5xx/408/429）重试 1 次后仍失败才抛，永久（其余 4xx、DNS）不重试；
 * 两类失败都计入 consecutive_failures 滞回（K2c），只是日志语义不同。
 */
public class NewsFetchException extends RuntimeException {

    private final boolean transientError;

    public NewsFetchException(String message, boolean transientError, Throwable cause) {
        super(message, cause);
        this.transientError = transientError;
    }

    public NewsFetchException(String message, boolean transientError) {
        this(message, transientError, null);
    }

    /**
     * 是否瞬时错误（重试 1 次后仍失败）
     */
    public boolean isTransientError() {
        return transientError;
    }
}

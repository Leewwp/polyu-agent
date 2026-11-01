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

package com.nageoffer.ai.ragent.rag.service.preguard;

/**
 * 回答前守卫的一次判定结论：两链只消费 rejected 与 lang（拒绝模板语言跟随题面）
 */
public record GuardDecision(boolean rejected, String reasonFamily, String lang) {

    public static GuardDecision allow(String reasonFamily, String lang) {
        return new GuardDecision(false, reasonFamily, lang);
    }

    public static GuardDecision reject(String reasonFamily, String lang) {
        return new GuardDecision(true, reasonFamily, lang);
    }
}

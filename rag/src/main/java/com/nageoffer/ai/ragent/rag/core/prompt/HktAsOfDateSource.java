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

package com.nageoffer.ai.ragent.rag.core.prompt;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.function.Supplier;

/**
 * as-of 判定基准日来源（#413）：服务器 HKT 当前日期，与系统提示词 CurrentDateFact 同源同口径。
 * <p>
 * 以 {@code Supplier<LocalDate>} 注入组装层，格式化器不直接读系统时钟（时钟注入可测、判定可固定）；
 * 日界=香港时间零点，与容器 JVM 默认时区无关
 */
@Component
public class HktAsOfDateSource implements Supplier<LocalDate> {

    @Override
    public LocalDate get() {
        return LocalDate.now(CurrentDateFact.HKT);
    }
}

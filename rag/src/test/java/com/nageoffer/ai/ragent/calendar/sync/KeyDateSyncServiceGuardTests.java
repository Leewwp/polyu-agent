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

package com.nageoffer.ai.ragent.calendar.sync;

import com.nageoffer.ai.ragent.calendar.dao.entity.KeyDateSourceDO;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #195 审核修正 P3 护栏：MANUAL_DISABLED 仅内存态禁落库——auto_state 的 CHECK
 * 约束（ck_key_date_source_auto）只允许 active/auto_isolated，人工停用的落库
 * 表示是 enabled='0'；persist 前过滤，任何 Mode 落库值都不得违约。
 */
class KeyDateSyncServiceGuardTests {

    @Test
    void manualDisabledNeverPersistedToAutoState() {
        KeyDateSourceDO source = new KeyDateSourceDO();
        source.setAutoState("active");
        KeyDateSyncService.persistMachine(source, new KeyDateSourceStateMachine(
                KeyDateSourceStateMachine.Mode.MANUAL_DISABLED, 2, 1));
        assertEquals("active", source.getAutoState(),
                "manual_disabled 不落 auto_state（CHECK 约束外值），保持原值不动");
        assertEquals(2, source.getDegradedStreak(), "streak 计数照常落（无约束面）");
        assertEquals(1, source.getProbeOkStreak());
    }

    @Test
    void allModesPersistWithinCheckConstraintDomain() {
        Set<String> allowed = Set.of("active", "auto_isolated");
        for (KeyDateSourceStateMachine.Mode mode : KeyDateSourceStateMachine.Mode.values()) {
            KeyDateSourceDO source = new KeyDateSourceDO();
            source.setAutoState("active");
            KeyDateSyncService.persistMachine(source, new KeyDateSourceStateMachine(mode, 1, 0));
            assertTrue(allowed.contains(source.getAutoState()),
                    mode + " 落库值 '" + source.getAutoState() + "' 须满足 ck_key_date_source_auto");
        }
        // 合同态显式断言（非 MANUAL_DISABLED 路径行为不变）
        KeyDateSourceDO source = new KeyDateSourceDO();
        source.setAutoState("active");
        KeyDateSyncService.persistMachine(source, new KeyDateSourceStateMachine(
                KeyDateSourceStateMachine.Mode.AUTO_ISOLATED, 3, 0));
        assertEquals("auto_isolated", source.getAutoState());
        KeyDateSyncService.persistMachine(source, new KeyDateSourceStateMachine(
                KeyDateSourceStateMachine.Mode.ACTIVE, 0, 2));
        assertEquals("active", source.getAutoState());
    }
}

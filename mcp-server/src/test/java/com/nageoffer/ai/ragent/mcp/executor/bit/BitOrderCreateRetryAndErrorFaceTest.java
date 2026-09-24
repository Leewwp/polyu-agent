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

package com.nageoffer.ai.ragent.mcp.executor.bit;

import com.nageoffer.ai.ragent.mcp.config.bit.BitProperties;
import com.nageoffer.ai.ragent.mcp.dao.mapper.CartMapper;
import com.nageoffer.ai.ragent.mcp.dao.mapper.OrderItemMapper;
import com.nageoffer.ai.ragent.mcp.dao.mapper.OrderMapper;
import com.nageoffer.ai.ragent.mcp.dao.mapper.ProductSkuMapper;
import com.nageoffer.ai.ragent.mcp.dao.mapper.UserCouponMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O9 回归：M14 订单号并发撞唯一键在事务外重算重试一次、失败文案收敛不透出 DB 异常原文；
 * L27 待支付超时最小值启动期守门。
 */
class BitOrderCreateRetryAndErrorFaceTest {

    private BitOrderCreateMcpExecutor executor(TransactionTemplate template) {
        return new BitOrderCreateMcpExecutor(
                mock(CartMapper.class),
                mock(ProductSkuMapper.class),
                mock(OrderMapper.class),
                mock(OrderItemMapper.class),
                mock(UserCouponMapper.class),
                template,
                new BitProperties());
    }

    @Test
    @DisplayName("M14：撞 uk_order_no 后事务外重算重试一次即成功")
    void duplicateKeyRetriesOnceInNewTransaction() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        CallToolResult success = com.nageoffer.ai.ragent.mcp.executor.McpToolResults.success("ok");
        when(template.execute(any()))
                .thenThrow(new DuplicateKeyException("uk_order_no duplicate key"))
                .thenReturn(success);
        BitOrderCreateMcpExecutor service = executor(template);

        CallToolResult result = service.placeOrderWithNumberRetry("u1", java.util.List.of("SKU1"), null, null);

        assertSame(success, result);
        verify(template, times(2)).execute(any());
    }

    @Test
    @DisplayName("M14：两次都撞时如实上抛，由分类文案接住（不透出 SQL 原文）")
    void persistentConflictSurfacesCategorizedText() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        when(template.execute(any()))
                .thenThrow(new DuplicateKeyException("ERROR: duplicate key value violates unique constraint \"uk_order_no\" Detail: Key (order_no)=(9527) already exists."));
        BitOrderCreateMcpExecutor service = executor(template);

        assertThrows(DuplicateKeyException.class,
                () -> service.placeOrderWithNumberRetry("u1", java.util.List.of("SKU1"), null, null));
        verify(template, times(2)).execute(any());

        String message = service.friendlyOrderFailure(new DuplicateKeyException("ERROR: duplicate key ... uk_order_no ..."));
        assertEquals("下单失败：并发冲突，请稍后重试", message);
        assertFalse(message.contains("uk_order_no"));
        assertFalse(message.contains("duplicate key"));
    }

    @Test
    @DisplayName("M14：非并发类失败一律收敛通用文案（IAE/ISE 原文也只进日志，业务回绝走 rejected 返回值不走异常）")
    void categorizesOtherFailures() {
        BitOrderCreateMcpExecutor service = executor(mock(TransactionTemplate.class));
        assertEquals("下单失败：系统繁忙，请稍后重试",
                service.friendlyOrderFailure(new RuntimeException("SQL syntax error near 'FROM t_order'")));
        assertEquals("下单失败：系统繁忙，请稍后重试",
                service.friendlyOrderFailure(new IllegalArgumentException("Index: 1, Size: 0")));
        assertEquals("下单失败：系统繁忙，请稍后重试",
                service.friendlyOrderFailure(new IllegalStateException("Connection refused: jdbc:postgresql://10.0.0.8:5432/bit")));
    }

    @Test
    @DisplayName("L27：待支付超时低于 60 秒在启动期失败（不带病运行）")
    void timeoutFloorRejectsSmallValues() {
        BitProperties properties = new BitProperties();
        properties.getPendingOrder().setTimeout(Duration.ofSeconds(30));
        BitPendingOrderTimeoutTask task = new BitPendingOrderTimeoutTask(
                mock(OrderMapper.class), mock(BitOrderReleaser.class), properties);

        IllegalStateException ex = assertThrows(IllegalStateException.class, task::validateTimeoutFloor);
        assertTrue(ex.getMessage().contains("60"));
    }

    @Test
    @DisplayName("L27：合法超时（默认 5 分钟）通过守门")
    void timeoutFloorAcceptsDefault() {
        BitPendingOrderTimeoutTask task = new BitPendingOrderTimeoutTask(
                mock(OrderMapper.class), mock(BitOrderReleaser.class), new BitProperties());
        task.validateTimeoutFloor();
    }
}

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

package com.nageoffer.ai.ragent.rag.core.vector;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrieveRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PgVectorRetrieverServiceTest {

    /**
     * M8：hnsw GUC 是会话级——SET 与 SELECT 必须经 ConnectionCallback 钉在同一物理连接，
     * 各自 jdbcTemplate 取连接时参数形同虚设。多库检索仍是一条 IN 查询共享一个 LIMIT。
     */
    @Test
    @DisplayName("M8：GUC 与查询同连接 + 多 Collection 单条 IN 查询共享 LIMIT")
    @SuppressWarnings("unchecked")
    void gucAndQueryShareOneConnection() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        when(embeddingService.embed("报销流程")).thenReturn(List.of(3.0F, 4.0F));

        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PreparedStatement preparedStatement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(any(String.class))).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("id")).thenReturn("c1");
        when(resultSet.getString("content")).thenReturn("报销需发票");
        when(resultSet.getString("collection_name")).thenReturn("kb-finance");
        when(resultSet.getFloat("score")).thenReturn(0.9F);

        // execute(ConnectionCallback) 直接把回调跑在 mock 连接上
        AtomicReference<List<RetrievedChunk>> out = new AtomicReference<>();
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenAnswer(invocation -> {
            ConnectionCallback<?> callback = invocation.getArgument(0);
            out.set((List<RetrievedChunk>) callback.doInConnection(connection));
            return out.get();
        });

        PgVectorRetrieverService service = new PgVectorRetrieverService(jdbcTemplate, embeddingService);
        List<RetrievedChunk> chunks = service.retrieve(RetrieveRequest.builder()
                .query("报销流程")
                .collectionNames(List.of("kb-finance", "kb-policy"))
                .topK(7)
                .build());

        // GUC 与查询同连接：同一 Statement 连发两条 SET（try-with-resources 单语句复用）
        verify(statement, times(2)).execute(any(String.class));
        verify(connection, times(1)).createStatement();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sqlCaptor.capture());
        assertTrue(sqlCaptor.getValue().contains("collection_name IN (?, ?)"),
                "多库共用一条 IN 查询：" + sqlCaptor.getValue());

        // 参数绑定：两个库名 + 查询向量 + LIMIT（全部占位符绑定，无拼接值）
        verify(preparedStatement).setString(1, "[0.6,0.8]");
        verify(preparedStatement).setString(2, "kb-finance");
        verify(preparedStatement).setString(3, "kb-policy");
        verify(preparedStatement).setString(4, "[0.6,0.8]");
        verify(preparedStatement).setInt(5, 7);

        assertEquals(1, chunks.size());
        assertEquals("c1", chunks.get(0).getId());
        verify(embeddingService, times(1)).embed("报销流程");
    }
}

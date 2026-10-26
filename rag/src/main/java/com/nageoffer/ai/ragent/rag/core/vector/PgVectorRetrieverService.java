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

import com.nageoffer.ai.ragent.rag.core.retrieval.RetrieveRequest;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
public class PgVectorRetrieverService implements VectorRetrieverService {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;

    @Override
    public List<RetrievedChunk> retrieve(RetrieveRequest request) {
        float[] vector = embedAndNormalize(request.getQuery());
        return retrieveByVector(vector, request);
    }

    @Override
    public List<RetrievedChunk> retrieveByVector(float[] vector, RetrieveRequest request) {
        List<String> collectionNames = request.getEffectiveCollectionNames();
        if (collectionNames.isEmpty()) {
            return List.of();
        }
        // 单个或多个逻辑库都通过一条 SQL 过滤，LIMIT 是整个范围的总 TopK
        return queryByCollections(vector, collectionNames, request.getTopK());
    }

    @Override
    public float[] embedAndNormalize(String query) {
        return normalize(toArray(embeddingService.embed(query)));
    }

    @Override
    public boolean supportsGlobalRetrieval() {
        return true;
    }

    /**
     * 在指定 collection 范围内执行一次向量相似度检索
     * <p>
     * 单库与全局共用此方法：单库传单元素列表，全局传多元素列表
     * <p>
     * M8：hnsw GUC 是会话级——SET 与 SELECT 经 jdbcTemplate 各自从池里取连接时，
     * 设置落在 A 连接、查询跑在 B 连接，召回参数形同虚设且 SET 残留在随机池化连接上；
     * ConnectionCallback 把两条 SET 与查询钉在同一物理连接
     */
    private List<RetrievedChunk> queryByCollections(float[] vector, List<String> collectionNames, int limit) {
        // 提升召回率；迭代扫描保证过滤后仍能填满 LIMIT，消除过滤向量检索的召回悬崖（pgvector >= 0.8）
        String vectorLiteral = toVectorLiteral(vector);
        String placeholders = collectionNames.stream().map(c -> "?").collect(java.util.stream.Collectors.joining(", "));
        String sql = "SELECT id, content, collection_name, metadata, 1 - (embedding <=> ?::vector) AS score FROM t_knowledge_vector "
                + "WHERE collection_name IN (" + placeholders + ") ORDER BY embedding <=> ?::vector LIMIT ?";

        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        return jdbcTemplate.execute((ConnectionCallback<List<RetrievedChunk>>) con -> {
            try (java.sql.Statement statement = con.createStatement()) {
                statement.execute("SET hnsw.ef_search = 200");
                statement.execute("SET hnsw.iterative_scan = relaxed_order");
            }
            try (java.sql.PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, vectorLiteral);
                for (int i = 0; i < collectionNames.size(); i++) {
                    ps.setString(i + 2, collectionNames.get(i));
                }
                ps.setString(collectionNames.size() + 2, vectorLiteral);
                ps.setInt(collectionNames.size() + 3, limit);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    List<RetrievedChunk> result = new java.util.ArrayList<>();
                    while (rs.next()) {
                        result.add(RetrievedChunk.builder()
                                .id(rs.getString("id"))
                                .text(rs.getString("content"))
                                .collectionName(rs.getString("collection_name"))
                                .score(rs.getFloat("score"))
                                .sourceDate(resolveSourceDate(rs.getString("metadata")))
                                .build());
                    }
                    return result;
                }
            }
        });
    }

    /**
     * 从向量行 metadata JSONB 解析证据日期（yyyy-MM-dd）。
     * <p>
     * 日期来源=灌库任务级 metadata 的 source_date 键（#275 口径：新闻=publish_time、
     * 校历=date_start，由灌库侧写入；带时间戳的 ISO 值截断到日）。键缺失、为空或
     * 不成形时返回 null，由上下文组装层渲染「日期未知」——当前存量语料无日期键，
     * 该通道为结构化日期透传（data-ragent-date 标记）预留的读回半边。
     */
    private String resolveSourceDate(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(metadataJson).path("source_date");
            if (!node.isTextual()) {
                return null;
            }
            String value = node.asText().trim();
            return value.matches("\\d{4}-\\d{2}-\\d{2}.*") ? value.substring(0, 10) : null;
        } catch (Exception e) {
            log.debug("metadata source_date 解析失败，按无日期处理：{}", e.getMessage());
            return null;
        }
    }

    private float[] normalize(float[] vector) {
        float norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }

    private float[] toArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        return sb.append("]").toString();
    }
}

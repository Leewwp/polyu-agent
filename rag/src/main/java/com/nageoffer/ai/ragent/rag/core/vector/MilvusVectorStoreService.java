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

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.IdUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.UpsertResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "milvus", matchIfMissing = true)
public class MilvusVectorStoreService implements VectorStoreService {

    private static final Gson GSON = new Gson();

    private final MilvusClientV2 milvusClient;
    private final RAGDefaultProperties ragDefaultProperties;

    @Override
    public void indexDocumentChunks(String collectionName, String docId, List<EmbeddedChunk> chunks) {
        Assert.isFalse(chunks == null || chunks.isEmpty(), () -> new ClientException("文档分块不允许为空"));

        final int dim = ragDefaultProperties.getDimension();
        List<float[]> vectors = extractVectors(chunks, dim);

        List<JsonObject> rows = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            EmbeddedChunk chunk = chunks.get(i);

            String content = chunk.content() == null ? "" : chunk.content();
            if (content.length() > 65535) {
                content = content.substring(0, 65535);
            }

            JsonObject metadata = buildMetadata(docId, chunk);

            JsonObject row = new JsonObject();
            row.addProperty("id", chunk.chunkId());
            row.addProperty("collection_name", collectionName);
            row.addProperty("content", content);
            row.add("metadata", metadata);
            row.add("embedding", toJsonArray(vectors.get(i)));

            rows.add(row);
        }

        UpsertReq req = UpsertReq.builder()
                .collectionName(sharedCollection())
                .data(rows)
                .build();

        // M9：upsert 而非 insert——IndexerNode 直连路径无先删，重放/重试复用同 chunkId 时
        // insert 撞主键产生重复行（检索重复召回），upsert 幂等收敛
        UpsertResp resp = milvusClient.upsert(req);
        log.info("Milvus chunk 建立/写入向量索引成功, collection={}, rows={}", collectionName, resp.getUpsertCnt());
    }

    @Override
    public void updateChunk(String collectionName, String docId, EmbeddedChunk chunk) {
        Assert.isFalse(chunk == null, () -> new ClientException("Chunk 对象不能为空"));

        final int dim = ragDefaultProperties.getDimension();
        float[] vector = extractVector(chunk, dim);

        String chunkPk = chunk.chunkId();

        String content = chunk.content() == null ? "" : chunk.content();
        if (content.length() > 65535) {
            content = content.substring(0, 65535);
        }

        JsonObject metadata = buildMetadata(docId, chunk);

        JsonObject row = new JsonObject();
        row.addProperty("id", chunkPk);
        row.addProperty("collection_name", collectionName);
        row.addProperty("content", content);
        row.add("metadata", metadata);
        row.add("embedding", toJsonArray(vector));

        UpsertReq upsertReq = UpsertReq.builder()
                .collectionName(sharedCollection())
                .data(List.of(row))
                .build();

        UpsertResp resp = milvusClient.upsert(upsertReq);
        log.info("Milvus 更新 chunk 向量索引成功, collection={}, docId={}, chunkId={}, upsertCnt={}",
                collectionName, docId, chunkPk, resp.getUpsertCnt());
    }

    @Override
    public void deleteDocumentVectors(String collectionName, String docId) {
        // 共享 collection 下多库共存，doc_id 不再天然隔离，必须叠加 collection_name 限定；
        // M7：值经 escapeFilterValue 转义，名称含引号不可逃逸表达式字面量（与检索侧同款）
        String filter = "collection_name == \"" + escapeFilterValue(collectionName)
                + "\" && metadata[\"doc_id\"] == \"" + escapeFilterValue(docId) + "\"";

        DeleteReq deleteReq = DeleteReq.builder()
                .collectionName(sharedCollection())
                .filter(filter)
                .build();

        DeleteResp resp = milvusClient.delete(deleteReq);
        log.info("Milvus 删除指定文档的所有 chunk 向量索引成功, collection={}, docId={}, deleteCnt={}",
                collectionName, docId, resp.getDeleteCnt());
    }

    @Override
    public void deleteChunkById(String collectionName, String chunkId) {
        // L13：id 为雪花主键全局唯一，但调用方传错 id 即跨库误删——叠加 collection_name 归属条件
        String filter = "collection_name == \"" + escapeFilterValue(collectionName)
                + "\" && id == \"" + escapeFilterValue(chunkId) + "\"";

        DeleteReq deleteReq = DeleteReq.builder()
                .collectionName(sharedCollection())
                .filter(filter)
                .build();

        DeleteResp resp = milvusClient.delete(deleteReq);
        log.info("Milvus 删除指定 chunk 向量索引成功, collection={}, chunkId={}, deleteCnt={}",
                collectionName, chunkId, resp.getDeleteCnt());
    }

    @Override
    public void deleteChunksByIds(String collectionName, List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        // L13：批量删除同样叠加 collection_name 归属条件；M7：逐值转义
        String idList = chunkIds.stream()
                .map(this::escapeFilterValue)
                .map(value -> "\"" + value + "\"")
                .collect(java.util.stream.Collectors.joining(", "));
        String filter = "collection_name == \"" + escapeFilterValue(collectionName) + "\" && id in [" + idList + "]";

        DeleteReq deleteReq = DeleteReq.builder()
                .collectionName(sharedCollection())
                .filter(filter)
                .build();

        DeleteResp resp = milvusClient.delete(deleteReq);
        log.info("Milvus 批量删除 chunk 向量索引成功, collection={}, count={}, deleteCnt={}",
                collectionName, chunkIds.size(), resp.getDeleteCnt());
    }

    private List<float[]> extractVectors(List<EmbeddedChunk> chunks, int expectedDim) {
        List<float[]> vectors = new ArrayList<>(chunks.size());
        for (EmbeddedChunk chunk : chunks) {
            vectors.add(extractVector(chunk, expectedDim));
        }
        return vectors;
    }

    private float[] extractVector(EmbeddedChunk chunk, int expectedDim) {
        float[] vector = chunk.embedding();
        if (vector == null || vector.length == 0) {
            throw new ClientException("向量不能为空");
        }
        if (vector.length != expectedDim) {
            throw new ClientException("向量维度不匹配，期望维度为 " + expectedDim);
        }
        return vector;
    }

    private JsonArray toJsonArray(float[] v) {
        JsonArray arr = new JsonArray(v.length);
        for (float x : v) {
            arr.add(x);
        }
        return arr;
    }

    private JsonObject buildMetadata(String docId, EmbeddedChunk chunk) {
        JsonObject metadata = new JsonObject();
        // 结构化元数据统一走唯一序列化点
        chunk.metadata().toMap().forEach((k, v) -> metadata.add(k, GSON.toJsonTree(v)));

        // collection_name 已提升为顶层标量字段，不再冗余写入 metadata
        metadata.addProperty("doc_id", docId);
        metadata.addProperty("chunk_index", chunk.index());
        return metadata;
    }

    /**
     * 全 Milvus 共用的物理 collection（所有知识库的 chunk 都写在这里，按 collection_name 标量字段区分）
     */
    private String sharedCollection() {
        return ragDefaultProperties.getCollectionName();
    }

    /**
     * M7：过滤表达式值转义（与检索侧 MilvusVectorRetrieverService.escapeFilterValue 同款）——
     * 名称含 " 或 \ 时不可逃逸字面量注入过滤条件
     */
    private String escapeFilterValue(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

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

package com.nageoffer.ai.ragent.rag.core.retrieval.postprocessor;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.knowledge.service.impl.ChunkMetadataResolver;
import com.nageoffer.ai.ragent.knowledge.service.impl.ChunkMetadataResolver.ChunkMeta;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #411 双日期列回填测试（#413 组装层消费半边）：富化按 chunkId 回表补齐日期，
 * 只补空缺不覆盖既有读回值，列 NULL 保持 null=「日期未知」语义
 */
class MetadataEnrichmentPostProcessorTest {

    private MetadataEnrichmentPostProcessor processor(Map<String, ChunkMeta> metaById) {
        ChunkMetadataResolver resolver = mock(ChunkMetadataResolver.class);
        when(resolver.resolve(anyList())).thenReturn(metaById);
        when(resolver.resolveDocNames(anyList())).thenReturn(Map.of());
        RAGConfigProperties props = new RAGConfigProperties();
        props.setContextEnrichEnabled(true);
        return new MetadataEnrichmentPostProcessor(resolver, props);
    }

    private List<RetrievedChunk> enrich(RetrievedChunk chunk, Map<String, ChunkMeta> meta) {
        return processor(meta).process(new ArrayList<>(List.of(chunk)), List.of(), mock(SearchContext.class));
    }

    @Test
    void fillsBlankDatesFromColumns() {
        // 双空缺：双日期列值原样回填（yyyy-MM-dd）
        RetrievedChunk chunk = RetrievedChunk.builder().id("c1").text("正文").build();
        Map<String, ChunkMeta> meta = Map.of("c1",
                new ChunkMeta("docA", 0, "文档A", "2026-07-21", "2026-09-01"));

        List<RetrievedChunk> result = enrich(chunk, meta);

        assertEquals("2026-07-21", result.get(0).getSourceDate());
        assertEquals("2026-09-01", result.get(0).getActivityEndDate());
    }

    @Test
    void neverOverwritesChannelReadBackDates() {
        // 向量通道已读回的 source_date（如资讯发布时间）优先，列值只补空缺
        RetrievedChunk chunk = RetrievedChunk.builder().id("c1").text("正文")
                .sourceDate("2026-02-27").build();
        Map<String, ChunkMeta> meta = Map.of("c1",
                new ChunkMeta("docA", 0, "文档A", "2026-07-21", "2026-09-01"));

        List<RetrievedChunk> result = enrich(chunk, meta);

        assertEquals("2026-02-27", result.get(0).getSourceDate(), "既有读回值不得被覆盖");
        assertEquals("2026-09-01", result.get(0).getActivityEndDate());
    }

    @Test
    void nullColumnsKeepUnknownSemantics() {
        // 列 NULL（无截止语义/无署期）→ 字段保持 null，组装层按「日期未知」降级
        RetrievedChunk chunk = RetrievedChunk.builder().id("c1").text("正文").build();
        Map<String, ChunkMeta> meta = Map.of("c1", new ChunkMeta("docA", 0, "文档A", null, null));

        List<RetrievedChunk> result = enrich(chunk, meta);

        assertNull(result.get(0).getSourceDate());
        assertNull(result.get(0).getActivityEndDate());
    }
}

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

package com.nageoffer.ai.ragent.core.chunk.dates;

import com.nageoffer.ai.ragent.core.chunk.model.Chunk;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkMetadata;
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * #411 摄取期标注语义：发布=文档级传播、截止=条目级不传播，两键经唯一序列化点进入向量
 * metadata JSONB（PR#402 读回通道消费 source_date）
 */
class ChunkDateAnnotatorTest {

    private static EmbeddedChunk chunk(int index, String content) {
        Chunk chunk = new Chunk("chunk-" + index, index, content, content, ChunkMetadata.empty());
        return new EmbeddedChunk(chunk, new float[]{0.1F, 0.2F});
    }

    @Test
    @DisplayName("发布文档级传播：首块署期覆盖全文档；截止条目级只落标记块")
    void publishPropagatesDeadlineStaysItemLevel() {
        List<EmbeddedChunk> annotated = ChunkDateAnnotator.annotate(List.of(
                chunk(0, "Annual Schedule for Academic Year 2026/27\nUpdated on 21 July 2026"),
                chunk(1, "Payment of Tuition Fees: two equal instalments before the start of each semester."),
                chunk(2, "[Notification: 14 August 2026; Payment Deadline: 1 September 2026] "
                        + "Payment of Tuition Fee for Current Students (Semester One)")));

        assertEquals("2026-07-21", annotated.get(0).metadata().extras().get(ChunkMetadata.KEY_SOURCE_DATE));
        assertNull(annotated.get(0).metadata().extras().get(ChunkMetadata.KEY_ACTIVITY_END_DATE));

        // 无标记块承接文档级发布日，但不承接别块的截止（传播截止=虚构）
        assertEquals("2026-07-21", annotated.get(1).metadata().extras().get(ChunkMetadata.KEY_SOURCE_DATE));
        assertNull(annotated.get(1).metadata().extras().get(ChunkMetadata.KEY_ACTIVITY_END_DATE));

        assertEquals("2026-07-21", annotated.get(2).metadata().extras().get(ChunkMetadata.KEY_SOURCE_DATE));
        assertEquals("2026-09-01", annotated.get(2).metadata().extras().get(ChunkMetadata.KEY_ACTIVITY_END_DATE));
    }

    @Test
    @DisplayName("块自带署期优先于文档级首见（多节页面各自署期不被覆盖）")
    void ownPublishMarkerWinsOverDocLevel() {
        List<EmbeddedChunk> annotated = ChunkDateAnnotator.annotate(List.of(
                chunk(0, "Fee schedule. Updated on 21 July 2026"),
                chunk(1, "Section for non-local students."),
                chunk(2, "Last updated on 5 October 2026")));

        assertEquals("2026-07-21", annotated.get(0).metadata().extras().get(ChunkMetadata.KEY_SOURCE_DATE));
        assertEquals("2026-07-21", annotated.get(1).metadata().extras().get(ChunkMetadata.KEY_SOURCE_DATE));
        assertEquals("2026-10-05", annotated.get(2).metadata().extras().get(ChunkMetadata.KEY_SOURCE_DATE));
    }

    @Test
    @DisplayName("无日期事实文档：不落键（关系库列 NULL、组装层「日期未知」，不虚构不推算）")
    void noDateFactsNoKeys() {
        List<EmbeddedChunk> annotated = ChunkDateAnnotator.annotate(List.of(
                chunk(0, "The Library offers workshops and orientation throughout the year."),
                chunk(1, "Terms and teaching weeks for the 26/27 academic year are listed below.")));

        assertFalse(annotated.get(0).metadata().extras().containsKey(ChunkMetadata.KEY_SOURCE_DATE));
        assertFalse(annotated.get(0).metadata().extras().containsKey(ChunkMetadata.KEY_ACTIVITY_END_DATE));
        assertFalse(annotated.get(1).metadata().extras().containsKey(ChunkMetadata.KEY_SOURCE_DATE));
        assertFalse(annotated.get(1).metadata().extras().containsKey(ChunkMetadata.KEY_ACTIVITY_END_DATE));
    }

    @Test
    @DisplayName("两键经唯一序列化点进入扁平 Map：source_date 即读回通道格式")
    void keysSurfaceThroughSerializationPoint() {
        List<EmbeddedChunk> annotated = ChunkDateAnnotator.annotate(List.of(
                chunk(0, "报名须知\n发布于 2026年10月1日\n报名截止：2026年10月15日")));

        var flat = annotated.get(0).metadata().toMap();
        assertEquals("2026-10-01", flat.get(ChunkMetadata.KEY_SOURCE_DATE));
        assertEquals("2026-10-15", flat.get(ChunkMetadata.KEY_ACTIVITY_END_DATE));
    }

    @Test
    @DisplayName("空入参与块序保持：返回一一对应")
    void preservesOrderAndHandlesEmpty() {
        assertEquals(List.of(), ChunkDateAnnotator.annotate(List.of()));
        List<EmbeddedChunk> annotated = ChunkDateAnnotator.annotate(List.of(
                chunk(0, "no dates here"), chunk(1, "Updated on 3 March 2026")));
        assertEquals(2, annotated.size());
        assertEquals("chunk-0", annotated.get(0).chunkId());
        assertEquals("chunk-1", annotated.get(1).chunkId());
    }
}

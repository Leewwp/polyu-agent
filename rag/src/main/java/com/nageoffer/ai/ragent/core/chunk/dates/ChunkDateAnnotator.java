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

import com.nageoffer.ai.ragent.core.chunk.model.ChunkMetadata;
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 摄取期日期标注（#411）：把 {@link ChunkDateFacts} 的抽取结果落进块元数据扩展位
 * <p>
 * 纯函数、无 Spring。两族事实的落位语义不同（发布≠截止分开，#376 语义分层）：
 * <ul>
 *   <li>发布时间（{@code source_date}，#275 口径 yyyy-MM-dd）是文档级事实——按块序取
 *       文档内首个显式发布标记，文档内全部块同值传播；块自带更早序外的显式发布标记时
 *       以块自己的为准（多节页面各自署期不被文档级覆盖）；</li>
 *   <li>活动截止日期（{@code activity_end_date}）是条目级事实——只落在含显式截止标记的块上，
 *       文档内不传播（其他块没有该截止语义，传播即虚构）。</li>
 * </ul>
 * 两键经 {@link ChunkMetadata#toMap()} 唯一序列化点进入全部索引后端：向量库 JSONB 的
 * {@code source_date} 由已合入的读回通道（PR#402）透传组装层；{@code activity_end_date}
 * 供组装层确定性 as-of 计算消费。无日期事实的块不落键——关系库列保持 NULL、组装层按既有
 * 行为渲染「日期未知」（不虚构、不推算）。
 */
public final class ChunkDateAnnotator {

    private ChunkDateAnnotator() {
    }

    /**
     * 对整文档的块列表做日期标注：返回新列表，与入参一一对应且顺序一致
     */
    public static List<EmbeddedChunk> annotate(List<EmbeddedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        // 每块抽取一次复用：文档级发布日按块序首个非空（确定性，与块序号一致）
        List<ChunkDateFacts.Dates> perChunk = new ArrayList<>(chunks.size());
        LocalDate docSourceDate = null;
        for (EmbeddedChunk chunk : chunks) {
            ChunkDateFacts.Dates dates = ChunkDateFacts.extract(chunk.chunk().content());
            perChunk.add(dates);
            if (docSourceDate == null && dates.sourceDate() != null) {
                docSourceDate = dates.sourceDate();
            }
        }

        List<EmbeddedChunk> result = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            EmbeddedChunk chunk = chunks.get(i);
            ChunkDateFacts.Dates dates = perChunk.get(i);
            LocalDate source = dates.sourceDate() != null ? dates.sourceDate() : docSourceDate;
            LocalDate deadline = dates.activityEndDate();

            Map<String, Object> dateExtras = new java.util.LinkedHashMap<>(2);
            if (source != null) {
                dateExtras.put(ChunkMetadata.KEY_SOURCE_DATE, ChunkDateFacts.toIsoDay(source));
            }
            if (deadline != null) {
                dateExtras.put(ChunkMetadata.KEY_ACTIVITY_END_DATE, ChunkDateFacts.toIsoDay(deadline));
            }
            result.add(dateExtras.isEmpty()
                    ? chunk
                    : new EmbeddedChunk(chunk.chunk().withMetadata(chunk.metadata().withExtras(dateExtras)),
                            chunk.embedding()));
        }
        return result;
    }
}

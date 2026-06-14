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

package com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LostInTheMiddlePostProcessorTests {

    @Test
    void reordersMostRelevantToHeadAndTail() {
        LostInTheMiddlePostProcessor processor = newProcessor();
        // 已按相关性降序：c0 最相关 ... c6 最不相关
        List<RetrievedChunk> chunks = chunksDescending(7);

        List<RetrievedChunk> reordered = processor.process(chunks, List.of(), context());

        // Haystack LostInTheMiddle 标准摆位：[0, 2, 4, 6, 5, 3, 1]
        assertThat(ids(reordered)).containsExactly("c0", "c2", "c4", "c6", "c5", "c3", "c1");
        // 最相关的两个落在首尾，最不相关的 c6 落在正中
        assertThat(reordered.get(0).getId()).isEqualTo("c0");
        assertThat(reordered.get(reordered.size() - 1).getId()).isEqualTo("c1");
        assertThat(reordered.get(reordered.size() / 2).getId()).isEqualTo("c6");
    }

    @Test
    void sortsByScoreDefensivelyBeforeReorder() {
        LostInTheMiddlePostProcessor processor = newProcessor();
        // 乱序输入（上游若关闭 Rerank 可能非严格相关性序）
        List<RetrievedChunk> chunks = new ArrayList<>(List.of(
                chunk("low", 0.1f),
                chunk("top", 0.9f),
                chunk("mid", 0.5f),
                chunk("high", 0.8f)));

        List<RetrievedChunk> reordered = processor.process(chunks, List.of(), context());

        // 先按分数降序 [top, high, mid, low] 再重排 → [top, mid, low, high]
        assertThat(ids(reordered)).containsExactly("top", "mid", "low", "high");
    }

    @Test
    void keepsOriginalOrderWhenBelowMinChunks() {
        LostInTheMiddlePostProcessor processor = newProcessor();
        List<RetrievedChunk> chunks = chunksDescending(3); // 默认 minChunks=4

        List<RetrievedChunk> reordered = processor.process(chunks, List.of(), context());

        assertThat(ids(reordered)).containsExactly("c0", "c1", "c2");
    }

    @Test
    void disabledByConfig() {
        SearchChannelProperties properties = new SearchChannelProperties();
        properties.getLostInTheMiddle().setEnabled(false);
        LostInTheMiddlePostProcessor processor = new LostInTheMiddlePostProcessor(properties);

        assertThat(processor.isEnabled(context())).isFalse();
    }

    @Test
    void emptyInputIsNoop() {
        LostInTheMiddlePostProcessor processor = newProcessor();
        assertThat(processor.process(List.of(), List.of(), context())).isEmpty();
    }

    private LostInTheMiddlePostProcessor newProcessor() {
        return new LostInTheMiddlePostProcessor(new SearchChannelProperties());
    }

    private SearchContext context() {
        return SearchContext.builder().topK(10).build();
    }

    private List<RetrievedChunk> chunksDescending(int size) {
        List<RetrievedChunk> chunks = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            chunks.add(chunk("c" + i, (float) (size - i)));
        }
        return chunks;
    }

    private List<String> ids(List<RetrievedChunk> chunks) {
        return chunks.stream().map(RetrievedChunk::getId).toList();
    }

    private RetrievedChunk chunk(String id, Float score) {
        return RetrievedChunk.builder()
                .id(id)
                .text(id)
                .score(score)
                .build();
    }
}

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
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalBudget;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.SearchContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunkKey;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import java.util.Set;

/**
 * 去重处理器回归测试
 * <p>
 * 覆盖两个历史缺陷：
 * 1. 无 id 的 Chunk 用 String.hashCode() 做去重键，哈希碰撞（如 "Aa" 与 "BB"）
 *    会把内容不同的 Chunk 误判为重复并静默丢弃
 * 2. score 为 null 时同键比较直接拆箱导致 NPE，去重处理器整体失效
 */
class DeduplicationPostProcessorTest {

    private RetrievedChunk chunk(String id, String text, Float score) {
        RetrievedChunk c = new RetrievedChunk();
        c.setId(id);
        c.setText(text);
        c.setScore(score);
        return c;
    }

    private List<SearchChannelResult> singleChannel(List<RetrievedChunk> chunks) {
        return List.of(SearchChannelResult.builder()
                .channelType(SearchChannelType.KEYWORD)
                .channelName("Keyword")
                .chunks(chunks)
                .latencyMs(1)
                .build());
    }

    @Test
    void hashCollidingTextsAreNotDeduplicated() {
        // "Aa" 与 "BB" 的 String.hashCode() 相同（均为 2112），但内容不同，不允许合并
        assertEquals("Aa".hashCode(), "BB".hashCode(), "前置条件：两段文本哈希碰撞");
        List<RetrievedChunk> chunks = new ArrayList<>();
        chunks.add(chunk(null, "Aa", 0.9f));
        chunks.add(chunk(null, "BB", 0.8f));

        List<RetrievedChunk> out = new DeduplicationPostProcessor()
                .process(chunks, singleChannel(chunks), SearchContext.builder().originalQuestion("q").budget(RetrievalBudget.uniform(10)).build());

        assertEquals(2, out.size(), "哈希碰撞的不同内容 Chunk 不允许被去重合并");
    }

    @Test
    void nullScoreDedupKeepsFirstOccurrence() {
        List<RetrievedChunk> chunks = new ArrayList<>();
        chunks.add(chunk("same-id", "文本A", null));
        chunks.add(chunk("same-id", "文本B", 0.5f));

        assertDoesNotThrow(() -> {
            List<RetrievedChunk> out = new DeduplicationPostProcessor()
                    .process(chunks, singleChannel(chunks), SearchContext.builder().originalQuestion("q").budget(RetrievalBudget.uniform(10)).build());
            assertEquals(1, out.size(), "同 id 应去重为 1 条");
            // 按 key 去重保留首次出现的实例，不比较分数（跨通道量纲不可比，最终名次交由下游 RRF）；null 分数不应抛异常
            assertEquals("文本A", out.get(0).getText(), "应保留首次出现的实例");
        });
    }

    @Test
    void differentIdsAndWhitespaceKeepHighestScore() {
        RetrievedChunk first = chunk("a", "  hello\n\tworld ", 0.3f);
        RetrievedChunk best = chunk("b", "hello world", 0.9f);
        RetrievedChunk tied = chunk("c", "hello\u3000world", 0.9f);
        List<RetrievedChunk> out = dedup(List.of(first, best, tied));
        assertEquals(1, out.size());
        assertSame(best, out.get(0));
    }

    @Test
    void nullScoresAreLowestAndTiesKeepFirst() {
        RetrievedChunk first = chunk("a", "same", null);
        RetrievedChunk second = chunk("b", "same", null);
        RetrievedChunk scored = chunk("c", "same", -1f);
        assertSame(first, dedup(List.of(first, second)).get(0));
        assertSame(scored, dedup(List.of(first, scored, second)).get(0));
    }

    @Test
    void contentAttributionAndRrfContributionsSurviveDeduplication() {
        RetrievedChunk low = chunk("low", "same text", 0.2f);
        RetrievedChunk high = chunk("high", " same\ntext ", 0.8f);
        RetrievedChunk extraCopy = chunk("copy", "same text", 0.1f);
        List<SearchChannelResult> results = List.of(
                SearchChannelResult.builder().channelType(SearchChannelType.VECTOR)
                        .chunks(List.of(low, extraCopy)).build(),
                SearchChannelResult.builder().channelType(SearchChannelType.KEYWORD)
                        .chunks(List.of(high)).build());
        SearchContext context = SearchContext.builder().budget(RetrievalBudget.uniform(10)).build();
        List<RetrievedChunk> out = new DeduplicationPostProcessor().process(List.of(), results, context);
        assertEquals(List.of(high), out);
        var index = ChannelAttribution.index(results);
        assertEquals(Set.of(SearchChannelType.VECTOR, SearchChannelType.KEYWORD),
                index.get(RetrievedChunkKey.of(high)));
        assertEquals(1, ChannelAttribution.countOfChannel(out, index, SearchChannelType.VECTOR));
        SearchChannelProperties properties = new SearchChannelProperties();
        properties.getFusion().getChannelWeights().setVector(1.0);
        properties.getFusion().getChannelWeights().setKeyword(1.0);
        new FusionPostProcessor(properties).process(out, results, context);
        // 每个通道最多贡献一次，不因同通道的多个副本而放大权重
        assertEquals(2.0 / (properties.getFusion().getRrfK() + 1), high.getScore(), 0.000001);
        assertEquals(2, results.get(0).getChunks().size());
    }

    @Test
    void sameIdentityWithDifferentTextRetainsAllChannelAttribution() {
        RetrievedChunk first = chunk("a", "first", 0.2f);
        RetrievedChunk sameId = chunk("a", "changed", 0.9f);
        RetrievedChunk winner = chunk("b", "first", 0.5f);
        List<SearchChannelResult> results = List.of(
                SearchChannelResult.builder().channelType(SearchChannelType.VECTOR).chunks(List.of(first)).build(),
                SearchChannelResult.builder().channelType(SearchChannelType.GRAPH).chunks(List.of(sameId)).build(),
                SearchChannelResult.builder().channelType(SearchChannelType.KEYWORD).chunks(List.of(winner)).build());
        var out = new DeduplicationPostProcessor().process(List.of(), results, null);
        assertEquals(List.of(winner), out);
        assertEquals(Set.of(SearchChannelType.VECTOR, SearchChannelType.GRAPH, SearchChannelType.KEYWORD),
                ChannelAttribution.index(results).get(RetrievedChunkKey.of(winner)));
    }

    @Test
    void separateQuestionsDoNotShareDeduplicationState() {
        RetrievedChunk one = chunk("a", "same", 0.2f);
        RetrievedChunk two = chunk("b", "same", 0.8f);
        DeduplicationPostProcessor processor = new DeduplicationPostProcessor();
        assertEquals(List.of(one), processor.process(List.of(), singleChannel(List.of(one)), null));
        assertEquals(List.of(two), processor.process(List.of(), singleChannel(List.of(two)), null));
    }

    private List<RetrievedChunk> dedup(List<RetrievedChunk> chunks) {
        return new DeduplicationPostProcessor().process(chunks, singleChannel(chunks), null);
    }
}

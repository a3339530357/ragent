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

import cn.hutool.core.collection.CollUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Lost in the Middle 位置重排后置处理器
 * <p>
 * 大模型对长上下文「中间段」的注意力显著弱于首尾（Liu et al., 2023,
 * <a href="https://arxiv.org/abs/2307.03172">Lost in the Middle</a>）。
 * 本处理器在不做任何模型调用的前提下，把按相关性排好序的片段重新摆位：
 * 最相关的交替放到头部和尾部，最不相关的沉到中间，从而规避「中间被忽略」。
 * <p>
 * 重排策略移植自 Haystack 的 {@code LostInTheMiddleRanker}：以最相关片段为锚定头，
 * 依次将后续片段插入「当前序列正中偏后」的位置，最终形成
 * {@code [0, 2, 4, ..., 5, 3, 1]} 这类「外高内低」的分布。
 * <p>
 * 作为整条后置处理链的最后一环执行（{@code order=20}），输入是 Rerank/RRF 之后
 * 的最终候选集，输出顺序即喂给 Prompt 的上下文顺序。引用编号在检索返回之后才分配，
 * 故 {@code [n]} 会自然跟随重排后的顺序，不会错位。
 *
 * @see SearchResultPostProcessor 后置处理器接口
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LostInTheMiddlePostProcessor implements SearchResultPostProcessor {

    private final SearchChannelProperties properties;

    @Override
    public String getName() {
        return "LostInTheMiddle";
    }

    @Override
    public int getOrder() {
        // 必须在 Rerank（order=10）之后，对最终候选集做位置重排
        return 20;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return properties.getLostInTheMiddle().isEnabled();
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        if (CollUtil.isEmpty(chunks)) {
            return chunks;
        }

        int minChunks = Math.max(2, properties.getLostInTheMiddle().getMinChunks());
        if (chunks.size() < minChunks) {
            // 片段过少时不存在「中间被忽略」问题，保留原相关性顺序
            return chunks;
        }

        // 防御性按相关性降序排列，确保「最相关在前」的前置条件成立
        // （Rerank 关闭时上游可能不是严格的相关性序）
        List<RetrievedChunk> sorted = new ArrayList<>(chunks);
        sorted.sort(Comparator.comparingDouble(LostInTheMiddlePostProcessor::safeScore).reversed());

        List<RetrievedChunk> reordered = reorder(sorted);
        if (log.isDebugEnabled()) {
            log.debug("Lost in the Middle 重排完成 - 片段数: {}, 重排前顺序: {}, 重排后顺序: {}",
                    reordered.size(), describe(sorted), describe(reordered));
        } else {
            log.info("Lost in the Middle 重排完成 - 片段数: {}", reordered.size());
        }
        return reordered;
    }

    /**
     * 按 Lost in the Middle 策略重排：最相关的片段交替插到头尾，最不相关的沉到中间。
     * <p>
     * 入参须已按相关性<b>降序</b>排列（index 0 最相关）。算法逐个把片段插入当前序列
     * 「正中偏后」的位置（{@code size/2 + size%2}），等价于 Haystack 的索引摆位实现。
     *
     * @param relevanceSorted 已按相关性降序排列的片段列表
     * @return 重排后的片段列表（新列表，不修改入参）
     */
    private List<RetrievedChunk> reorder(List<RetrievedChunk> relevanceSorted) {
        List<RetrievedChunk> arranged = new ArrayList<>(relevanceSorted.size());
        for (RetrievedChunk chunk : relevanceSorted) {
            int insertionIndex = arranged.size() / 2 + arranged.size() % 2;
            arranged.add(insertionIndex, chunk);
        }
        return arranged;
    }

    private static double safeScore(RetrievedChunk chunk) {
        return chunk == null || chunk.getScore() == null ? Double.NEGATIVE_INFINITY : chunk.getScore();
    }

    private String describe(List<RetrievedChunk> chunks) {
        return chunks.stream()
                .map(chunk -> chunk == null ? "null" : chunk.getId())
                .toList()
                .toString();
    }
}

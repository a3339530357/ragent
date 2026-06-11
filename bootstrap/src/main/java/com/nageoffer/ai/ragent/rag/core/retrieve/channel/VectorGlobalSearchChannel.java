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

package com.nageoffer.ai.ragent.rag.core.retrieve.channel;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.strategy.CollectionParallelRetriever;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 向量全局检索通道
 */
@Slf4j
@Component
public class VectorGlobalSearchChannel implements SearchChannel {

    private final SearchChannelProperties properties;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final CollectionParallelRetriever parallelRetriever;

    public VectorGlobalSearchChannel(RetrieverService retrieverService,
                                     SearchChannelProperties properties,
                                     KnowledgeBaseMapper knowledgeBaseMapper,
                                     Executor innerRetrievalExecutor) {
        this.properties = properties;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.parallelRetriever = new CollectionParallelRetriever(retrieverService, innerRetrievalExecutor);
    }

    @Override
    public String getName() {
        return "VectorGlobalSearch";
    }

    @Override
    public int getPriority() {
        return 10;  // 较低优先级
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        // 检查配置是否启用
        if (!properties.getChannels().getVectorGlobal().isEnabled()) {
            return false;
        }

        // 意图定向检索关闭时，全局检索必须兜底，否则无通道可用
        if (!properties.getChannels().getIntentDirected().isEnabled()) {
            return true;
        }

        List<NodeScore> allScores = context.getIntents().stream()
                .flatMap(si -> si.nodeScores().stream())
                .toList();
        if (CollUtil.isEmpty(allScores)) {
            log.info("未识别出任何意图，启用全局检索");
            return true;
        }

        double maxScore = allScores.stream()
                .mapToDouble(NodeScore::getScore)
                .max()
                .orElse(0.0);

        double threshold = properties.getChannels().getVectorGlobal().getConfidenceThreshold();
        if (maxScore < threshold) {
            log.info("意图置信度过低（{}），启用全局检索", maxScore);
            return true;
        }

        double supplementThreshold = properties.getChannels().getVectorGlobal().getSingleIntentSupplementThreshold();
        if (allScores.size() == 1 && maxScore < supplementThreshold) {
            log.info("单一中等置信度意图（{}），启用补充全局检索", maxScore);
            return true;
        }

        return false;
    }

    @Override
    public List<SearchTarget> resolveSearchTargets(SearchContext context) {
        return getAllKBTargets(context);
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();

        try {
            log.info("执行向量全局检索，问题：{}", context.getMainQuestion());

            // 获取所有 KB 类型的 collection
            List<SearchTarget> targets = context.getSearchTargets(getName());
            if (CollUtil.isEmpty(targets)) {
                targets = resolveSearchTargets(context);
            }

            if (targets.isEmpty()) {
                log.warn("未找到任何 KB collection，跳过全局检索");
                return SearchChannelResult.builder()
                        .channelType(SearchChannelType.VECTOR_GLOBAL)
                        .channelName(getName())
                        .chunks(List.of())
                        .latencyMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            // 并行在所有 collection 中检索
            int topKMultiplier = properties.getChannels().getVectorGlobal().getTopKMultiplier();
            List<RetrievedChunk> allChunks = retrieveFromAllCollections(
                    context,
                    targets,
                    context.getTopK() * topKMultiplier
            );
            List<RetrievedChunk> filteredChunks = filterByMinScore(allChunks);

            long latency = System.currentTimeMillis() - startTime;

            log.info("向量全局检索完成，原始 {} 个 Chunk，过滤后 {} 个 Chunk，耗时 {}ms",
                    allChunks.size(), filteredChunks.size(), latency);

            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.VECTOR_GLOBAL)
                    .channelName(getName())
                    .chunks(annotate(filteredChunks))
                    .latencyMs(latency)
                    .build();

        } catch (Exception e) {
            log.error("向量全局检索失败", e);
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.VECTOR_GLOBAL)
                    .channelName(getName())
                    .chunks(List.of())
                    .latencyMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    /**
     * 获取所有 KB 类型的 collection
     */
    private List<SearchTarget> getAllKBTargets(SearchContext context) {
        // 从知识库表获取全量 collection（全局检索兜底）
        QueryWrapper<KnowledgeBaseDO> queryWrapper = new QueryWrapper<KnowledgeBaseDO>()
                .select("id", "name", "collection_name", "embedding_model")
                .eq("deleted", 0);
        List<KnowledgeBaseDO> kbList = knowledgeBaseMapper.selectList(queryWrapper);
        return kbList.stream()
                .filter(kb -> allowsKb(kb, context))
                .filter(kb -> kb.getCollectionName() != null && !kb.getCollectionName().isBlank())
                .collect(java.util.stream.Collectors.toMap(
                        KnowledgeBaseDO::getCollectionName,
                        kb -> SearchTarget.builder()
                                .channelName(getName())
                                .targetId(kb.getId())
                                .targetName(kb.getName())
                                .collectionName(kb.getCollectionName())
                                .embeddingModelId(kb.getEmbeddingModel())
                                .query(context.getMainQuestion())
                                .build(),
                        (left, right) -> left
                ))
                .values()
                .stream()
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private boolean allowsKb(KnowledgeBaseDO kb, SearchContext context) {
        if (context == null || context.getFilterContext() == null || !context.getFilterContext().hasKbScope()) {
            return true;
        }
        return context.getFilterContext().getAllowedKbIds().contains(kb.getId());
    }

    /**
     * 并行在所有 collection 中检索
     */
    private List<RetrievedChunk> retrieveFromAllCollections(SearchContext context,
                                                            List<SearchTarget> targets,
                                                            int topK) {
        // 使用模板方法执行并行检索
        return parallelRetriever.executeParallelRetrieval(context, targets, topK);
    }

    private List<RetrievedChunk> filterByMinScore(List<RetrievedChunk> chunks) {
        double minScore = properties.getChannels().getVectorGlobal().getMinScore();
        if (minScore <= 0 || CollUtil.isEmpty(chunks)) {
            return chunks;
        }

        List<RetrievedChunk> filtered = chunks.stream()
                .filter(chunk -> chunk.getScore() != null && chunk.getScore() >= minScore)
                .toList();

        if (filtered.size() != chunks.size()) {
            log.info("全局检索分数过滤完成，阈值：{}，输入：{}，输出：{}",
                    minScore, chunks.size(), filtered.size());
        }
        return filtered;
    }

    private List<RetrievedChunk> annotate(List<RetrievedChunk> chunks) {
        if (CollUtil.isEmpty(chunks)) {
            return chunks;
        }
        chunks.forEach(chunk -> {
            if (chunk.getMetadata() != null) {
                chunk.getMetadata().put("channelType", SearchChannelType.VECTOR_GLOBAL.name());
                chunk.getMetadata().put("channelName", getName());
            }
        });
        return chunks;
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.VECTOR_GLOBAL;
    }
}

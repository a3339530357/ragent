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

package com.nageoffer.ai.ragent.rag.core.retrieve;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchTarget;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.RetrievalFilterContextFactory;
import com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor.SearchResultPostProcessor;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 多通道检索引擎 —— RAG 管线的核心检索执行层
 * <p>
 * 由 {@link RetrievalEngine} 委托调用，专门负责 KB（知识库）场景下的向量检索和后处理。
 * 采用"并行检索 + 串行后处理"的两阶段架构：
 * <p>
 * <b>Phase 1：多通道并行检索</b>
 * <ul>
 *   <li>通过 Spring 自动注入所有 {@link SearchChannel} 实现（如 IntentDirectedSearchChannel、VectorGlobalSearchChannel）</li>
 *   <li>按 {@link SearchChannel#isEnabled(SearchContext)} 过滤启用的通道</li>
 *   <li>按 {@link SearchChannel#getPriority()} 排序（数值越小优先级越高）</li>
 *   <li>使用 {@code ragRetrievalExecutor} 线程池并行执行所有通道的检索</li>
 *   <li>单个通道异常不会中断其他通道，而是返回空结果集（降级兜底）</li>
 * </ul>
 * <p>
 * <b>Phase 2：后置处理器链（责任链模式）</b>
 * <ul>
 *   <li>通过 Spring 自动注入所有 {@link SearchResultPostProcessor} 实现</li>
 *   <li>按 {@link SearchResultPostProcessor#getOrder()} 排序后依次执行</li>
 *   <li>典型处理器链：DeduplicationPostProcessor(order=1, 按通道优先级去重) → RrfFusionPostProcessor(order=5, 多通道融合) → RerankPostProcessor(order=10, 语义重排) → LostInTheMiddlePostProcessor(order=20, 位置重排)</li>
 *   <li>单个处理器异常时跳过该处理器，继续执行后续处理器（容错设计）</li>
 * </ul>
 *
 * @see SearchChannel 检索通道接口
 * @see SearchResultPostProcessor 后置处理器接口
 * @see RetrievalEngine 上层检索协调器
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiChannelRetrievalEngine {

    /** 所有检索通道实现（通过 Spring IoC 自动注入） */
    private final List<SearchChannel> searchChannels;

    /** 所有后置处理器实现（通过 Spring IoC 自动注入） */
    private final List<SearchResultPostProcessor> postProcessors;

    /** Query Embedding 服务 */
    private final EmbeddingService embeddingService;

    /** 全局过滤上下文工厂 */
    private final RetrievalFilterContextFactory retrievalFilterContextFactory;

    /** 多通道并行检索线程池 */
    private final Executor ragRetrievalExecutor;

    /**
     * 执行多通道检索（仅 KB 场景）
     * <p>
     * 完整流程：构建检索上下文 → Phase 1 多通道并行检索 → Phase 2 后置处理器链 → 返回最终 Chunk 列表。
     *
     * @param subIntents 子问题意图列表（当前实现取第一个子问题作为检索查询）
     * @param topK       期望返回的结果数量
     * @return 经过去重、重排后的检索 Chunk 列表，无结果时返回空列表
     */
    @RagTraceNode(name = "multi-channel-retrieval", type = "RETRIEVE_CHANNEL")
    public List<RetrievedChunk> retrieveKnowledgeChannels(List<SubQuestionIntent> subIntents, int topK) {
        // 构建检索上下文
        SearchContext context = buildSearchContext(subIntents, topK);

        // 【阶段1：多通道并行检索】
        List<SearchChannelResult> channelResults = executeSearchChannels(context);
        if (CollUtil.isEmpty(channelResults)) {
            return List.of();
        }

        // 【阶段2：后置处理器链】
        return executePostProcessors(channelResults, context);
    }

    /**
     * 【Phase 1】执行所有启用的检索通道
     * <p>
     * 流程：
     * <ol>
     *   <li>过滤启用的通道（{@link SearchChannel#isEnabled(SearchContext)}）</li>
     *   <li>按优先级排序（数值越小越优先）</li>
     *   <li>使用 {@code ragRetrievalExecutor} 线程池并行提交所有通道的检索任务</li>
     *   <li>等待所有任务完成，收集结果并记录统计信息</li>
     * </ol>
     * <p>
     * 容错策略：单个通道执行异常时，捕获异常并返回空结果（confidence=0），
     * 不影响其他通道的正常执行。
     *
     * @param context 检索上下文（包含查询文本、意图、topK 等信息）
     * @return 所有通道的检索结果列表（含空结果的通道）
     */
    private List<SearchChannelResult> executeSearchChannels(SearchContext context) {
        // 过滤启用的通道并按优先级排序（priority 值越小优先级越高）
        List<SearchChannel> enabledChannels = searchChannels.stream()
                .filter(channel -> channel.isEnabled(context))
                .sorted(Comparator.comparingInt(SearchChannel::getPriority))
                .toList();

        if (enabledChannels.isEmpty()) {
            return List.of();
        }

        log.info("启用的检索通道：{}",
                enabledChannels.stream().map(SearchChannel::getName).toList());

        prepareQueryEmbeddings(context, enabledChannels);

        // 使用线程池并行执行所有通道，每个通道独立捕获异常以实现容错
        List<CompletableFuture<SearchChannelResult>> futures = enabledChannels.stream()
                .map(channel -> CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                log.info("执行检索通道：{}", channel.getName());
                                return channel.search(context);
                            } catch (Exception e) {
                                // 单通道异常降级：返回空结果而非抛出异常，保证其他通道不受影响
                                log.error("检索通道 {} 执行失败", channel.getName(), e);
                                return emptyResult(channel);
                            }
                        },
                        ragRetrievalExecutor
                ))
                .toList();

        // 等待所有通道完成并统计
        int successCount = 0;
        int failureCount = 0;
        int totalChunks = 0;

        List<SearchChannelResult> results = futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .toList();

        // 打印详细统计信息
        for (SearchChannelResult result : results) {
            int chunkCount = result.getChunks().size();
            totalChunks += chunkCount;

            if (chunkCount > 0) {
                successCount++;
                log.info("通道 {} 完成 ✓ - 检索到 {} 个 Chunk，耗时：{}ms",
                        result.getChannelName(),
                        chunkCount,
                        result.getLatencyMs()
                );
            } else {
                failureCount++;
                log.warn("通道 {} 完成但无结果 - 耗时：{}ms",
                        result.getChannelName(),
                        result.getLatencyMs()
                );
            }
        }

        log.info("多通道检索统计 - 总通道数: {}, 有结果: {}, 无结果: {}, Chunk 总数: {}",
                enabledChannels.size(), successCount, failureCount, totalChunks);
        log.info("Query Embedding 复用统计 - 本轮生成次数: {}, 缓存组数: {}, 详情: {}",
                context.getQueryEmbeddingContext() == null ? 0 : context.getQueryEmbeddingContext().generatedCount(),
                context.getQueryEmbeddingContext() == null ? 0 : context.getQueryEmbeddingContext().cachedGroupCount(),
                context.getQueryEmbeddingContext() == null ? "" : context.getQueryEmbeddingContext().describeEmbeddingGroups());

        return results;
    }

    private void prepareQueryEmbeddings(SearchContext context, List<SearchChannel> enabledChannels) {
        List<SearchTarget> allTargets = new ArrayList<>();
        for (SearchChannel channel : enabledChannels) {
            try {
                List<SearchTarget> channelTargets = channel.resolveSearchTargets(context);
                context.putSearchTargets(channel.getName(), channelTargets);
                if (CollUtil.isNotEmpty(channelTargets)) {
                    allTargets.addAll(channelTargets);
                    log.info("通道 {} 解析检索目标完成，targetCount={}, targets={}",
                            channel.getName(),
                            channelTargets.size(),
                            channelTargets.stream().map(SearchTarget::displayName).toList());
                } else {
                    log.info("通道 {} 未解析到向量检索目标", channel.getName());
                }
            } catch (Exception e) {
                context.putSearchTargets(channel.getName(), List.of());
                log.warn("通道 {} 解析检索目标失败，将在通道执行阶段按旧逻辑兜底，原因: {}",
                        channel.getName(), e.getMessage(), e);
            }
        }

        if (context.getQueryEmbeddingContext() == null) {
            log.warn("SearchContext 缺少 QueryEmbeddingContext，跳过 Query Embedding 预生成");
            return;
        }
        context.getQueryEmbeddingContext().preload(allTargets);
    }

    /**
     * 【Phase 2】执行后置处理器链
     * <p>
     * 采用责任链模式，将所有通道的检索结果合并后，依次通过每个启用的后置处理器进行处理。
     * 典型的处理器链：
     * <ol>
     *   <li>{@code DeduplicationPostProcessor}（order=1）—— 按通道优先级去重，高优先级通道的 Chunk 保留</li>
     *   <li>{@code RerankPostProcessor}（order=10）—— 调用 Rerank 模型对 Chunk 进行语义重排序</li>
     * </ol>
     * <p>
     * 容错策略：单个处理器异常时跳过该处理器，使用上一步的结果继续执行后续处理器。
     *
     * @param results 所有通道的原始检索结果
     * @param context 检索上下文
     * @return 经过所有后置处理器处理后的最终 Chunk 列表
     */
    private List<RetrievedChunk> executePostProcessors(List<SearchChannelResult> results,
                                                       SearchContext context) {
        // 过滤启用的处理器并按 order 排序（数值越小越先执行）
        List<SearchResultPostProcessor> enabledProcessors = postProcessors.stream()
                .filter(processor -> processor.isEnabled(context))
                .sorted(Comparator.comparingInt(SearchResultPostProcessor::getOrder))
                .toList();

        if (enabledProcessors.isEmpty()) {
            // 无启用的后置处理器时直接返回原始结果（理论上不应发生，作为安全兜底）
            log.warn("没有启用的后置处理器，直接返回原始结果");
            return results.stream()
                    .flatMap(r -> r.getChunks().stream())
                    .collect(Collectors.toList());
        }

        // 初始 Chunk 列表：将所有通道的结果扁平化合并
        List<RetrievedChunk> chunks = results.stream()
                .flatMap(r -> r.getChunks().stream())
                .collect(Collectors.toList());

        int initialSize = chunks.size();

        // 依次执行处理器（责任链模式：每个处理器的输出作为下一个处理器的输入）
        for (SearchResultPostProcessor processor : enabledProcessors) {
            try {
                int beforeSize = chunks.size();
                chunks = processor.process(chunks, results, context);
                int afterSize = chunks.size();

                log.info("后置处理器 {} 完成 - 输入: {} 个 Chunk, 输出: {} 个 Chunk, 变化: {}",
                        processor.getName(),
                        beforeSize,
                        afterSize,
                        (afterSize - beforeSize > 0 ? "+" : "") + (afterSize - beforeSize)
                );
            } catch (Exception e) {
                // 单个处理器异常时跳过，不中断整个链，保证后续处理器仍可执行
                log.error("后置处理器 {} 执行失败，跳过该处理器", processor.getName(), e);
                // 继续执行下一个处理器，不中断整个链
            }
        }

        log.info("后置处理器链执行完成 - 初始: {} 个 Chunk, 最终: {} 个 Chunk",
                initialSize, chunks.size());

        return chunks;
    }

    private SearchChannelResult emptyResult(SearchChannel channel) {
        return SearchChannelResult.builder()
                .channelType(channel.getType())
                .channelName(channel.getName())
                .chunks(List.of())
                .build();
    }

    /**
     * 构建检索上下文
     * <p>
     * 从子问题意图列表中提取查询文本和相关参数，封装为 {@link SearchContext}
     * 供各检索通道和后置处理器使用。当前取第一个子问题的文本作为检索查询。
     *
     * @param subIntents 子问题意图列表
     * @param topK       期望返回的结果数量
     * @return 检索上下文对象
     */
    private SearchContext buildSearchContext(List<SubQuestionIntent> subIntents, int topK) {
        String question = CollUtil.isEmpty(subIntents) ? "" : subIntents.get(0).subQuestion();
        List<SubQuestionIntent> intents = CollUtil.isEmpty(subIntents) ? List.of() : subIntents;

        return SearchContext.builder()
                .originalQuestion(question)
                .rewrittenQuestion(question)
                .intents(intents)
                .topK(topK)
                .filterContext(retrievalFilterContextFactory.create())
                .queryEmbeddingContext(QueryEmbeddingContext.create(
                        question,
                        question,
                        IdUtil.fastSimpleUUID(),
                        RagTraceContext.getTraceId(),
                        embeddingService
                ))
                .build();
    }
}

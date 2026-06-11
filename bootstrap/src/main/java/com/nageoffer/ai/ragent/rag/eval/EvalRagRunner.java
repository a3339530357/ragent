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

package com.nageoffer.ai.ragent.rag.eval;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.rag.config.RagTraceProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceRunDO;
import com.nageoffer.ai.ragent.rag.service.RagTraceRecordService;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptContext;
import com.nageoffer.ai.ragent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScoreFilters;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.dto.IntentGroup;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 可复用的单题 RAG 评测 runner。
 */
@Service
@RequiredArgsConstructor
public class EvalRagRunner {

    private static final String TRACE_NAME = "eval-rag-run";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_ERROR = "ERROR";

    private final QueryRewriteService queryRewriteService;
    private final IntentResolver intentResolver;
    private final RetrievalEngine retrievalEngine;
    private final LLMService llmService;
    private final RAGPromptService promptBuilder;
    private final SearchChannelProperties searchProperties;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final RagTraceProperties traceProperties;
    private final RagTraceRecordService traceRecordService;

    public EvalSnapshot run(String question) {
        if (!traceProperties.isEnabled() || StrUtil.isNotBlank(RagTraceContext.getTraceId())) {
            return doRun(question);
        }

        String traceId = IdUtil.getSnowflakeNextIdStr();
        long startMillis = System.currentTimeMillis();
        traceRecordService.startRun(RagTraceRunDO.builder()
                .traceId(traceId)
                .traceName(TRACE_NAME)
                .entryMethod(EvalRagRunner.class.getName() + "#run")
                .userId(UserContext.getUserId())
                .status(STATUS_RUNNING)
                .startTime(new Date(startMillis))
                .build());

        RagTraceContext.setTraceId(traceId);
        try {
            EvalSnapshot snapshot = doRun(question);
            traceRecordService.finishRun(traceId, STATUS_SUCCESS, null, new Date(), System.currentTimeMillis() - startMillis);
            return snapshot;
        } catch (RuntimeException | Error ex) {
            traceRecordService.finishRun(traceId, STATUS_ERROR, truncateError(ex), new Date(), System.currentTimeMillis() - startMillis);
            throw ex;
        } finally {
            RagTraceContext.clear();
        }
    }

    private EvalSnapshot doRun(String question) {
        long start = System.currentTimeMillis();

        RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(question, List.of());
        List<SubQuestionIntent> subIntents = intentResolver.resolve(rewriteResult);
        RetrievalContext retrievalContext = retrievalEngine.retrieve(subIntents, searchProperties.getDefaultTopK());
        String answer = generateAnswer(rewriteResult, retrievalContext, subIntents);

        return buildSnapshot(question, rewriteResult, retrievalContext, subIntents, answer, System.currentTimeMillis() - start);
    }

    private String truncateError(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        String message = throwable.getClass().getSimpleName() + ": " + StrUtil.blankToDefault(throwable.getMessage(), "");
        int max = traceProperties.getMaxErrorLength();
        return message.length() <= max ? message : message.substring(0, max);
    }

    private EvalSnapshot buildSnapshot(String question,
                                       RewriteResult rewriteResult,
                                       RetrievalContext retrievalContext,
                                       List<SubQuestionIntent> subIntents,
                                       String answer,
                                       long latencyMs) {
        List<RetrievedChunk> uniqueChunks = flattenChunks(retrievalContext);
        List<String> chunkIds = uniqueChunks.stream()
                .map(RetrievedChunk::getId)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());
        List<String> contexts = uniqueChunks.stream()
                .map(RetrievedChunk::getText)
                .collect(Collectors.toList());
        List<String> contextDocIds = resolveContextDocIds(uniqueChunks);
        List<String> docIds = dedupNonBlank(contextDocIds);
        List<EvalSnapshot.RetrievalChunkSnapshot> chunkDetails = buildChunkDetails(uniqueChunks, contextDocIds);

        return EvalSnapshot.builder()
                .originalQuestion(question)
                .rewrittenQuestion(rewriteResult == null ? null : rewriteResult.rewrittenQuestion())
                .subQuestions(extractSubQuestions(subIntents))
                .intentLeafIds(extractTopLeafIds(subIntents))
                .retrievedDocIds(docIds)
                .retrievedChunkIds(chunkIds)
                .retrievedContexts(contexts)
                .retrievedContextDocIds(contextDocIds)
                .retrievedChunkDetails(chunkDetails)
                .retrievalChannelCounts(channelCounts(chunkDetails))
                .mcpContext(retrievalContext == null ? null : retrievalContext.getMcpContext())
                .mcpToolIds(extractMcpToolIds(subIntents))
                .hasMcp(retrievalContext != null && retrievalContext.hasMcp())
                .hasKb(retrievalContext != null && retrievalContext.hasKb())
                .answer(answer)
                .traceId(RagTraceContext.getTraceId())
                .latencyMs(latencyMs)
                .build();
    }

    private List<EvalSnapshot.RetrievalChunkSnapshot> buildChunkDetails(List<RetrievedChunk> chunks,
                                                                        List<String> contextDocIds) {
        if (CollUtil.isEmpty(chunks)) {
            return Collections.emptyList();
        }
        List<EvalSnapshot.RetrievalChunkSnapshot> details = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            Map<String, Object> metadata = chunk.getMetadata() == null ? Map.of() : chunk.getMetadata();
            String bizDocId = i < contextDocIds.size() ? contextDocIds.get(i) : null;
            details.add(EvalSnapshot.RetrievalChunkSnapshot.builder()
                    .chunkId(chunk.getId())
                    .docId(StrUtil.blankToDefault(bizDocId, chunk.getDocId()))
                    .docName(chunk.getDocName())
                    .kbId(chunk.getKbId())
                    .kbName(chunk.getKbName())
                    .collectionName(chunk.getCollectionName())
                    .chunkIndex(chunk.getChunkIndex())
                    .score(chunk.getScore())
                    .channelType(asString(metadata.get("channelType")))
                    .channelName(asString(metadata.get("channelName")))
                    .fusionScore(asDouble(metadata.get("fusionScore")))
                    .fusionChannelHits(asInteger(metadata.get("fusionChannelHits")))
                    .fusionSources(metadata.get("fusionSources"))
                    .build());
        }
        return details;
    }

    private Map<String, Integer> channelCounts(List<EvalSnapshot.RetrievalChunkSnapshot> details) {
        if (CollUtil.isEmpty(details)) {
            return Map.of();
        }
        return details.stream()
                .map(detail -> StrUtil.blankToDefault(detail.getChannelType(), "UNKNOWN"))
                .collect(Collectors.groupingBy(
                        channel -> channel,
                        java.util.LinkedHashMap::new,
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
                ));
    }

    private String generateAnswer(RewriteResult rewriteResult,
                                  RetrievalContext retrievalContext,
                                  List<SubQuestionIntent> subIntents) {
        if (retrievalContext == null || retrievalContext.isEmpty()) {
            return "未检索到与问题相关的文档内容。";
        }
        IntentGroup mergedGroup = intentResolver.mergeIntentGroup(subIntents);
        String question = rewriteResult == null ? null : rewriteResult.rewrittenQuestion();
        List<String> subQuestions = rewriteResult == null ? List.of() : rewriteResult.subQuestions();
        PromptContext promptContext = PromptContext.builder()
                .question(question)
                .mcpContext(retrievalContext.getMcpContext())
                .kbContext(retrievalContext.getKbContext())
                .mcpIntents(mergedGroup.mcpIntents())
                .kbIntents(mergedGroup.kbIntents())
                .intentChunks(retrievalContext.getIntentChunks())
                .build();
        List<ChatMessage> messages = promptBuilder.buildStructuredMessages(
                promptContext,
                List.of(),
                question,
                subQuestions
        );
        ChatRequest request = ChatRequest.builder()
                .scene("eval-rag-answer")
                .messages(messages)
                .thinking(false)
                .temperature(retrievalContext.hasMcp() ? 0.3D : 0D)
                .topP(retrievalContext.hasMcp() ? 0.8D : 1D)
                .build();
        return llmService.chat(request);
    }

    /**
     * 摊平 intentChunks，按 chunk id 去重并保留首次顺序。
     */
    private List<RetrievedChunk> flattenChunks(RetrievalContext retrievalContext) {
        if (retrievalContext == null || CollUtil.isEmpty(retrievalContext.getIntentChunks())) {
            return Collections.emptyList();
        }
        Set<String> seen = new LinkedHashSet<>();
        return retrievalContext.getIntentChunks().values().stream()
                .filter(CollUtil::isNotEmpty)
                .flatMap(List::stream)
                .filter(chunk -> chunk != null && StrUtil.isNotBlank(chunk.getId()))
                .filter(chunk -> seen.add(chunk.getId()))
                .collect(Collectors.toList());
    }

    /**
     * 链路：chunkId -> t_knowledge_chunk.doc_id -> t_knowledge_document.doc_name -> 业务 docId。
     */
    private List<String> resolveContextDocIds(List<RetrievedChunk> chunks) {
        if (CollUtil.isEmpty(chunks)) {
            return Collections.emptyList();
        }
        List<String> chunkIdsForLookup = chunks.stream()
                .map(RetrievedChunk::getId)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .collect(Collectors.toList());
        if (chunkIdsForLookup.isEmpty()) {
            return new ArrayList<>(Collections.nCopies(chunks.size(), null));
        }

        Map<String, String> chunkIdToInternalDocId = knowledgeChunkMapper.selectByIds(chunkIdsForLookup).stream()
                .filter(chunk -> StrUtil.isNotBlank(chunk.getId()) && StrUtil.isNotBlank(chunk.getDocId()))
                .collect(Collectors.toMap(
                        KnowledgeChunkDO::getId,
                        KnowledgeChunkDO::getDocId,
                        (left, right) -> left));
        List<String> internalDocIds = chunkIdToInternalDocId.values().stream().distinct().collect(Collectors.toList());
        Map<String, String> internalToBizDocId = internalDocIds.isEmpty()
                ? Map.of()
                : knowledgeDocumentMapper.selectByIds(internalDocIds).stream()
                        .filter(document -> StrUtil.isNotBlank(document.getId()) && StrUtil.isNotBlank(document.getDocName()))
                        .collect(Collectors.toMap(
                                KnowledgeDocumentDO::getId,
                                document -> stripExtension(document.getDocName()),
                                (left, right) -> left));

        return chunks.stream()
                .map(chunk -> {
                    if (StrUtil.isBlank(chunk.getId())) {
                        return null;
                    }
                    String internalDocId = chunkIdToInternalDocId.get(chunk.getId());
                    if (StrUtil.isBlank(internalDocId)) {
                        return null;
                    }
                    return internalToBizDocId.get(internalDocId);
                })
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static String stripExtension(String docName) {
        if (docName == null) {
            return null;
        }
        int dot = docName.lastIndexOf('.');
        return (dot > 0 && dot < docName.length() - 1) ? docName.substring(0, dot) : docName;
    }

    private List<String> dedupNonBlank(List<String> input) {
        if (CollUtil.isEmpty(input)) {
            return Collections.emptyList();
        }
        Set<String> seen = new LinkedHashSet<>();
        return input.stream()
                .filter(StrUtil::isNotBlank)
                .filter(seen::add)
                .collect(Collectors.toList());
    }

    private List<String> extractSubQuestions(List<SubQuestionIntent> intents) {
        if (CollUtil.isEmpty(intents)) {
            return Collections.emptyList();
        }
        return intents.stream()
                .map(SubQuestionIntent::subQuestion)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());
    }

    private List<String> extractTopLeafIds(List<SubQuestionIntent> intents) {
        if (CollUtil.isEmpty(intents)) {
            return Collections.emptyList();
        }
        return intents.stream()
                .map(intent -> {
                    if (CollUtil.isEmpty(intent.nodeScores()) || intent.nodeScores().get(0).getNode() == null) {
                        return null;
                    }
                    return intent.nodeScores().get(0).getNode().getId();
                })
                .collect(Collectors.toList());
    }

    private List<String> extractMcpToolIds(List<SubQuestionIntent> intents) {
        if (CollUtil.isEmpty(intents)) {
            return Collections.emptyList();
        }
        return intents.stream()
                .filter(intent -> CollUtil.isNotEmpty(intent.nodeScores()))
                .flatMap(intent -> NodeScoreFilters.mcp(intent.nodeScores()).stream())
                .map(NodeScore::getNode)
                .filter(node -> node != null && StrUtil.isNotBlank(node.getMcpToolId()))
                .map(node -> node.getMcpToolId())
                .distinct()
                .collect(Collectors.toList());
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}

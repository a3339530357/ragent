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

package com.nageoffer.ai.ragent.knowledge.service.impl;

import cn.hutool.crypto.SecureUtil;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.core.chunk.model.Chunk;
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.core.ingest.VectorTarget;
import com.nageoffer.ai.ragent.core.ingest.embed.ChunkEmbeddingService;
import com.nageoffer.ai.ragent.infra.token.TokenCounterService;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkCreateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.support.VectorTargetResolver;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static com.nageoffer.ai.ragent.knowledge.support.DedupQueryTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeChunkDedupTest {
    @Mock private KnowledgeChunkMapper chunkMapper;
    @Mock private KnowledgeDocumentMapper documentMapper;
    @Mock private KnowledgeBaseMapper baseMapper;
    @Mock private ChunkEmbeddingService embeddings;
    @Mock private VectorTargetResolver targets;
    @Mock private TokenCounterService tokens;
    @Mock private VectorStoreService vectors;
    @Mock private BizChangeLogContext audit;
    @InjectMocks private KnowledgeChunkServiceImpl service;
    private AutoCloseable mocks;
    private final VectorTarget target = new VectorTarget("collection", "model", 1);

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        init(KnowledgeChunkDO.class);
        init(KnowledgeDocumentDO.class);
        when(documentMapper.selectById("doc")).thenReturn(KnowledgeDocumentDO.builder()
                .id("doc").kbId("kb").enabled(1).status("success").build());
        KnowledgeBaseDO kb = KnowledgeBaseDO.builder().id("kb").collectionName("collection").embeddingModel("model").build();
        when(baseMapper.selectById("kb")).thenReturn(kb);
        when(targets.resolve(kb)).thenReturn(target);
        when(embeddings.embed(anyList(), eq(target))).thenAnswer(invocation -> {
            List<Chunk> chunks = invocation.getArgument(0);
            return chunks.stream().map(chunk -> new EmbeddedChunk(chunk, new float[]{1f})).toList();
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    @Test
    void manualCreateMarksDuplicateAndStillEmbedsAndIndexes() {
        when(chunkMapper.selectList(any())).thenReturn(List.of(KnowledgeChunkDO.builder()
                .id("original").contentHash(SecureUtil.sha256("same")).build()));
        KnowledgeChunkCreateRequest request = new KnowledgeChunkCreateRequest();
        request.setChunkId("new");
        request.setContent("same");
        service.create("doc", request);
        ArgumentCaptor<KnowledgeChunkDO> saved = ArgumentCaptor.forClass(KnowledgeChunkDO.class);
        verify(chunkMapper).insert(saved.capture());
        assertEquals("original", saved.getValue().getDuplicateOf());
        verify(embeddings).embed(anyList(), eq(target));
        verify(vectors).indexDocumentChunks(eq("collection"), eq("doc"), argThat(chunks ->
                chunks.size() == 1 && chunks.get(0).chunkId().equals("new")));
    }

    @Test
    void manualEditRecomputesDuplicateAndExcludesItself() {
        KnowledgeChunkDO row = currentChunk();
        when(chunkMapper.selectById("edited")).thenReturn(row);
        when(chunkMapper.selectList(any())).thenAnswer(invocation -> {
            AbstractWrapper<?, ?, ?> query = invocation.getArgument(0);
            assertEquals("kb", equalValue(query, "kb_id"));
            assertEquals(0, equalValue(query, "deleted"));
            assertTrue(query.getSqlSegment().contains("id NOT IN"));
            assertTrue(query.getParamNameValuePairs().containsValue("edited"));
            assertTrue(query.getParamNameValuePairs().containsValue(SecureUtil.sha256("same")));
            return List.of(KnowledgeChunkDO.builder().id("original").contentHash(SecureUtil.sha256("same")).build());
        });
        KnowledgeChunkUpdateRequest request = new KnowledgeChunkUpdateRequest();
        request.setContent("same");
        service.update("doc", "edited", request);
        assertEquals("original", row.getDuplicateOf());
        verify(vectors).updateChunk(eq("collection"), eq("doc"), any());
    }

    @Test
    void editToUniqueContentClearsOldMarkerInSql() {
        KnowledgeChunkDO row = currentChunk();
        row.setDuplicateOf("old-original");
        when(chunkMapper.selectById("edited")).thenReturn(row);
        when(chunkMapper.selectList(any())).thenReturn(List.of());
        KnowledgeChunkUpdateRequest request = new KnowledgeChunkUpdateRequest();
        request.setContent("unique");
        service.update("doc", "edited", request);
        assertNull(row.getDuplicateOf());
        verify(chunkMapper).updateById(row);
        var field = TableInfoHelper.getTableInfo(KnowledgeChunkDO.class).getFieldList().stream()
                .filter(info -> info.getProperty().equals("duplicateOf")).findFirst().orElseThrow();
        assertEquals(FieldStrategy.ALWAYS, field.getUpdateStrategy());
        assertFalse(field.getSqlSet(false, "et.").contains("<if"));
    }

    private KnowledgeChunkDO currentChunk() {
        return KnowledgeChunkDO.builder().id("edited").docId("doc").kbId("kb")
                .content("old").enabled(1).chunkIndex(0).build();
    }
}

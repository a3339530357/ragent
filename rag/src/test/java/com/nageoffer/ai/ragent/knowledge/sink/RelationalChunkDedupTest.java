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

package com.nageoffer.ai.ragent.knowledge.sink;

import cn.hutool.crypto.SecureUtil;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkAssembler;
import com.nageoffer.ai.ragent.core.chunk.model.EmbeddedChunk;
import com.nageoffer.ai.ragent.core.ingest.DocumentRef;
import com.nageoffer.ai.ragent.core.ingest.VectorTarget;
import com.nageoffer.ai.ragent.core.ingest.sink.ChunkIndexWriter;
import com.nageoffer.ai.ragent.infra.token.TokenCounterService;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import com.nageoffer.ai.ragent.rag.core.vector.sink.VectorChunkSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static com.nageoffer.ai.ragent.knowledge.support.DedupQueryTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RelationalChunkDedupTest {
    private final KnowledgeChunkMapper mapper = mock(KnowledgeChunkMapper.class);
    private final VectorStoreService vectors = mock(VectorStoreService.class);
    private final RelationalChunkSink sink = new RelationalChunkSink(mapper, mock(TokenCounterService.class));
    private final List<KnowledgeChunkDO> existing = new ArrayList<>();
    private final VectorTarget target = new VectorTarget("collection", "embedding", 1);

    @BeforeEach
    void setUp() {
        init(KnowledgeChunkDO.class);
        when(mapper.selectList(any())).thenAnswer(invocation -> {
            AbstractWrapper<?, ?, ?> query = invocation.getArgument(0);
            Object kb = equalValue(query, "kb_id");
            Object deleted = equalValue(query, "deleted");
            assertEquals(0, deleted);
            assertTrue(query.getSqlSegment().contains("content_hash IN"));
            assertTrue(query.getParamNameValuePairs().containsValue(SecureUtil.sha256("same")));
            assertTrue(query.getSqlSegment().contains("ORDER BY create_time ASC,id ASC"));
            return existing.stream().filter(row -> Objects.equals(kb, row.getKbId())
                    && Objects.equals(deleted, row.getDeleted())).toList();
        });
    }

    @Test
    void existingDuplicatesReferenceEarliestLiveChunk() {
        existing.add(row("earliest", "kb", 0));
        existing.add(row("later", "kb", 0));
        sink.replaceDocument(target, DocumentRef.of("doc", "kb"), List.of(chunk("new")));
        assertEquals("earliest", inserted().get(0).getDuplicateOf());
    }

    @Test
    void duplicatesWithinBatchAreMarkedAndAllReachVectorSink() {
        List<EmbeddedChunk> chunks = List.of(chunk("first"), chunk("second"), chunk("third"));
        ChunkIndexWriter writer = new ChunkIndexWriter(List.of(sink, new VectorChunkSink(vectors)),
                TransactionOperations.withoutTransaction());
        writer.replaceDocument(target, DocumentRef.of("doc", "kb"), chunks);
        List<KnowledgeChunkDO> rows = inserted();
        assertEquals(3, rows.size());
        assertNull(rows.get(0).getDuplicateOf());
        assertEquals("first", rows.get(1).getDuplicateOf());
        assertEquals("first", rows.get(2).getDuplicateOf());
        verify(vectors).indexDocumentChunks("collection", "doc", chunks);
    }

    @Test
    void otherKnowledgeBaseAndDeletedChunksDoNotMarkDuplicates() {
        existing.add(row("other", "other-kb", 0));
        existing.add(row("deleted", "kb", 1));
        sink.replaceDocument(target, DocumentRef.of("doc", "kb"), List.of(chunk("new")));
        assertNull(inserted().get(0).getDuplicateOf());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<KnowledgeChunkDO> inserted() {
        ArgumentCaptor<Collection<KnowledgeChunkDO>> captor = ArgumentCaptor.forClass((Class) Collection.class);
        verify(mapper).insert(captor.capture());
        return new ArrayList<>(captor.getValue());
    }

    private KnowledgeChunkDO row(String id, String kb, int deleted) {
        return KnowledgeChunkDO.builder().id(id).kbId(kb).contentHash(SecureUtil.sha256("same"))
                .deleted(deleted).build();
    }

    private EmbeddedChunk chunk(String id) {
        return new EmbeddedChunk(ChunkAssembler.restore(id, 0, "same", "same"), new float[]{1f});
    }
}

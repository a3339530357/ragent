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

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeDocumentPageRequest;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;

import static com.nageoffer.ai.ragent.knowledge.support.DedupQueryTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeDocumentDuplicateCountTest {
    @Mock private KnowledgeDocumentMapper documentMapper;
    @Mock private KnowledgeChunkMapper chunkMapper;
    @InjectMocks private KnowledgeDocumentServiceImpl service;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        init(KnowledgeDocumentDO.class);
        init(KnowledgeChunkDO.class);
        when(chunkMapper.selectMaps(any())).thenAnswer(invocation -> {
            QueryWrapper<KnowledgeChunkDO> query = invocation.getArgument(0);
            assertTrue(query.getSqlSelect().contains("COUNT(*) AS duplicate_count"));
            assertTrue(query.getSqlSegment().contains("duplicate_of IS NOT NULL"));
            assertTrue(query.getSqlSegment().contains("doc_id IN"));
            assertTrue(query.getSqlSegment().contains("GROUP BY doc_id"));
            assertEquals(0, equalValue(query, "deleted"));
            return List.of(Map.of("doc_id", "duplicate-doc", "duplicate_count", 2L));
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    @Test
    void pageCountsDuplicatesInOneGroupedQueryAndDefaultsToZero() {
        Page<KnowledgeDocumentDO> page = new Page<>(1, 10, 2);
        page.setRecords(List.of(document("duplicate-doc"), document("unique-doc")));
        when(documentMapper.selectPage(any(Page.class), any())).thenReturn(page);
        var result = service.page("kb", new KnowledgeDocumentPageRequest());
        assertEquals(2L, result.getRecords().get(0).getDuplicateChunkCount());
        assertEquals(0L, result.getRecords().get(1).getDuplicateChunkCount());
        verify(chunkMapper, times(1)).selectMaps(any());
    }

    @Test
    void documentDetailAlsoReturnsDuplicateCount() {
        when(documentMapper.selectById("duplicate-doc")).thenReturn(document("duplicate-doc"));
        assertEquals(2L, service.get("duplicate-doc").getDuplicateChunkCount());
    }

    private KnowledgeDocumentDO document(String id) {
        return KnowledgeDocumentDO.builder().id(id).kbId("kb").docName(id).build();
    }
}

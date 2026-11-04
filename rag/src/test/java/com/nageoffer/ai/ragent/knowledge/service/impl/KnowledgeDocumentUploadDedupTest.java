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
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.core.parser.registry.ParserRegistry;
import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeDocumentUploadRequest;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.handler.RemoteFileFetcher;
import com.nageoffer.ai.ragent.knowledge.support.IngestionSpecCodec;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.nageoffer.ai.ragent.knowledge.support.DedupQueryTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeDocumentUploadDedupTest {
    @Mock private KnowledgeBaseMapper baseMapper;
    @Mock private KnowledgeDocumentMapper documentMapper;
    @Mock private FileStorageService storage;
    @Mock private ParserRegistry parserRegistry;
    @Mock private IngestionSpecCodec codec;
    @Mock private BizChangeLogContext audit;
    @Mock private RemoteFileFetcher remote;
    @InjectMocks private KnowledgeDocumentServiceImpl service;
    private AutoCloseable mocks;
    private final List<KnowledgeDocumentDO> existing = new ArrayList<>();
    private final MockMultipartFile file = new MockMultipartFile("file", "new.md", "text/markdown",
            "same bytes".getBytes(StandardCharsets.UTF_8));
    private final KnowledgeDocumentUploadRequest request = new KnowledgeDocumentUploadRequest();

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        init(KnowledgeDocumentDO.class);
        request.setSourceType("file");
        request.setProcessMode("chunk");
        when(baseMapper.selectById("kb")).thenReturn(KnowledgeBaseDO.builder().id("kb").collectionName("collection").build());
        when(documentMapper.selectOne(any())).thenAnswer(invocation -> {
            AbstractWrapper<?, ?, ?> query = invocation.getArgument(0);
            Object kb = equalValue(query, "kb_id");
            Object hash = equalValue(query, "file_hash");
            Object deleted = equalValue(query, "deleted");
            assertEquals(0, deleted);
            return existing.stream().filter(row -> Objects.equals(kb, row.getKbId())
                    && Objects.equals(hash, row.getFileHash()) && Objects.equals(deleted, row.getDeleted()))
                    .findFirst().orElse(null);
        });
        when(storage.upload(anyString(), any())).thenReturn(StoredFileDTO.builder()
                .url("/file").detectedType("md").mimeType("text/markdown").originalFilename("new.md").size(10L).build());
        when(parserRegistry.canParse("text/markdown")).thenReturn(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    @Test
    void duplicateHashIsRejectedBeforeStorage() {
        existing.add(document("kb", 0));
        ClientException error = assertThrows(ClientException.class, () -> service.upload("kb", request, file));
        assertEquals(BaseErrorCode.KNOWLEDGE_FILE_DUPLICATE.code(), error.getErrorCode());
        assertTrue(error.getMessage().contains("original.md"));
        verifyNoInteractions(storage);
        verify(documentMapper, never()).insert(any(KnowledgeDocumentDO.class));
    }

    @Test
    void explicitConfirmationAllowsDuplicateAndPersistsHash() {
        existing.add(document("kb", 0));
        request.setAllowDuplicate(true);
        service.upload("kb", request, file);
        verify(documentMapper, never()).selectOne(any());
        assertStoredHash();
    }

    @Test
    void otherKnowledgeBaseDoesNotBlockUpload() {
        existing.add(document("other-kb", 0));
        service.upload("kb", request, file);
        assertStoredHash();
    }

    @Test
    void deletedDocumentDoesNotBlockUpload() {
        existing.add(document("kb", 1));
        service.upload("kb", request, file);
        assertStoredHash();
    }

    @Test
    void historicalNullHashDoesNotBlockUpload() {
        KnowledgeDocumentDO old = document("kb", 0);
        old.setFileHash(null);
        existing.add(old);
        service.upload("kb", request, file);
        assertStoredHash();
    }

    @Test
    void hashUsesStreamWithoutReadingWholeFile() throws Exception {
        var streamed = spy(file);
        doThrow(new AssertionError("不得整文件读入内存")).when(streamed).getBytes();
        service.upload("kb", request, streamed);
        verify(streamed).getInputStream();
        assertStoredHash();
    }

    @Test
    void remoteUploadDoesNotUseLocalHashCheck() {
        request.setSourceType("url");
        request.setSourceLocation("https://example.com/file.md");
        when(remote.fetchAndStore(anyString(), anyString())).thenReturn(StoredFileDTO.builder()
                .url("/remote").detectedType("md").mimeType("text/markdown").originalFilename("remote.md").build());
        service.upload("kb", request, null);
        verify(documentMapper, never()).selectOne(any());
        ArgumentCaptor<KnowledgeDocumentDO> row = ArgumentCaptor.forClass(KnowledgeDocumentDO.class);
        verify(documentMapper).insert(row.capture());
        assertNull(row.getValue().getFileHash());
    }

    private KnowledgeDocumentDO document(String kb, int deleted) {
        return KnowledgeDocumentDO.builder().id("old").kbId(kb).docName("original.md")
                .fileHash(SecureUtil.sha256("same bytes")).deleted(deleted).build();
    }

    private void assertStoredHash() {
        ArgumentCaptor<KnowledgeDocumentDO> row = ArgumentCaptor.forClass(KnowledgeDocumentDO.class);
        verify(documentMapper).insert(row.capture());
        assertEquals(SecureUtil.sha256("same bytes"), row.getValue().getFileHash());
        verify(storage).upload(eq("collection"), any());
    }
}

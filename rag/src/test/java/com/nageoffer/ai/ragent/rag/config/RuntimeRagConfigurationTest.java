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

package com.nageoffer.ai.ragent.rag.config;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.runtime.*;

import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

class RuntimeRagConfigurationTest {
    private final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void clear() {
        RuntimeConfigContext.activate(null);
        RuntimeConfigContext.setLocal(null);
    }

    private RuntimeConfigSnapshot snapshot(
            long id, SearchChannelProperties search, RAGConfigProperties flags) {
        return new RuntimeConfigSnapshot(
                id,
                json.createObjectNode(),
                Map.of(SearchChannelProperties.class, search, RAGConfigProperties.class, flags));
    }

    private RAGConfigProperties flags() {
        var p = new RAGConfigProperties();
        p.setQueryRewriteEnabled(true);
        p.setRerankEnabled(true);
        p.setCitationEnabled(false);
        p.setContextEnrichEnabled(true);
        return p;
    }

    @Test
    void budgetsAndFeaturesSwitchTogetherWithoutMutatingOldSnapshot() {
        var old = new SearchChannelProperties();
        var oldFlags = flags();
        var next = new SearchChannelProperties();
        next.setDefaultTopK(5);
        next.setRecallBudget(30);
        var newFlags = flags();
        newFlags.setCitationEnabled(true);
        var one = snapshot(1, old, oldFlags);
        var two = snapshot(2, next, newFlags);
        RuntimeConfigContext.activate(one);
        var injected = new SearchChannelProperties();
        var injectedFlags = new RAGConfigProperties();
        try (var ignored = RuntimeConfigContext.use(one)) {
            RuntimeConfigContext.activate(two);
            assertEquals(10, injected.getDefaultTopK());
            assertFalse(injectedFlags.getCitationEnabled());
        }
        assertEquals(5, injected.getDefaultTopK());
        assertEquals(30, injected.resolveRecallBudget(5));
        assertTrue(injectedFlags.getCitationEnabled());
    }

    @Test
    void incompatiblePipelineFlagsAndUnavailableBackendsAreRejected() {
        var p = new SearchChannelProperties();
        var flags = flags();
        var snapshot = snapshot(1, p, flags);
        var contributor =
                new RuntimeRagConfiguration()
                        .runtimeRagSearch(
                                p,
                                new MockEnvironment()
                                        .withProperty("rag.keyword.type", "none")
                                        .withProperty("rag.graph.type", "none"));
        try (var ignored = RuntimeConfigContext.use(snapshot)) {
            p.setRecallBudget(1);
            assertThrows(
                    IllegalStateException.class,
                    () -> contributor.validate(snapshot, json.createObjectNode()));
            p.setRecallBudget(20);
            p.getChannels().getKeyword().setEnabled(true);
            assertThrows(
                    RuntimeException.class,
                    () -> contributor.validate(snapshot, json.createObjectNode()));
            p.getChannels().getKeyword().setEnabled(false);
            p.getEvidence().setMinRerankScore(0.4);
            flags.setRerankEnabled(false);
            assertThrows(
                    IllegalStateException.class,
                    () -> contributor.validate(snapshot, json.createObjectNode()));
        }
    }
}

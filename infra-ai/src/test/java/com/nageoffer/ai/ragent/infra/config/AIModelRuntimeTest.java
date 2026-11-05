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

package com.nageoffer.ai.ragent.infra.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nageoffer.ai.ragent.framework.convention.*;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.runtime.*;
import com.nageoffer.ai.ragent.infra.chat.*;
import com.nageoffer.ai.ragent.infra.model.*;
import com.nageoffer.ai.ragent.infra.rerank.*;
import com.sun.net.httpserver.HttpServer;

import okhttp3.OkHttpClient;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

class AIModelRuntimeTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private RuntimeChatClientRegistry clients;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();

    @BeforeEach
    void setup() throws Exception {
        RuntimeConfigContext.activate(null);
        RuntimeConfigContext.setLocal(null);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    body.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    path.set(exchange.getRequestURI().getPath());
                    byte[] response =
                            "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        var factory = mock(AutowireCapableBeanFactory.class);
        doAnswer(
                        inv -> {
                            ReflectionTestUtils.setField(
                                    (Object) inv.getArgument(0),
                                    "syncHttpClient",
                                    new OkHttpClient());
                            return null;
                        })
                .when(factory)
                .autowireBean(any());
        when(factory.initializeBean(any(), anyString())).thenAnswer(inv -> inv.getArgument(0));
        clients = new RuntimeChatClientRegistry(List.of(), factory);
    }

    @AfterEach
    void cleanup() {
        server.stop(0);
        RuntimeConfigContext.activate(null);
        RuntimeConfigContext.setLocal(null);
    }

    private AIModelProperties settings(String key, String endpoint) {
        AIModelProperties p = new AIModelProperties();
        var provider = new AIModelProperties.ProviderConfig();
        provider.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        provider.setApiKey(key);
        provider.setEndpoints(Map.of("chat", endpoint, "embedding", "/embedding"));
        p.setProviders(new HashMap<>(Map.of("custom", provider)));
        var model = new AIModelProperties.ModelCandidate();
        model.setId("test-model");
        model.setModel("remote-model");
        model.setProvider("custom");
        model.setSupportsThinking(true);
        p.getChat().setCandidates(new ArrayList<>(List.of(model)));
        p.getChat().setDefaultTier("standard");
        p.getChat().setDeepThinkingTier("deep");
        for (String tier : List.of("fast", "standard", "deep")) {
            var value = new AIModelProperties.TierConfig();
            value.setCandidates(List.of("test-model"));
            value.setTimeoutMs(1000L);
            p.getChat().getTiers().put(tier, value);
        }
        return p;
    }

    private RuntimeConfigSnapshot snapshot(long id, AIModelProperties p) {
        ObjectNode root = json.createObjectNode();
        try (var ignored = RuntimeConfigContext.use(null)) {
            root.set("ai", json.valueToTree(p));
        }
        return new RuntimeConfigSnapshot(id, root, Map.of(AIModelProperties.class, p));
    }

    @Test
    void runningTaskKeepsOldAddressAndKeyWhileNewTaskUsesPublishedConfig() {
        var first = settings("first-secret", "/first");
        var second = settings("second-secret", "/second");
        // 在激活任何版本前构造两个快照，模拟配置中心发布准备
        var one = snapshot(1, first);
        var two = snapshot(2, second);
        RuntimeConfigContext.activate(one);
        AIModelProperties startup = new AIModelProperties();
        var selector = new ModelSelector(startup, new ModelHealthStore(startup));
        try (var ignored = RuntimeConfigContext.use(one)) {
            RuntimeConfigContext.activate(two);
            invoke(selector);
            assertEquals("Bearer first-secret", authorization.get());
            assertEquals("/first", path.get());
        }
        invoke(selector);
        assertEquals("Bearer second-secret", authorization.get());
        assertEquals("/second", path.get());
        assertTrue(body.get().contains("remote-model"));
    }

    private void invoke(ModelSelector selector) {
        var target = selector.selectChatCandidates(false).get(0);
        assertEquals(
                "OK",
                clients.resolve(target.candidate().getProvider())
                        .chat(
                                ChatRequest.builder()
                                        .messages(List.of(ChatMessage.user("固定测试内容")))
                                        .thinking(false)
                                        .build(),
                                target));
    }

    @Test
    void circuitStateDoesNotLeakIntoNewConfiguration() {
        var first = settings("one", "/first");
        var second = settings("two", "/second");
        var one = snapshot(1, first);
        var two = snapshot(2, second);
        RuntimeConfigContext.activate(one);
        var health = new ModelHealthStore(new AIModelProperties());
        health.markFailure("test-model");
        health.markFailure("test-model");
        assertTrue(health.isUnavailable("test-model"));
        RuntimeConfigContext.activate(two);
        assertFalse(health.isUnavailable("test-model"));
        try (var ignored = RuntimeConfigContext.use(one)) {
            assertTrue(health.isUnavailable("test-model"));
        }
    }

    @Test
    void invalidReferencesAndEmbeddingServiceChangesAreRejected() {
        var p = settings("secret", "/chat");
        var embed = new AIModelProperties.ModelCandidate();
        embed.setId("embedding");
        embed.setProvider("custom");
        embed.setModel("embedding");
        embed.setDimension(1024);
        p.getEmbedding().setCandidates(List.of(embed));
        p.getEmbedding().setDefaultModel("embedding");
        var baseline = json.valueToTree(p);
        var contributor =
                new AIModelRuntimeContributor(p, json, clients, List.of(new NoopRerankClient()));
        p.getProviders().get("custom").setApiKey("rotated");
        var rotated = snapshot(1, p);
        try (var ignored = RuntimeConfigContext.use(rotated)) {
            assertDoesNotThrow(() -> contributor.validate(rotated, baseline));
        }
        p.getProviders().get("custom").setUrl("https://different.example.com");
        var changed = snapshot(2, p);
        try (var ignored = RuntimeConfigContext.use(changed)) {
            assertThrows(ClientException.class, () -> contributor.validate(changed, baseline));
        }
        p.getProviders().remove("custom");
        var removed = snapshot(3, p);
        try (var ignored = RuntimeConfigContext.use(removed)) {
            assertThrows(ClientException.class, () -> contributor.validate(removed, baseline));
        }
    }

    @Test
    void disabledOnlyTierAndUnsupportedRerankCannotPublish() {
        var p = settings("secret", "/chat");
        var baseline = json.valueToTree(p);
        var contributor =
                new AIModelRuntimeContributor(p, json, clients, List.of(new NoopRerankClient()));
        p.getChat().getCandidates().get(0).setEnabled(false);
        var disabled = snapshot(1, p);
        try (var ignored = RuntimeConfigContext.use(disabled)) {
            assertThrows(RuntimeException.class, () -> contributor.validate(disabled, baseline));
        }
        p.getChat().getCandidates().get(0).setEnabled(true);
        p.getRerank().setCandidates(p.getChat().getCandidates());
        var unsupported = snapshot(2, p);
        try (var ignored = RuntimeConfigContext.use(unsupported)) {
            assertThrows(ClientException.class, () -> contributor.validate(unsupported, baseline));
        }
    }

    @Test
    void modelSpecificUrlStillWorksWithoutProviderBaseUrl() {
        var p = settings("secret", "/chat");
        p.getChat()
                .getCandidates()
                .get(0)
                .setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/override");
        p.getProviders().get("custom").setUrl(null);
        p.getProviders().get("custom").setEndpoints(Map.of());
        var baseline = json.valueToTree(p);
        var snapshot = snapshot(1, p);
        var contributor =
                new AIModelRuntimeContributor(p, json, clients, List.of(new NoopRerankClient()));
        try (var ignored = RuntimeConfigContext.use(snapshot)) {
            assertDoesNotThrow(() -> contributor.validate(snapshot, baseline));
            var selector = new ModelSelector(p, new ModelHealthStore(p));
            invoke(selector);
            assertEquals("/override", path.get());
        }
    }
}

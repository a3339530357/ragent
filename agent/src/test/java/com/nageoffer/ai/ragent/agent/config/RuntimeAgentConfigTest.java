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

package com.nageoffer.ai.ragent.agent.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.runtime.*;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;

import io.agentscope.core.model.ChatResponse;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

import org.junit.jupiter.api.*;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

class RuntimeAgentConfigTest {
    @BeforeEach
    void setup() {
        RuntimeConfigContext.activate(null);
        RuntimeConfigContext.setLocal(null);
        new RuntimeConfigReactorBridge().install();
    }

    @AfterEach
    void clear() {
        RuntimeConfigContext.activate(null);
        RuntimeConfigContext.setLocal(null);
    }

    private RuntimeConfigSnapshot snapshot(long id) {
        var chat = new AgentProperties.Chat();
        chat.setModel("agent-" + id);
        chat.setProvider("provider-" + id);
        var ai = new AIModelProperties();
        return new RuntimeConfigSnapshot(
                id,
                new ObjectMapper().createObjectNode(),
                Map.of(AgentProperties.Chat.class, chat, AIModelProperties.class, ai));
    }

    @Test
    void reactorToolWorkKeepsOriginalVersionAcrossSchedulerAndPublish() throws Exception {
        var one = snapshot(1);
        var two = snapshot(2);
        RuntimeConfigContext.activate(one);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        CompletableFuture<List<Long>> work =
                Flux.range(0, 3)
                        .flatMap(
                                i ->
                                        reactor.core.publisher.Mono.fromCallable(
                                                        () -> {
                                                            started.countDown();
                                                            assertTrue(
                                                                    release.await(
                                                                            5, TimeUnit.SECONDS));
                                                            // 模拟工具内部的同步检索：必须看见本轮快照，而非发布后的全局配置
                                                            assertEquals(
                                                                    "agent-1",
                                                                    new AgentProperties()
                                                                            .getChat()
                                                                            .getModel());
                                                            return RuntimeConfigContext.current()
                                                                    .version();
                                                        })
                                                .subscribeOn(Schedulers.boundedElastic()))
                        .contextWrite(context -> context.put(RuntimeConfigContext.KEY, one))
                        .collectList()
                        .toFuture();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        RuntimeConfigContext.activate(two);
        release.countDown();
        assertEquals(List.of(1L, 1L, 1L), work.get(5, TimeUnit.SECONDS));
        assertEquals(2, RuntimeConfigContext.current().version());
        assertEquals(
                "clean",
                reactor.core.publisher.Mono.fromCallable(
                                () -> RuntimeConfigContext.local() == null ? "clean" : "dirty")
                        .subscribeOn(Schedulers.boundedElastic())
                        .block());
    }

    @Test
    void modelDelegationKeepsCapturedClientAndBuildsOnlyOncePerVersion() {
        var one = snapshot(1);
        var two = snapshot(2);
        RuntimeConfigContext.activate(one);
        List<String> built = new ArrayList<>();
        RuntimeAgentModel runtime =
                new RuntimeAgentModel(
                        () -> {
                            String name = new AgentProperties().getChat().getModel();
                            built.add(name);
                            var model = mock(OpenAIChatModel.class);
                            when(model.getModelName()).thenReturn(name);
                            when(model.stream(anyList(), isNull(), isNull()))
                                    .thenReturn(Flux.just(mock(ChatResponse.class)));
                            return model;
                        });
        var original = runtime.currentModel();
        var inFlight = runtime.stream(List.of(), null, null);
        RuntimeConfigContext.activate(two);
        assertEquals("agent-2", runtime.getModelName());
        assertEquals("agent-2", runtime.getModelName());
        assertEquals(1, inFlight.collectList().block(Duration.ofSeconds(2)).size());
        try (var ignored = RuntimeConfigContext.use(one)) {
            assertSame(original, runtime.currentModel());
            assertEquals("agent-1", runtime.getModelName());
        }
        assertEquals(List.of("agent-1", "agent-2"), built);
    }

    @Test
    void realAgentToolLoopKeepsSnapshotWhenConfigurationIsPublishedInsideTool() {
        var first = snapshot(1);
        var second = snapshot(2);
        RuntimeConfigContext.activate(first);
        var facade = mock(com.nageoffer.ai.ragent.rag.service.KnowledgeSearchFacade.class);
        java.util.concurrent.atomic.AtomicInteger toolCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        when(facade.search(anyString()))
                .thenAnswer(
                        invocation -> {
                            assertEquals(1, RuntimeConfigContext.current().version());
                            RuntimeConfigContext.activate(second);
                            toolCalls.incrementAndGet();
                            return "固定检索结果";
                        });
        var toolkit = new io.agentscope.core.tool.Toolkit();
        toolkit.registerAgentTool(
                new com.nageoffer.ai.ragent.agent.tool.KnowledgeSearchTool("查询资料", facade));
        var model = mock(OpenAIChatModel.class);
        when(model.getModelName()).thenReturn("test-model");
        java.util.concurrent.atomic.AtomicInteger generations =
                new java.util.concurrent.atomic.AtomicInteger();
        when(model.stream(any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            assertEquals(1, RuntimeConfigContext.current().version());
                            int turn = generations.incrementAndGet();
                            if (turn <= 2)
                                return Flux.just(
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                io.agentscope.core.message
                                                                        .ToolUseBlock.builder()
                                                                        .id("lookup-" + turn)
                                                                        .name("search_knowledge")
                                                                        .input(
                                                                                Map.of(
                                                                                        "query",
                                                                                        "测试资料"))
                                                                        .content(
                                                                                "{\"query\":\"测试资料\"}")
                                                                        .build()))
                                                .finishReason("tool_calls")
                                                .build());
                            return Flux.just(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            io.agentscope.core.message.TextBlock
                                                                    .builder()
                                                                    .text("完成")
                                                                    .build()))
                                            .finishReason("stop")
                                            .build());
                        });
        try (var agent =
                io.agentscope.core.ReActAgent.builder()
                        .name("snapshot-test")
                        .sysPrompt("测试")
                        .model(model)
                        .toolkit(toolkit)
                        .maxIters(5)
                        .maxRetries(1)
                        .stateStore(new io.agentscope.core.state.InMemoryAgentStateStore())
                        .build()) {
            agent.streamEvents(
                            "测试",
                            io.agentscope.core.agent.RuntimeContext.builder()
                                    .userId("u")
                                    .sessionId("s")
                                    .build())
                    .contextWrite(context -> context.put(RuntimeConfigContext.KEY, first))
                    .collectList()
                    .block(Duration.ofSeconds(5));
        }
        assertEquals(3, generations.get());
        assertEquals(2, toolCalls.get());
        assertEquals(2, RuntimeConfigContext.current().version());
    }
}

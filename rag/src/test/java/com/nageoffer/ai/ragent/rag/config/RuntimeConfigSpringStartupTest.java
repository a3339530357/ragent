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
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nageoffer.ai.ragent.framework.runtime.RuntimeConfigContext;
import com.nageoffer.ai.ragent.framework.runtime.RuntimeConfigContributor;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.config.AIModelRuntimeContributor;
import com.nageoffer.ai.ragent.infra.config.RuntimeChatClientRegistry;
import com.nageoffer.ai.ragent.settings.RuntimeConfigCipher;
import com.nageoffer.ai.ragent.settings.RuntimeConfigMapper;
import com.nageoffer.ai.ragent.settings.RuntimeConfigService;
import com.nageoffer.ai.ragent.settings.RuntimeConfigState;
import com.nageoffer.ai.ragent.settings.RuntimeConfigVersion;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 使用真实配置类和 Spring 增强代理启动配置中心，外部数据库与模型调用保持隔离。 */
class RuntimeConfigSpringStartupTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties
    static class BindingConfiguration {}

    // 显式包含 Bean 方法，确保测试在精简容器中也触发真实的配置增强。
    @Configuration
    @ConfigurationProperties(prefix = "ai")
    static class EnhancedAIProperties extends AIModelProperties {
        @Bean
        String aiProxyMarker() {
            return "ai";
        }
    }

    @Configuration
    static class EnhancedRagFeatures extends RAGConfigProperties {
        @Bean
        String ragProxyMarker() {
            return "rag";
        }
    }

    @BeforeEach
    @AfterEach
    void clearContext() {
        RuntimeConfigContext.activate(null);
        RuntimeConfigContext.setLocal(null);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 7})
    void startupSerializesDeclaredConfigurationTypesAndRestoresPublishedVersion(long version) {
        ObjectMapper json = new ObjectMapper();
        RuntimeConfigCipher cipher =
                new RuntimeConfigCipher(Base64.getEncoder().encodeToString(new byte[32]));
        RuntimeConfigMapper mapper = mock(RuntimeConfigMapper.class);
        RuntimeConfigState state = new RuntimeConfigState();
        state.setActiveVersion(version);
        state.setDraftRevision(0L);
        state.setDraftBaseVersion(version);
        when(mapper.state()).thenReturn(state);

        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext()) {
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(new MapPropertySource("test", properties()));
            AIModelProperties plain =
                    Binder.get(context.getEnvironment()).bind("ai", AIModelProperties.class).get();
            if (version > 0) {
                plain.getChat().getCandidates().get(0).setModel("published-model");
                RAGConfigProperties features = new RAGConfigProperties();
                features.setQueryRewriteEnabled(true);
                features.setRerankEnabled(false);
                features.setCitationEnabled(false);
                features.setContextEnrichEnabled(true);
                SearchChannelProperties search = new SearchChannelProperties();
                search.getEvidence().setMinRerankScore(0);
                ObjectNode payload = json.createObjectNode();
                payload.set("ai", json.valueToTree(plain));
                payload.set("ragFeatures", json.valueToTree(features));
                payload.set("ragSearch", json.valueToTree(search));
                RuntimeConfigVersion saved = new RuntimeConfigVersion();
                saved.setId(version);
                saved.setSchemaVersion(1);
                saved.setPayload(cipher.encrypt(payload.toString()));
                when(mapper.version(version)).thenReturn(saved);
            }
            context.register(
                    BindingConfiguration.class,
                    EnhancedAIProperties.class,
                    EnhancedRagFeatures.class,
                    SearchChannelProperties.class,
                    RuntimeRagConfiguration.class);
            context.registerBean(
                    "runtimeAi",
                    RuntimeConfigContributor.class,
                    () ->
                            new AIModelRuntimeContributor(
                                    context.getBean(AIModelProperties.class),
                                    json,
                                    mock(RuntimeChatClientRegistry.class),
                                    List.of()));
            context.registerBean(
                    RuntimeConfigService.class,
                    () ->
                            new RuntimeConfigService(
                                    mapper,
                                    cipher,
                                    TransactionOperations.withoutTransaction(),
                                    json,
                                    new ArrayList<>(
                                            context.getBeansOfType(RuntimeConfigContributor.class)
                                                    .values()),
                                    List.of()));

            // refresh 会真实调用 SmartInitializingSingleton，不手工绕过容器生命周期。
            assertDoesNotThrow(context::refresh);
            AIModelProperties enhanced = context.getBean(AIModelProperties.class);
            RAGConfigProperties enhancedFeatures = context.getBean(RAGConfigProperties.class);
            assertNotEquals(AIModelProperties.class, enhanced.getClass());
            assertEquals(EnhancedAIProperties.class, ClassUtils.getUserClass(enhanced));
            assertNotEquals(EnhancedRagFeatures.class, enhancedFeatures.getClass());
            assertTrue(json.isEnabled(SerializationFeature.FAIL_ON_EMPTY_BEANS));

            RuntimeConfigService service = context.getBean(RuntimeConfigService.class);
            assertEquals(version, service.appliedVersion());
            String expectedModel = version == 0 ? "startup-model" : "published-model";
            assertEquals(expectedModel, enhanced.getChat().getCandidates().get(0).getModel());
            ObjectNode document = RuntimeConfigContext.current().document();
            assertFalse(document.toString().contains("$$beanFactory"));
            assertFalse(document.toString().contains("beanExpressionResolver"));
            assertEquals(3, document.size());
            assertDoesNotThrow(() -> service.prepare(version, document));
            assertFalse(service.view().toString().contains("test-private-key"));
            verify(mapper, never()).insert(any());
        }
    }

    private Map<String, Object> properties() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("ai.providers.stub.url", "http://127.0.0.1:1");
        values.put("ai.providers.stub.api-key", "test-private-key");
        values.put("ai.providers.stub.endpoints.chat", "/v1/chat/completions");
        values.put("ai.chat.default-tier", "standard");
        values.put("ai.chat.deep-thinking-tier", "deep");
        values.put("ai.chat.candidates[0].id", "stub-model");
        values.put("ai.chat.candidates[0].provider", "stub");
        values.put("ai.chat.candidates[0].model", "startup-model");
        values.put("ai.chat.candidates[0].supports-thinking", true);
        for (String tier : List.of("fast", "standard", "deep")) {
            values.put("ai.chat.tiers." + tier + ".candidates[0]", "stub-model");
            values.put("ai.chat.tiers." + tier + ".timeout-ms", 1000);
        }
        values.put("rag.rerank.enabled", false);
        values.put("rag.search.evidence.min-rerank-score", 0);
        return values;
    }
}

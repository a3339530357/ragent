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

import com.fasterxml.jackson.databind.JsonNode;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.runtime.*;
import com.nageoffer.ai.ragent.rag.config.validation.RetrievalChannelConfigValidator;
import com.nageoffer.ai.ragent.rag.core.retrieval.postprocessor.EvidenceGatePostProcessor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.List;

@Configuration
public class RuntimeRagConfiguration {
    @Bean
    RuntimeConfigContributor runtimeRagFeatures(RAGConfigProperties properties) {
        return new RuntimeConfigContributor() {
            public String key() {
                return "ragFeatures";
            }

            public Class<?> type() {
                return RAGConfigProperties.class;
            }

            public Object defaults() {
                return properties;
            }

            public void validate(RuntimeConfigSnapshot snapshot, JsonNode baseline) {
                RAGConfigProperties p = snapshot.section(RAGConfigProperties.class);
                if (p.getQueryRewriteEnabled() == null
                        || p.getRerankEnabled() == null
                        || p.getCitationEnabled() == null
                        || p.getContextEnrichEnabled() == null)
                    throw new ClientException("RAG 功能开关不能为空");
            }
        };
    }

    @Bean
    RuntimeConfigContributor runtimeRagSearch(SearchChannelProperties properties, Environment env) {
        return new RuntimeConfigContributor() {
            public String key() {
                return "ragSearch";
            }

            public Class<?> type() {
                return SearchChannelProperties.class;
            }

            public Object defaults() {
                return properties;
            }

            public void validate(RuntimeConfigSnapshot snapshot, JsonNode baseline) {
                SearchChannelProperties p = snapshot.section(SearchChannelProperties.class);
                if (p.getScope() == null
                        || p.getChannels() == null
                        || p.getFusion() == null
                        || p.getEvidence() == null) throw new ClientException("检索配置分组不能为空");
                p.afterPropertiesSet();
                var ai =
                        snapshot.section(
                                com.nageoffer.ai.ragent.infra.config.AIModelProperties.class);
                if (ai != null
                        && Boolean.TRUE.equals(
                                snapshot.section(RAGConfigProperties.class).getRerankEnabled())
                        && ai.getRerank().getCandidates().stream()
                                .noneMatch(c -> !Boolean.FALSE.equals(c.getEnabled())))
                    throw new ClientException("开启重排序时至少需要一个可用的精排候选");
                new EvidenceGatePostProcessor(p, snapshot.section(RAGConfigProperties.class))
                        .afterPropertiesSet();
                var violations =
                        RetrievalChannelConfigValidator.validate(
                                env::getProperty,
                                key ->
                                        key.contains("keyword")
                                                ? p.getChannels().getKeyword().isEnabled()
                                                : p.getChannels().getGraph().isEnabled());
                if (!violations.isEmpty())
                    throw new com.nageoffer.ai.ragent.rag.config.validation
                            .RetrievalConfigException(violations);
                if (!List.of("rrf", "off").contains(p.getFusion().getStrategy()))
                    throw new ClientException("融合策略只支持 rrf 或 off");
                if (p.getFusion().getRrfK() < 0) throw new ClientException("RRF k 不能小于零");
                var w = p.getFusion().getChannelWeights();
                if (w == null) throw new ClientException("通道权重不能为空");
                for (double value :
                        List.of(
                                w.getVector(),
                                w.getKeyword(),
                                w.getGraph(),
                                w.getWebSearch(),
                                w.getDefaultWeight()))
                    if (!Double.isFinite(value) || value < 0)
                        throw new ClientException("通道权重必须为非负有限数值");
                if (!Double.isFinite(p.getScope().getMinIntentScore())
                        || !Double.isFinite(p.getScope().getConfidenceThreshold())
                        || !Double.isFinite(p.getScope().getSupplementRatio())
                        || !Double.isFinite(p.getEvidence().getMinRerankScore()))
                    throw new ClientException("检索阈值必须为有限数值");
                var web = p.getChannels().getWebSearch();
                if (web.getCount() < 1 || web.getCount() > 20 || web.getTimeoutSeconds() < 1)
                    throw new ClientException("联网条数须为 1～20，超时须为正数");
                if (web.isEnabled()
                        && (web.getApiKey() == null || web.getApiKey().isBlank())
                        && (System.getenv("YDC_API_KEY") == null
                                || System.getenv("YDC_API_KEY").isBlank()))
                    throw new ClientException("联网检索尚未配置密钥");
                com.nageoffer.ai.ragent.infra.config.AIModelRuntimeContributor.requireUrl(
                        web.getApiUrl());
            }
        };
    }
}

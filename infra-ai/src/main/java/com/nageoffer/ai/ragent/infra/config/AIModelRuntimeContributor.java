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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.runtime.*;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.http.ModelUrlResolver;
import com.nageoffer.ai.ragent.infra.model.ChatTierConfigValidator;
import com.nageoffer.ai.ragent.infra.rerank.RerankClient;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.*;

@Component
@RequiredArgsConstructor
public class AIModelRuntimeContributor implements RuntimeConfigContributor {
    private final AIModelProperties properties;
    private final ObjectMapper json;
    private final RuntimeChatClientRegistry clients;
    private final List<RerankClient> rerankClients;

    public String key() {
        return "ai";
    }

    public Class<?> type() {
        return AIModelProperties.class;
    }

    public Object defaults() {
        return properties;
    }

    public void validate(RuntimeConfigSnapshot snapshot, JsonNode baseline) {
        AIModelProperties p = snapshot.section(AIModelProperties.class);
        JsonNode document = snapshot.document().path(key());
        for (String fixed : List.of("embedding", "selection", "stream")) {
            if (!baseline.path(fixed).equals(document.path(fixed)))
                throw new ClientException("本期不支持修改 ai." + fixed);
        }
        // 共享供应商仅允许轮换密钥，不能改变已有向量空间使用的服务地址与协议
        for (JsonNode embedding : baseline.path("embedding").path("candidates")) {
            String provider = embedding.path("provider").asText();
            JsonNode old = baseline.path("providers").path(provider);
            JsonNode next = document.path("providers").path(provider);
            if (next.isMissingNode()) throw new ClientException("供应商被 Embedding 引用，不能删除");
            for (String field : List.of("url", "endpoints", "thinkingParam"))
                if (!old.path(field).equals(next.path(field)))
                    throw new ClientException("Embedding 共享供应商只允许轮换密钥");
        }
        if (p.getProviders() == null) throw new ClientException("供应商配置不能为空");
        p.getProviders()
                .forEach(
                        (id, provider) -> {
                            if (!id.matches("[a-zA-Z0-9_-]{1,64}"))
                                throw new ClientException("供应商标识仅允许字母、数字、下划线和短横线");
                            if (provider == null) throw new ClientException("供应商配置不能为空");
                            // 保留既有的候选 URL 覆盖语义；最终可调用地址在模型分组校验。
                            if (provider.getUrl() != null && !provider.getUrl().isBlank())
                                requireUrl(provider.getUrl());
                        });
        new ChatTierConfigValidator(p).afterPropertiesSet();
        validateGroup(p, p.getChat(), ModelCapability.CHAT, false);
        validateGroup(p, p.getVlm(), ModelCapability.CHAT, false);
        validateGroup(p, p.getEmbedding(), ModelCapability.EMBEDDING, false);
        validateGroup(p, p.getRerank(), ModelCapability.RERANK, true);
        for (var tier : p.getChat().getTiers().values()) {
            boolean enabled =
                    tier.getCandidates().stream()
                            .anyMatch(
                                    id ->
                                            p.getChat().getCandidates().stream()
                                                    .anyMatch(
                                                            c ->
                                                                    id.equals(c.getId())
                                                                            && !Boolean.FALSE
                                                                                    .equals(
                                                                                            c
                                                                                                    .getEnabled())));
            if (!enabled) throw new ClientException("每个聊天档位至少需要一个启用的模型");
        }
    }

    private void validateGroup(
            AIModelProperties p,
            AIModelProperties.ModelGroup group,
            ModelCapability capability,
            boolean rerank) {
        if (group == null || group.getCandidates() == null)
            throw new ClientException("模型分组或候选列表缺失");
        Set<String> ids = new HashSet<>();
        Set<String> supported = new HashSet<>();
        rerankClients.forEach(c -> supported.add(c.provider()));
        for (var c : group.getCandidates()) {
            if (c == null || c.getId() == null || c.getId().isBlank() || !ids.add(c.getId()))
                throw new ClientException("模型标识不能为空或重复");
            if (c.getProvider() == null || c.getModel() == null || c.getModel().isBlank())
                throw new ClientException("模型和供应商名称不能为空");
            if (rerank && !supported.contains(c.getProvider()))
                throw new ClientException("该供应商尚无 Rerank 适配器");
            if (rerank && "noop".equals(c.getProvider())) continue;
            var provider = p.getProviders().get(c.getProvider());
            if (provider == null) throw new ClientException("模型引用的供应商不存在");
            if (!Boolean.FALSE.equals(c.getEnabled())
                    && !"ollama".equals(c.getProvider())
                    && (provider.getApiKey() == null || provider.getApiKey().isBlank()))
                throw new ClientException("启用的模型尚未配置供应商密钥");
            requireUrl(ModelUrlResolver.resolveUrl(provider, c, capability));
        }
        if (group.getDefaultModel() != null && !group.getDefaultModel().isBlank()) {
            if (group.getCandidates().stream()
                    .noneMatch(
                            c ->
                                    group.getDefaultModel().equals(c.getId())
                                            && !Boolean.FALSE.equals(c.getEnabled())))
                throw new ClientException("默认模型必须存在且启用");
        }
    }

    public void prepare(RuntimeConfigSnapshot snapshot) {
        for (var c : snapshot.section(AIModelProperties.class).getChat().getCandidates())
            clients.resolve(c.getProvider());
    }

    public static void requireUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!Set.of("http", "https").contains(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) throw new IllegalArgumentException();
        } catch (Exception e) {
            throw new ClientException("模型地址必须为不带凭据、查询参数或片段的 HTTP(S) URL");
        }
    }
}

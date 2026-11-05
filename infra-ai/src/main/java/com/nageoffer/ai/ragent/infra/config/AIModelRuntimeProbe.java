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

import com.nageoffer.ai.ragent.framework.convention.*;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.runtime.RuntimeConfigContext;
import com.nageoffer.ai.ragent.framework.runtime.RuntimeConfigProbe;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import com.nageoffer.ai.ragent.infra.rerank.RerankClient;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class AIModelRuntimeProbe implements RuntimeConfigProbe {
    private final RuntimeChatClientRegistry clients;
    private final List<RerankClient> rerankClients;
    private final com.nageoffer.ai.ragent.infra.vlm.RoutingVlmService vlm;

    public boolean supports(String capability) {
        return List.of("chat", "rerank", "vlm").contains(capability);
    }

    public void test(String capability, String modelId) {
        AIModelProperties p = RuntimeConfigContext.section(AIModelProperties.class);
        var group =
                switch (capability) {
                    case "rerank" -> p.getRerank();
                    case "vlm" -> p.getVlm();
                    default -> p.getChat();
                };
        var c =
                group.getCandidates().stream()
                        .filter(x -> x.getId().equals(modelId))
                        .findFirst()
                        .orElseThrow(() -> new ClientException("待测试模型不存在"));
        ModelTarget target =
                new ModelTarget(c.getId(), c, p.getProviders().get(c.getProvider()), 10_000L);
        if ("rerank".equals(capability)) {
            var client =
                    rerankClients.stream()
                            .filter(x -> x.provider().equals(c.getProvider()))
                            .findFirst()
                            .orElseThrow();
            client.rerank(
                    "连接测试",
                    List.of(RetrievedChunk.builder().id("probe").text("连接测试").build()),
                    1,
                    target);
        } else if ("vlm".equals(capability)) {
            byte[] pixel =
                    java.util.Base64.getDecoder()
                            .decode(
                                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");
            String result =
                    vlm.describeImageUsingTarget(pixel, "image/png", "请描述这张测试图片。", 32, target);
            if (result == null || result.isBlank()) throw new ClientException("视觉模型未返回内容");
        } else {
            String result =
                    clients.resolve(c.getProvider())
                            .chat(
                                    ChatRequest.builder()
                                            .messages(List.of(ChatMessage.user("连接测试，请只回复 OK。")))
                                            .temperature(0D)
                                            .thinking(false)
                                            .build(),
                                    target);
            if (result == null || result.isBlank()) throw new ClientException("模型未返回内容");
        }
    }
}

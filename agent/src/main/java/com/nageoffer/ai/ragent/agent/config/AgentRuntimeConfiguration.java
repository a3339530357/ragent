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

import com.fasterxml.jackson.databind.JsonNode;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.runtime.*;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.config.AIModelRuntimeContributor;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.GenerateOptions;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

@Configuration
@ConditionalOnAgentEngine
public class AgentRuntimeConfiguration {
    @Bean
    RuntimeConfigContributor agentRuntimeContributor(
            AgentProperties properties, RuntimeAgentModel model) {
        return new RuntimeConfigContributor() {
            public String key() {
                return "agentChat";
            }

            public Class<?> type() {
                return AgentProperties.Chat.class;
            }

            public Object defaults() {
                return properties.getChat();
            }

            public void validate(RuntimeConfigSnapshot snapshot, JsonNode baseline) {
                var chat = snapshot.section(AgentProperties.Chat.class);
                var ai = snapshot.section(AIModelProperties.class);
                if (chat.getModel() == null
                        || chat.getModel().isBlank()
                        || chat.getProvider() == null)
                    throw new ClientException("主 Agent 模型和供应商不能为空");
                var provider = ai.getProviders().get(chat.getProvider());
                if (provider == null) throw new ClientException("主 Agent 引用的供应商不存在");
                AIModelRuntimeContributor.requireUrl(provider.getUrl());
                if (provider.getEndpoints() == null
                        || provider.getEndpoints().get("chat") == null
                        || provider.getEndpoints().get("chat").isBlank())
                    throw new ClientException("主 Agent 供应商缺少 chat 端点");
                if (!"ollama".equals(chat.getProvider())
                        && (provider.getApiKey() == null || provider.getApiKey().isBlank()))
                    throw new ClientException("主 Agent 供应商未配置密钥");
            }

            public void prepare(RuntimeConfigSnapshot snapshot) {
                model.currentModel();
            }
        };
    }

    @Bean
    RuntimeConfigProbe agentRuntimeProbe(RuntimeAgentModel model, AgentThinkingOptions thinking) {
        return new RuntimeConfigProbe() {
            public boolean supports(String capability) {
                return "agent".equals(capability);
            }

            public void test(String capability, String modelId) {
                var options = GenerateOptions.builder();
                thinking.disable(options);
                var result =
                        model.stream(
                                        List.of(
                                                Msg.builder()
                                                        .role(MsgRole.USER)
                                                        .textContent("连接测试，请只回复 OK。")
                                                        .build()),
                                        null,
                                        options.build())
                                .collectList()
                                .block(Duration.ofSeconds(10));
                if (result == null || result.isEmpty()) throw new ClientException("主 Agent 未返回内容");
            }
        };
    }
}

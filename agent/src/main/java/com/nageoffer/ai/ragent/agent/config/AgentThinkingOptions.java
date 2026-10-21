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

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ThinkingEffort;
import com.nageoffer.ai.ragent.infra.enums.ThinkingParam;
import io.agentscope.core.model.GenerateOptions;
import lombok.RequiredArgsConstructor;

import java.util.Map;

/**
 * 把思考档位翻译成 Agent 模型的请求选项，方言取自 agent.chat.provider 对应的供应商
 * <p>
 * reasoning_effort 走框架原生字段而非附加参数：框架按字段合并，调用级非空即覆盖模型默认选项，
 * 后台调用传 none 才能确定地盖住主对话配置的档位
 */
@RequiredArgsConstructor
public class AgentThinkingOptions {

    private final AgentProperties agentProperties;
    private final AIModelProperties aiModelProperties;

    /**
     * 主对话循环的模型默认选项；未配置档位或供应商无思考方言时返回 null，不改变模型默认行为
     */
    public GenerateOptions defaultOptions() {
        AgentProperties.Chat chat = agentProperties.getChat();
        ThinkingEffort effort = chat == null ? null : chat.getThinkingEffort();
        if (effort == null || resolveParam() == null) {
            return null;
        }
        GenerateOptions.Builder builder = GenerateOptions.builder();
        apply(builder, effort);
        return builder.build();
    }

    /**
     * 后台调用（记忆仲裁、对话摘要）显式关闭思考，不白等一段推理
     */
    public void disable(GenerateOptions.Builder builder) {
        apply(builder, ThinkingEffort.OFF);
    }

    private void apply(GenerateOptions.Builder builder, ThinkingEffort effort) {
        ThinkingParam param = resolveParam();
        if (param == null) {
            return;
        }
        if (param == ThinkingParam.REASONING_EFFORT) {
            builder.reasoningEffort(effort.getWireValue());
            return;
        }
        param.bodyParams(effort).forEach(builder::additionalBodyParam);
    }

    private ThinkingParam resolveParam() {
        String providerId = agentProperties.getChat() == null ? null : agentProperties.getChat().getProvider();
        Map<String, AIModelProperties.ProviderConfig> providers = aiModelProperties.getProviders();
        AIModelProperties.ProviderConfig provider = providers == null || providerId == null ? null : providers.get(providerId);
        return ThinkingParam.resolve(providerId, provider);
    }
}

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

import com.nageoffer.ai.ragent.framework.runtime.*;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.*;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Supplier;

/** 实例归快照所有；SDK 模型不暴露关闭接口，HTTP transport 由 SDK 共享管理。 */
public final class RuntimeAgentModel implements Model {
    private final Supplier<OpenAIChatModel> factory;
    private volatile OpenAIChatModel startup;

    public RuntimeAgentModel(Supplier<OpenAIChatModel> factory) {
        this.factory = factory;
    }

    public OpenAIChatModel currentModel() {
        var snapshot = RuntimeConfigContext.current();
        if (snapshot != null) return snapshot.resource("agent-model", factory);
        if (startup == null)
            synchronized (this) {
                if (startup == null) startup = factory.get();
            }
        return startup;
    }

    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        RuntimeConfigSnapshot snapshot = RuntimeConfigContext.current();
        OpenAIChatModel model = currentModel();
        Flux<ChatResponse> stream = model.stream(messages, tools, options);
        return snapshot == null
                ? stream
                : stream.contextWrite(context -> context.put(RuntimeConfigContext.KEY, snapshot));
    }

    public String getModelName() {
        return currentModel().getModelName();
    }

    public boolean supportsNativeStructuredOutput() {
        return currentModel().supportsNativeStructuredOutput();
    }

    public boolean supportsNativeStructuredOutputWithTools() {
        return currentModel().supportsNativeStructuredOutputWithTools();
    }

    public int getContextWindowSize() {
        return currentModel().getContextWindowSize();
    }
}

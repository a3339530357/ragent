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

import com.nageoffer.ai.ragent.framework.runtime.RuntimeConfigContext;
import com.nageoffer.ai.ragent.infra.chat.ChatClient;
import com.nageoffer.ai.ragent.infra.chat.GenericOpenAIChatClient;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 专用适配器复用，通用适配器按版本构造，全部复用容器的 HTTP 连接池。 */
@Component
@RequiredArgsConstructor
public class RuntimeChatClientRegistry {
    private final List<ChatClient> dedicated;
    private final AutowireCapableBeanFactory factory;
    private final Map<String, ChatClient> startup = new ConcurrentHashMap<>();

    public ChatClient resolve(String provider) {
        for (ChatClient client : dedicated) if (client.provider().equals(provider)) return client;
        var snapshot = RuntimeConfigContext.current();
        if (snapshot == null) return startup.computeIfAbsent(provider, this::create);
        return snapshot.resource("chat-client:" + provider, () -> create(provider));
    }

    private ChatClient create(String provider) {
        GenericOpenAIChatClient client = new GenericOpenAIChatClient(provider);
        factory.autowireBean(client);
        return (ChatClient) factory.initializeBean(client, "runtimeChatClient-" + provider);
    }
}

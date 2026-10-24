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

import com.nageoffer.ai.ragent.infra.debug.ModelRequestDumpWriter;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.model.transport.HttpTransportFactory;
import reactor.core.publisher.Flux;

/**
 * AgentScope HTTP transport 装饰器：在真实 transport 之前把每一次模型请求的
 * 请求头与请求体落盘，用于调试模型鉴权、参数拼装等问题。
 * 通过 {@link HttpTransportFactory#setDefault} 全局生效，Agent 引擎内的所有
 * AgentScope 模型客户端都会经过这里。
 */
public final class AgentScopeDumpTransport implements HttpTransport {

    private final HttpTransport delegate;

    private final ModelRequestDumpWriter writer;

    private AgentScopeDumpTransport(HttpTransport delegate, ModelRequestDumpWriter writer) {
        this.delegate = delegate;
        this.writer = writer;
    }

    /**
     * 在任一 AgentScope 模型客户端构造之前调用；重复调用幂等（已包装则不再套一层）
     */
    public static void install(ModelRequestDumpWriter writer) {
        HttpTransport current = HttpTransportFactory.getDefault();
        if (current instanceof AgentScopeDumpTransport) {
            return;
        }
        HttpTransportFactory.setDefault(new AgentScopeDumpTransport(current, writer));
    }

    @Override
    public HttpResponse execute(HttpRequest request) throws HttpTransportException {
        writer.dump("agentscope", request.getMethod(), request.getUrl(),
                request.getHeaders(), request.getBody());
        return delegate.execute(request);
    }

    @Override
    public Flux<String> stream(HttpRequest request) {
        return delegate.stream(request)
                .doOnSubscribe(subscribed -> writer.dump("agentscope-stream", request.getMethod(),
                        request.getUrl(), request.getHeaders(), request.getBody()));
    }

    @Override
    public void close() {
        delegate.close();
    }
}

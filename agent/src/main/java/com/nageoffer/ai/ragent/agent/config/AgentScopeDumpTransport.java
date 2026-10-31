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

import com.nageoffer.ai.ragent.framework.convention.ModelCallType;
import com.nageoffer.ai.ragent.infra.debug.ModelRequestDump;
import com.nageoffer.ai.ragent.infra.debug.ModelRequestDumpWriter;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.model.transport.HttpTransportFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AgentScope HTTP transport 装饰器：
 * <ul>
 *   <li>摘掉调用方用 {@link ModelCallType#HEADER} 携带的调用类型，保证这个内部标记不发往上游；</li>
 *   <li>dump 开启时（writer 非空），把每一次模型请求的请求头、请求体按类型落盘，拿到返回后补写响应：
 *       一次性返回记完整响应体，流式返回在流结束、出错或被取消时记拼好的结果与原始数据块。</li>
 * </ul>
 * 没带类型头的请求视为 Agent 主循环。
 * 通过 {@link HttpTransportFactory#setDefault} 全局生效，Agent 引擎内的所有 AgentScope 模型客户端都会经过这里。
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
     *
     * @param writer dump 写入器；为 null 时只摘类型头、不落盘
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
        ModelCallType callType = callTypeOf(request);
        HttpRequest outbound = stripCallTypeHeader(request);
        ModelRequestDump dump = begin(callType, "agentscope", outbound);
        HttpResponse response;
        try {
            response = delegate.execute(outbound);
        } catch (RuntimeException e) {
            dump.fail(e);
            throw e;
        }
        dump.completeBody(response.getStatusCode(), response.getBody());
        return response;
    }

    @Override
    public Flux<String> stream(HttpRequest request) {
        ModelCallType callType = callTypeOf(request);
        HttpRequest outbound = stripCallTypeHeader(request);
        if (writer == null) {
            return delegate.stream(outbound);
        }
        // 每次订阅（含 AgentScope 重试）各记一个文件；流结束、出错、被取消都补写一次
        return Flux.defer(() -> {
            ModelRequestDump dump = begin(callType, "agentscope-stream", outbound);
            List<String> chunks = Collections.synchronizedList(new ArrayList<>());
            AtomicReference<Throwable> error = new AtomicReference<>();
            return delegate.stream(outbound)
                    .doOnNext(chunk -> {
                        dump.markFirstChunk();
                        chunks.add(chunk);
                    })
                    .doOnError(error::set)
                    .doFinally(signal -> {
                        synchronized (chunks) {
                            dump.completeStream(null, new ArrayList<>(chunks), error.get(), false);
                        }
                    });
        });
    }

    @Override
    public void close() {
        delegate.close();
    }

    private ModelRequestDump begin(ModelCallType callType, String source, HttpRequest request) {
        if (writer == null) {
            return ModelRequestDump.NOOP;
        }
        return writer.begin(callType, source, request.getMethod(), request.getUrl(),
                request.getHeaders(), request.getBody());
    }

    private static ModelCallType callTypeOf(HttpRequest request) {
        String value = findCallTypeHeader(request.getHeaders());
        if (value != null) {
            try {
                return ModelCallType.valueOf(value);
            } catch (IllegalArgumentException ignored) {
                return ModelCallType.UNTAGGED;
            }
        }
        return ModelCallType.AGENT_CHAT;
    }

    private static String findCallTypeHeader(Map<String, String> headers) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (ModelCallType.HEADER.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static HttpRequest stripCallTypeHeader(HttpRequest request) {
        Map<String, String> headers = request.getHeaders();
        if (findCallTypeHeader(headers) == null) {
            return request;
        }
        Map<String, String> kept = new LinkedHashMap<>();
        headers.forEach((name, value) -> {
            if (!ModelCallType.HEADER.equalsIgnoreCase(name)) {
                kept.put(name, value);
            }
        });
        return HttpRequest.builder()
                .url(request.getUrl())
                .method(request.getMethod())
                .headers(kept)
                .body(request.getBody())
                .build();
    }
}

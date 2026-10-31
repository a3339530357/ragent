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

package com.nageoffer.ai.ragent.infra.debug;

import com.nageoffer.ai.ragent.framework.convention.ModelCallType;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.ForwardingSource;
import okio.Okio;
import okio.Source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OkHttp 应用层拦截器：把发往模型服务的每一次请求（请求头 + 请求体）与返回落盘。
 * 只挂载在 ai.request-dump.enabled=true 时创建的 OkHttpClient 上。
 * 调用类型取自请求的 {@code ModelCallType} tag（模型客户端构造请求时打上）；没有 tag 的视为非模型流量，不 dump。
 * <ul>
 *   <li>一次性返回：peek 一份完整响应体记录，业务照常读取原响应；</li>
 *   <li>流式返回（SSE）：给响应体套一层边转发边复制的 source，流读完、被关闭或出错时补写，不拖慢首包。</li>
 * </ul>
 */
public final class ModelRequestDumpInterceptor implements Interceptor {

    /**
     * 一次性响应最多记录的字节数，超出部分截断（peek 不消耗原响应）
     */
    private static final long MAX_SYNC_CAPTURE_BYTES = 16L * 1024 * 1024;

    /**
     * 流式响应最多复制的字节数，超出后停止复制、照常转发，并在 dump 里标记截断
     */
    private static final long MAX_STREAM_CAPTURE_BYTES = 32L * 1024 * 1024;

    private static final String EVENT_STREAM = "text/event-stream";

    private final ModelRequestDumpWriter writer;

    public ModelRequestDumpInterceptor(ModelRequestDumpWriter writer) {
        this.writer = writer;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        ModelCallType callType = request.tag(ModelCallType.class);
        if (callType == null) {
            // 同一个 OkHttpClient 还承载 MinerU 解析、文档下载等非模型流量，没有类型 tag 的一律不 dump
            return chain.proceed(request);
        }
        ModelRequestDump dump = writer.begin(callType, "okhttp", request.method(), request.url().toString(),
                flattenHeaders(request.headers()), readBodyQuietly(request));

        Response response;
        try {
            response = chain.proceed(request);
        } catch (IOException | RuntimeException e) {
            dump.fail(e);
            throw e;
        }

        try {
            if (isEventStream(request, response)) {
                return wrapStreamingBody(response, dump);
            }
            dump.completeBody(response.code(), response.peekBody(MAX_SYNC_CAPTURE_BYTES).string());
        } catch (Exception ignored) {
            // 记录响应失败不打断请求，原响应照常返回
        }
        return response;
    }

    /**
     * 请求声明要 SSE 或响应声明是 SSE 都按流式处理——绝不能对流式响应 peek，否则会等整段流结束才返回首包
     */
    private boolean isEventStream(Request request, Response response) {
        String accept = request.header("Accept");
        if (accept != null && accept.contains(EVENT_STREAM)) {
            return true;
        }
        MediaType contentType = response.body().contentType();
        return contentType != null && contentType.toString().contains(EVENT_STREAM);
    }

    private Response wrapStreamingBody(Response response, ModelRequestDump dump) {
        ResponseBody original = response.body();
        CapturingSource capturing = new CapturingSource(original.source(), dump, response.code());
        ResponseBody wrapped = ResponseBody.create(Okio.buffer(capturing), original.contentType(),
                original.contentLength());
        return response.newBuilder().body(wrapped).build();
    }

    private Map<String, String> flattenHeaders(Headers headers) {
        Map<String, String> flat = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            flat.merge(headers.name(i), headers.value(i), (prev, next) -> prev + ", " + next);
        }
        return flat;
    }

    private String readBodyQuietly(Request request) {
        try {
            RequestBody body = request.body();
            if (body == null) {
                return null;
            }
            Buffer buffer = new Buffer();
            body.writeTo(buffer);
            return buffer.readUtf8();
        } catch (Exception ignored) {
            // 读 body 失败（如 one-shot 流）不打断请求，只是 dump 里没有请求体
            return null;
        }
    }

    /**
     * 边转发边复制的 source：读到流尾、被关闭（调用方读到 [DONE] 或取消后关流）、读出错三种情况都会补写一次 dump
     */
    private static final class CapturingSource extends ForwardingSource {

        private final ModelRequestDump dump;

        private final int status;

        private final Buffer captured = new Buffer();

        private boolean truncated;

        CapturingSource(Source delegate, ModelRequestDump dump, int status) {
            super(delegate);
            this.dump = dump;
            this.status = status;
        }

        @Override
        public long read(Buffer sink, long byteCount) throws IOException {
            long read;
            try {
                read = super.read(sink, byteCount);
            } catch (IOException | RuntimeException e) {
                finish(e);
                throw e;
            }
            if (read == -1) {
                finish(null);
                return read;
            }
            try {
                dump.markFirstChunk();
                if (!truncated) {
                    if (captured.size() + read > MAX_STREAM_CAPTURE_BYTES) {
                        truncated = true;
                    } else {
                        sink.copyTo(captured, sink.size() - read, read);
                    }
                }
            } catch (Exception ignored) {
                // 复制失败只影响 dump
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                finish(null);
            }
        }

        private void finish(Throwable error) {
            try {
                dump.completeStream(status, splitLines(captured.clone().readString(StandardCharsets.UTF_8)),
                        error, truncated);
            } catch (Exception ignored) {
                // 旁路 dump 失败不打断请求
            }
        }

        private static List<String> splitLines(String text) {
            List<String> lines = new ArrayList<>();
            for (String line : text.split("\n")) {
                String trimmed = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
                if (!trimmed.isBlank()) {
                    lines.add(trimmed);
                }
            }
            return lines;
        }
    }
}

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

import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OkHttp 应用层拦截器：把发往模型服务的每一次请求（请求头 + 请求体）落盘。
 * 只挂载在 ai.request-dump.enabled=true 时创建的模型专用 OkHttpClient 上，不碰其它 HTTP 流量。
 */
public final class ModelRequestDumpInterceptor implements Interceptor {

    private final ModelRequestDumpWriter writer;

    public ModelRequestDumpInterceptor(ModelRequestDumpWriter writer) {
        this.writer = writer;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        try {
            writer.dump("okhttp", request.method(), request.url().toString(),
                    flattenHeaders(request.headers()), readBody(request));
        } catch (Exception ignored) {
            // 读 body 失败（如 one-shot 流）或其它异常都不打断请求
        }
        return chain.proceed(request);
    }

    private Map<String, String> flattenHeaders(Headers headers) {
        Map<String, String> flat = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            flat.merge(headers.name(i), headers.value(i), (prev, next) -> prev + ", " + next);
        }
        return flat;
    }

    private String readBody(Request request) throws IOException {
        RequestBody body = request.body();
        if (body == null) {
            return null;
        }
        Buffer buffer = new Buffer();
        body.writeTo(buffer);
        return buffer.readUtf8();
    }
}

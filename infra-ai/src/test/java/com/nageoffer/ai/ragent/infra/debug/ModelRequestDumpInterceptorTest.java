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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.framework.convention.ModelCallType;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.ForwardingSource;
import okio.Okio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelRequestDumpInterceptorTest {
    @TempDir
    Path dir;

    @Test
    void synchronousResponseRemainsReadableAndIsRecordedByType() throws Exception {
        Request request = request(true);
        String body = "{\"choices\":[],\"usage\":{\"total_tokens\":3}}";
        Response response = intercept(request, ResponseBody.create(body, MediaType.get("application/json")));
        assertEquals(body, response.body().string());
        JsonObject record = record();
        assertEquals("main-chat", record.get("callType").getAsString());
        assertEquals("completed", record.getAsJsonObject("response").get("state").getAsString());
        assertEquals(JsonParser.parseString(body), record.getAsJsonObject("response").get("body"));
    }

    @Test
    void streamIsNotReadAheadAndRecordsAssembledContentOnClose() throws Exception {
        String events = "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"世界\"},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n";
        AtomicInteger reads = new AtomicInteger();
        var source = new ForwardingSource(new Buffer().writeUtf8(events)) {
            @Override
            public long read(Buffer sink, long byteCount) throws IOException {
                reads.incrementAndGet();
                return super.read(sink, byteCount);
            }
        };
        Response response = intercept(request(true), ResponseBody.create(Okio.buffer(source),
                MediaType.get("text/event-stream"), -1));
        assertEquals(0, reads.get(), "interceptor must not wait for the stream before returning");
        assertEquals("pending", record().getAsJsonObject("response").get("state").getAsString());
        assertEquals(events, response.body().string());
        JsonObject result = record().getAsJsonObject("response");
        assertEquals("completed", result.get("state").getAsString());
        assertEquals("你好世界", result.getAsJsonObject("assembled").get("content").getAsString());
        assertFalse(result.get("rawTruncated").getAsBoolean());
    }

    @Test
    void untaggedTrafficDoesNotCreateDump() throws Exception {
        Response response = intercept(request(false), ResponseBody.create("download", MediaType.get("text/plain")));
        assertEquals("download", response.body().string());
        try (var files = Files.list(dir)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void failedDumpDoesNotBreakResponse() throws Exception {
        Files.writeString(dir.resolve("main-chat"), "not a directory");
        Response response = intercept(request(true), ResponseBody.create("ok", MediaType.get("text/plain")));
        assertEquals("ok", response.body().string());
    }

    private Request request(boolean tagged) {
        Request.Builder builder = new Request.Builder().url("http://localhost/model");
        if (tagged) {
            builder.tag(ModelCallType.class, ModelCallType.MAIN_CHAT);
        }
        return builder.build();
    }

    private Response intercept(Request request, ResponseBody body) throws IOException {
        Interceptor.Chain chain = mock(Interceptor.Chain.class);
        when(chain.request()).thenReturn(request);
        when(chain.proceed(request)).thenReturn(new Response.Builder().request(request)
                .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build());
        return new ModelRequestDumpInterceptor(new ModelRequestDumpWriter(dir.toString())).intercept(chain);
    }

    private JsonObject record() throws IOException {
        try (var files = Files.list(dir.resolve("main-chat"))) {
            Path file = files.findFirst().orElseThrow();
            return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        }
    }
}

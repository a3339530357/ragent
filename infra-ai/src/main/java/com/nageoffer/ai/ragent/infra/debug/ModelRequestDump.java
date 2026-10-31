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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次模型调用的 dump 句柄：请求部分已由 {@link ModelRequestDumpWriter#begin} 落盘，
 * 拿到返回（或失败、被取消）时调用一次 complete* / fail 补写 response，重复调用只认第一次。
 * 所有方法都不抛异常——dump 是旁路调试能力，绝不允许影响模型调用主链路。
 */
public class ModelRequestDump {

    /**
     * dump 关闭或请求部分落盘失败时的空操作句柄
     */
    public static final ModelRequestDump NOOP = new ModelRequestDump(null, null, null);

    private final ModelRequestDumpWriter writer;

    private final Path file;

    private final JsonObject record;

    private final long startNanos = System.nanoTime();

    private final AtomicBoolean firstChunkMarked = new AtomicBoolean();

    private final AtomicBoolean finished = new AtomicBoolean();

    private volatile Long firstChunkMs;

    ModelRequestDump(ModelRequestDumpWriter writer, Path file, JsonObject record) {
        this.writer = writer;
        this.file = file;
        this.record = record;
    }

    /**
     * 流式响应收到第一块数据时调用，记录首包耗时
     */
    public void markFirstChunk() {
        if (writer != null && firstChunkMarked.compareAndSet(false, true)) {
            firstChunkMs = elapsedMs();
        }
    }

    /**
     * 一次性返回：记录状态码与完整响应体
     *
     * @param status HTTP 状态码
     * @param body   响应体原文
     */
    public void completeBody(int status, String body) {
        if (!tryFinish()) {
            return;
        }
        try {
            JsonObject response = baseResponse(isSuccess(status) ? "completed" : "http-error", status);
            response.add("body", ModelRequestDumpWriter.parseJsonOrString(body));
            write(response);
        } catch (Exception ignored) {
            // 旁路 dump 失败不打断请求
        }
    }

    /**
     * 流式返回：记录拼好的完整结果与原始数据块
     *
     * @param status   HTTP 状态码；拿不到（如 Agent 引擎流式）传 null
     * @param lines    按到达顺序的原始数据行（SSE 行或已去掉 data: 前缀的数据块均可）
     * @param error    流中途的异常；没有传 null
     * @param truncated 原始数据是否因超过上限而被截断
     */
    public void completeStream(Integer status, List<String> lines, Throwable error, boolean truncated) {
        if (!tryFinish()) {
            return;
        }
        try {
            if (status != null && !isSuccess(status)) {
                // 错误响应不是 SSE，按普通响应体记录
                JsonObject response = baseResponse("http-error", status);
                response.add("body", ModelRequestDumpWriter.parseJsonOrString(String.join("\n", lines)));
                write(response);
                return;
            }
            ModelStreamAssembler.Result assembled = ModelStreamAssembler.assemble(lines);
            String state = error != null ? "error" : assembled.done() ? "completed" : "closed-early";
            JsonObject response = baseResponse(state, status);
            response.addProperty("firstChunkMs", firstChunkMs);
            if (error != null) {
                response.addProperty("error", error.toString());
            }
            response.add("assembled", assembled.json());
            JsonArray raw = new JsonArray();
            lines.forEach(raw::add);
            response.addProperty("rawTruncated", truncated);
            response.add("rawEvents", raw);
            write(response);
        } catch (Exception ignored) {
            // 旁路 dump 失败不打断请求
        }
    }

    /**
     * 没拿到响应就失败（网络错误、超时等）
     */
    public void fail(Throwable error) {
        if (!tryFinish()) {
            return;
        }
        try {
            JsonObject response = baseResponse("error", null);
            response.addProperty("error", String.valueOf(error));
            write(response);
        } catch (Exception ignored) {
            // 旁路 dump 失败不打断请求
        }
    }

    private boolean tryFinish() {
        return writer != null && finished.compareAndSet(false, true);
    }

    private JsonObject baseResponse(String state, Integer status) {
        JsonObject response = new JsonObject();
        response.addProperty("state", state);
        response.addProperty("status", status);
        response.addProperty("elapsedMs", elapsedMs());
        return response;
    }

    private void write(JsonObject response) {
        synchronized (record) {
            record.add("response", response);
            writer.rewrite(file, record);
        }
    }

    private long elapsedMs() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static boolean isSuccess(int status) {
        return status >= 200 && status < 300;
    }
}

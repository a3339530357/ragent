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

package com.nageoffer.ai.ragent.settings;

import cn.dev33.satoken.stp.StpUtil;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mzt.logapi.starter.annotation.LogRecord;
import com.nageoffer.ai.ragent.audit.constant.BizChangeBizType;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/rag/settings/runtime")
@RequiredArgsConstructor
public class RuntimeConfigController {
    private final RuntimeConfigService service;
    private final RuntimeConfigSync sync;

    public record Draft(long draftRevision, long baseVersion, ObjectNode config) {}

    public record Revision(long draftRevision, long baseVersion) {}

    public record Rollback(long draftRevision, long baseVersion, long version) {}

    public record Probe(long draftRevision, long baseVersion, String capability, String modelId) {}

    @GetMapping
    public Result<Map<String, Object>> get() {
        admin();
        Map<String, Object> result = service.view();
        result.put("instances", sync.instances());
        return Results.success(result);
    }

    @PutMapping("/draft")
    @LogRecord(
            success = "保存运行时配置草稿",
            type = BizChangeBizType.RUNTIME_CONFIG,
            subType = "DRAFT",
            bizNo = "runtime")
    public Result<Long> draft(@RequestBody Draft request) {
        admin();
        return Results.success(
                service.saveDraft(
                        request.draftRevision(), request.baseVersion(), request.config()));
    }

    @PostMapping("/validate")
    public Result<List<String>> validate(@RequestBody Revision request) {
        admin();
        return Results.success(service.validate(request.draftRevision(), request.baseVersion()));
    }

    @PostMapping("/publish")
    @LogRecord(
            success = "发布运行时配置版本：{{#_ret.data}}",
            type = BizChangeBizType.RUNTIME_CONFIG,
            subType = "PUBLISH",
            bizNo = "runtime")
    public Result<Long> publish(@RequestBody Revision request) {
        admin();
        long version = service.publish(request.draftRevision(), request.baseVersion());
        sync.announce(version);
        return Results.success(version);
    }

    @PostMapping("/rollback")
    @LogRecord(
            success = "回滚运行时配置，生成版本：{{#_ret.data}}",
            type = BizChangeBizType.RUNTIME_CONFIG,
            subType = "ROLLBACK",
            bizNo = "runtime")
    public Result<Long> rollback(@RequestBody Rollback request) {
        admin();
        long version =
                service.rollback(request.draftRevision(), request.baseVersion(), request.version());
        sync.announce(version);
        return Results.success(version);
    }

    @GetMapping("/versions")
    public Result<List<RuntimeConfigVersion>> versions() {
        admin();
        return Results.success(service.history());
    }

    @GetMapping("/versions/{version}")
    public Result<ObjectNode> version(@PathVariable long version) {
        admin();
        return Results.success(service.historical(version));
    }

    @PostMapping("/test")
    @LogRecord(
            success = "测试运行时模型连接",
            type = BizChangeBizType.RUNTIME_CONFIG,
            subType = "TEST",
            bizNo = "runtime")
    public Result<Void> test(@RequestBody Probe request) {
        admin();
        service.test(
                request.draftRevision(),
                request.baseVersion(),
                request.capability(),
                request.modelId());
        return Results.success();
    }

    private void admin() {
        StpUtil.checkRole("admin");
    }
}

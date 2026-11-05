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

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.runtime.*;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.io.IOException;
import java.util.*;

/** 配置中心只处理版本；业务校验由各模块贡献，发布不发送外部模型请求。 */
@Service
@Slf4j
public class RuntimeConfigService implements SmartInitializingSingleton {
    private final RuntimeConfigMapper mapper;
    private final RuntimeConfigCipher cipher;
    private final TransactionOperations transactions;
    private final ObjectMapper json;
    private final List<RuntimeConfigContributor> contributors;
    private final List<RuntimeConfigProbe> probes;
    private ObjectNode baseline;
    private volatile String applyError;
    private volatile long targetVersion;

    public RuntimeConfigService(
            RuntimeConfigMapper mapper,
            RuntimeConfigCipher cipher,
            TransactionOperations transactions,
            ObjectMapper json,
            List<RuntimeConfigContributor> contributors,
            List<RuntimeConfigProbe> probes) {
        this.mapper = mapper;
        this.cipher = cipher;
        this.transactions = transactions;
        this.json = json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.contributors = contributors;
        this.probes = probes;
    }

    @Override
    public void afterSingletonsInstantiated() {
        baseline = json.createObjectNode();
        try (var ignored =
                RuntimeConfigContext.use(
                        new RuntimeConfigSnapshot(0, json.createObjectNode(), Map.of()))) {
            for (RuntimeConfigContributor c : contributors)
                baseline.set(c.key(), serializeDefaults(c));
        }
        RuntimeConfigState state = requiredState();
        targetVersion = state.getActiveVersion();
        ObjectNode document =
                targetVersion == 0 ? baseline : decode(requiredVersion(targetVersion));
        RuntimeConfigSnapshot snapshot = prepare(targetVersion, document);
        RuntimeConfigContext.activate(snapshot);
    }

    private JsonNode serializeDefaults(RuntimeConfigContributor contributor) {
        try {
            // 配置 Bean 可能被 Spring 增强；只导出声明类型，避免序列化代理内部的 BeanFactory。
            // 经 JSON 字节读取后再比较，确保默认配置与数据库载入配置的数字节点类型一致。
            byte[] bytes =
                    json.writerFor(contributor.type()).writeValueAsBytes(contributor.defaults());
            return json.readTree(bytes);
        } catch (IOException e) {
            throw new IllegalStateException("读取默认配置分组失败：" + contributor.key(), e);
        }
    }

    public boolean writable() {
        return cipher.configured();
    }

    public long appliedVersion() {
        var active = RuntimeConfigContext.active();
        return active == null ? 0 : active.version();
    }

    public String applyError() {
        return applyError;
    }

    public long targetVersion() {
        return targetVersion;
    }

    public Map<String, Object> view() {
        RuntimeConfigState state = requiredState();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("activeVersion", state.getActiveVersion());
        view.put("appliedVersion", appliedVersion());
        view.put("draftRevision", state.getDraftRevision());
        view.put("draftBaseVersion", state.getDraftBaseVersion());
        view.put("writable", writable());
        view.put("applyError", applyError);
        ObjectNode effective = documentAt(state.getActiveVersion());
        ObjectNode draft = state.getDraftPayload() == null ? null : decode(state.getDraftPayload());
        view.put("effective", RuntimeConfigSecrets.mask(effective));
        view.put("draft", draft == null ? null : RuntimeConfigSecrets.mask(draft));
        List<String> changes = new ArrayList<>();
        if (draft != null) collectChanges(effective, draft, "", changes);
        view.put("changes", changes);
        return view;
    }

    public long saveDraft(long revision, long base, ObjectNode input) {
        requireWritable();
        return Objects.requireNonNull(
                transactions.execute(
                        status -> {
                            RuntimeConfigState state = mapper.lockState();
                            checkVersions(state, revision, base);
                            ObjectNode previous =
                                    state.getDraftPayload() == null
                                            ? documentAt(base)
                                            : decode(state.getDraftPayload());
                            if (input == null) throw new ClientException("配置内容不能为空");
                            ObjectNode resolved = RuntimeConfigSecrets.resolve(input, previous);
                            // 草稿允许语义不完整，但只允许已注册模块，秘密必须先解析再加密
                            checkSections(resolved);
                            parseSections(resolved);
                            mapper.saveDraft(
                                    revision + 1,
                                    base,
                                    cipher.encrypt(resolved.toString()),
                                    actor());
                            return revision + 1;
                        }));
    }

    public List<String> validate(long revision, long base) {
        RuntimeConfigState state = requiredState();
        checkVersions(state, revision, base);
        if (state.getDraftPayload() == null) throw new ClientException("尚未保存草稿");
        try {
            prepare(-1, decode(state.getDraftPayload()));
            return List.of();
        } catch (RuntimeException e) {
            // 校验器只返回字段/规则描述，禁止把包含密钥的反序列化异常直接回传
            return List.of(safeValidationMessage(e));
        }
    }

    public long publish(long revision, long base) {
        requireWritable();
        RuntimeConfigState state = requiredState();
        checkVersions(state, revision, base);
        if (state.getDraftPayload() == null) throw new ClientException("尚未保存草稿");
        return publishDocument(revision, base, decode(state.getDraftPayload()), null);
    }

    public long rollback(long revision, long base, long version) {
        requireWritable();
        return publishDocument(revision, base, documentAt(version), version);
    }

    private long publishDocument(long revision, long base, ObjectNode document, Long rollbackFrom) {
        RuntimeConfigSnapshot prepared = prepare(-1, document);
        RuntimeConfigVersion version = new RuntimeConfigVersion();
        version.setSchemaVersion(1);
        version.setPayload(cipher.encrypt(document.toString()));
        version.setCreatedBy(actor());
        version.setRollbackFrom(rollbackFrom);
        transactions.executeWithoutResult(
                status -> {
                    checkVersions(mapper.lockState(), revision, base);
                    mapper.insert(version);
                    mapper.publish(version.getId(), actor());
                });
        applyPrepared(prepared.withVersion(version.getId()));
        log.info(
                "运行时配置已发布 version={} actor={} rollbackFrom={}",
                version.getId(),
                actor(),
                rollbackFrom);
        return version.getId();
    }

    public synchronized void refresh() {
        try {
            long desired = requiredState().getActiveVersion();
            targetVersion = desired;
            if (desired <= appliedVersion()) {
                if (desired == appliedVersion()) applyError = null;
                return;
            }
            applyPrepared(prepare(desired, documentAt(desired)));
        } catch (Exception e) {
            applyError = "配置加载或准备失败，请检查数据库、主密钥及部署兼容性";
            log.warn(
                    "运行时配置刷新失败，保留旧版本 version={} type={}",
                    appliedVersion(),
                    e.getClass().getSimpleName());
        }
    }

    private synchronized void applyPrepared(RuntimeConfigSnapshot snapshot) {
        targetVersion = Math.max(targetVersion, snapshot.version());
        if (snapshot.version() > appliedVersion()) RuntimeConfigContext.activate(snapshot);
        applyError = null;
    }

    public List<RuntimeConfigVersion> history() {
        return mapper.history();
    }

    public ObjectNode historical(long version) {
        return RuntimeConfigSecrets.mask(documentAt(version));
    }

    public void test(long revision, long base, String capability, String modelId) {
        if (capability == null || capability.isBlank()) throw new ClientException("请选择测试能力");
        RuntimeConfigState state = requiredState();
        checkVersions(state, revision, base);
        ObjectNode document =
                state.getDraftPayload() == null
                        ? documentAt(base)
                        : decode(state.getDraftPayload());
        RuntimeConfigSnapshot snapshot = prepare(-1, document);
        RuntimeConfigProbe probe =
                probes.stream()
                        .filter(p -> p.supports(capability))
                        .findFirst()
                        .orElseThrow(() -> new ClientException("不支持的模型测试能力"));
        try (var ignored = RuntimeConfigContext.use(snapshot)) {
            probe.test(capability, modelId);
        } catch (Exception e) {
            throw new ClientException("连接测试失败，请核对模型、地址、密钥及网络连接");
        }
    }

    public RuntimeConfigSnapshot prepare(long version, ObjectNode document) {
        checkSections(document);
        Map<Class<?>, Object> typed = parseSections(document);
        RuntimeConfigSnapshot snapshot = new RuntimeConfigSnapshot(version, document, typed);
        try (var ignored = RuntimeConfigContext.use(snapshot)) {
            for (RuntimeConfigContributor c : contributors)
                c.validate(snapshot, baseline.get(c.key()));
            try {
                for (RuntimeConfigContributor c : contributors) c.prepare(snapshot);
            } catch (RuntimeException e) {
                throw new IllegalStateException("配置客户端准备失败，请检查模型、地址和协议设置");
            }
        }
        return snapshot;
    }

    private Map<Class<?>, Object> parseSections(ObjectNode document) {
        Map<Class<?>, Object> typed = new HashMap<>();
        try {
            for (RuntimeConfigContributor c : contributors)
                typed.put(c.type(), json.treeToValue(document.get(c.key()), c.type()));
        } catch (Exception e) {
            throw new ClientException("配置字段或类型不合法，请检查表单");
        }
        return typed;
    }

    private void collectChanges(
            JsonNode before, JsonNode after, String path, List<String> changes) {
        if (Objects.equals(before, after)) return;
        if (before != null && before.isObject() && after != null && after.isObject()) {
            Set<String> names = new TreeSet<>();
            before.fieldNames().forEachRemaining(names::add);
            after.fieldNames().forEachRemaining(names::add);
            for (String name : names)
                collectChanges(
                        before.get(name),
                        after.get(name),
                        path.isEmpty() ? name : path + "." + name,
                        changes);
        } else changes.add(path);
    }

    private void checkSections(ObjectNode document) {
        if (document == null) throw new ClientException("配置内容不能为空");
        Set<String> expected = new HashSet<>();
        contributors.forEach(c -> expected.add(c.key()));
        Set<String> actual = new HashSet<>();
        document.fieldNames().forEachRemaining(actual::add);
        if (!expected.equals(actual)) throw new ClientException("配置分组缺失或包含未开放的分组");
        expected.forEach(
                k -> {
                    if (!document.path(k).isObject()) throw new ClientException("配置分组必须为对象");
                });
    }

    private ObjectNode documentAt(long version) {
        return version == 0 ? baseline.deepCopy() : decode(requiredVersion(version));
    }

    private ObjectNode decode(RuntimeConfigVersion version) {
        if (!Integer.valueOf(1).equals(version.getSchemaVersion()))
            throw new IllegalStateException("不支持的配置结构版本");
        return decode(version.getPayload());
    }

    private ObjectNode decode(String payload) {
        try {
            return (ObjectNode) json.readTree(cipher.decrypt(payload));
        } catch (Exception e) {
            throw new IllegalStateException("配置读取失败，请核对加密主密钥及存储结构");
        }
    }

    private RuntimeConfigVersion requiredVersion(long id) {
        RuntimeConfigVersion row = mapper.version(id);
        if (row == null) throw new ClientException("配置版本不存在");
        return row;
    }

    private RuntimeConfigState requiredState() {
        RuntimeConfigState state = mapper.state();
        if (state == null) throw new IllegalStateException("请先执行运行时配置中心升级脚本");
        return state;
    }

    private void requireWritable() {
        if (!writable()) throw new ClientException("未配置加密主密钥，配置中心当前只读");
    }

    private void checkVersions(RuntimeConfigState state, long revision, long base) {
        if (state == null
                || state.getDraftRevision() != revision
                || state.getActiveVersion() != base) throw new ClientException("配置版本冲突，请刷新后重新编辑");
        if (state.getDraftPayload() != null && state.getDraftBaseVersion() != base)
            throw new ClientException("草稿基准版本已失效，请刷新");
    }

    private String actor() {
        return Objects.toString(UserContext.getUsername(), "system");
    }

    private String safeValidationMessage(Exception e) {
        return e.getMessage() == null ? "配置校验失败" : e.getMessage();
    }
}

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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.runtime.*;

import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

class RuntimeConfigServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private RuntimeConfigMapper mapper;
    private RuntimeConfigService service;
    private RuntimeConfigState state;
    private RuntimeConfigCipher cipher;
    private final Map<Long, RuntimeConfigVersion> versions = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final AtomicBoolean preparationFails = new AtomicBoolean();
    private RuntimeConfigContributor contributor;
    private TransactionOperations transactions;
    private final Object transactionLock = new Object();

    public record Settings(String model, String apiKey) {}

    @BeforeEach
    void setup() {
        RuntimeConfigContext.activate(null);
        RuntimeConfigContext.setLocal(null);
        mapper = mock(RuntimeConfigMapper.class);
        state = new RuntimeConfigState();
        state.setActiveVersion(0L);
        state.setDraftRevision(0L);
        state.setDraftBaseVersion(0L);
        cipher = new RuntimeConfigCipher(Base64.getEncoder().encodeToString(new byte[32]));
        contributor =
                new RuntimeConfigContributor() {
                    public String key() {
                        return "test";
                    }

                    public Class<?> type() {
                        return Settings.class;
                    }

                    public Object defaults() {
                        return new Settings("old", "secret-original");
                    }

                    public void validate(
                            RuntimeConfigSnapshot candidate,
                            com.fasterxml.jackson.databind.JsonNode baseline) {
                        if (candidate.section(Settings.class).model().equals("invalid"))
                            throw new ClientException("模型配置不合法");
                    }

                    public void prepare(RuntimeConfigSnapshot candidate) {
                        if (preparationFails.get()) throw new IllegalStateException("客户端准备失败");
                        candidate.resource("prepared", Object::new);
                    }
                };
        transactions = mock(TransactionOperations.class);
        when(transactions.execute(any()))
                .thenAnswer(
                        inv -> {
                            synchronized (transactionLock) {
                                return ((TransactionCallback<?>) inv.getArgument(0))
                                        .doInTransaction(null);
                            }
                        });
        doAnswer(
                        inv -> {
                            synchronized (transactionLock) {
                                ((java.util.function.Consumer<?>) inv.getArgument(0)).accept(null);
                                return null;
                            }
                        })
                .when(transactions)
                .executeWithoutResult(any());
        doAnswer(inv -> copyState()).when(mapper).state();
        when(mapper.lockState()).thenAnswer(inv -> copyState());
        when(mapper.version(anyLong())).thenAnswer(inv -> versions.get(inv.getArgument(0)));
        when(mapper.history()).thenAnswer(inv -> new ArrayList<>(versions.values()));
        doAnswer(
                        inv -> {
                            state.setDraftRevision(inv.getArgument(0));
                            state.setDraftBaseVersion(inv.getArgument(1));
                            state.setDraftPayload(inv.getArgument(2));
                            return null;
                        })
                .when(mapper)
                .saveDraft(anyLong(), anyLong(), anyString(), anyString());
        doAnswer(
                        inv -> {
                            RuntimeConfigVersion v = inv.getArgument(0);
                            v.setId(ids.incrementAndGet());
                            versions.put(v.getId(), v);
                            return null;
                        })
                .when(mapper)
                .insert(any());
        doAnswer(
                        inv -> {
                            state.setActiveVersion(inv.getArgument(0));
                            state.setDraftRevision(state.getDraftRevision() + 1);
                            state.setDraftBaseVersion(state.getActiveVersion());
                            state.setDraftPayload(null);
                            return null;
                        })
                .when(mapper)
                .publish(anyLong(), anyString());
        service = newService(cipher);
        service.afterSingletonsInstantiated();
    }

    @AfterEach
    void clear() {
        RuntimeConfigContext.activate(null);
        RuntimeConfigContext.setLocal(null);
    }

    private RuntimeConfigState copyState() {
        RuntimeConfigState copy = new RuntimeConfigState();
        copy.setActiveVersion(state.getActiveVersion());
        copy.setDraftRevision(state.getDraftRevision());
        copy.setDraftBaseVersion(state.getDraftBaseVersion());
        copy.setDraftPayload(state.getDraftPayload());
        return copy;
    }

    private RuntimeConfigService newService(RuntimeConfigCipher encryption) {
        return new RuntimeConfigService(
                mapper, encryption, transactions, json, List.of(contributor), List.of());
    }

    private ObjectNode edit(String model) {
        ObjectNode input = (ObjectNode) service.view().get("effective");
        ((ObjectNode) input.get("test")).put("model", model);
        return input;
    }

    @Test
    void draftIsEncryptedAndDoesNotAffectLiveConfiguration() {
        assertEquals(1, service.saveDraft(0, 0, edit("new")));
        assertEquals("old", RuntimeConfigContext.section(Settings.class).model());
        assertFalse(state.getDraftPayload().contains("secret-original"));
        assertFalse(service.view().toString().contains("secret-original"));
        assertEquals(
                "secret-original",
                jsonRead(cipher.decrypt(state.getDraftPayload()))
                        .path("test")
                        .path("apiKey")
                        .asText());
    }

    @Test
    void publishPinsExistingTaskAndNewTaskUsesNewVersion() {
        RuntimeConfigSnapshot old = RuntimeConfigContext.current();
        service.saveDraft(0, 0, edit("new"));
        assertTrue(service.validate(1, 0).isEmpty());
        try (var ignored = RuntimeConfigContext.use(old)) {
            long id = service.publish(1, 0);
            assertEquals(1, id);
            assertEquals("old", RuntimeConfigContext.section(Settings.class).model());
        }
        assertEquals("new", RuntimeConfigContext.section(Settings.class).model());
        assertNotNull(
                RuntimeConfigContext.current()
                        .resource(
                                "prepared",
                                () -> {
                                    throw new AssertionError("不得重新准备客户端");
                                }));
    }

    @Test
    void invalidAndFailedPreparationDoNotWritePublishedVersion() {
        service.saveDraft(0, 0, edit("invalid"));
        assertFalse(service.validate(1, 0).isEmpty());
        assertThrows(ClientException.class, () -> service.publish(1, 0));
        assertEquals(0, state.getActiveVersion());
        service.saveDraft(1, 0, edit("valid"));
        preparationFails.set(true);
        assertThrows(IllegalStateException.class, () -> service.publish(2, 0));
        assertEquals(0, state.getActiveVersion());
        verify(mapper, never()).insert(any());
    }

    @Test
    void staleEditorsAndPublishersCannotOverwriteChanges() {
        service.saveDraft(0, 0, edit("one"));
        assertThrows(ClientException.class, () -> service.saveDraft(0, 0, edit("two")));
        assertThrows(ClientException.class, () -> service.publish(0, 0));
        service.publish(1, 0);
        assertThrows(ClientException.class, () -> service.saveDraft(2, 0, edit("two")));
    }

    @Test
    void concurrentEditorsHaveOnlyOneWinner() throws Exception {
        ObjectNode one = edit("one"), two = edit("two");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (ObjectNode value : List.of(one, two))
                attempts.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    try {
                                        service.saveDraft(0, 0, value);
                                        return true;
                                    } catch (ClientException e) {
                                        return false;
                                    }
                                }));
            start.countDown();
            int successes = 0;
            for (var attempt : attempts) if (attempt.get(5, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
            assertEquals(1, state.getDraftRevision());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void restartLoadsPublishedVersionAndRollbackCreatesNewHistory() {
        service.saveDraft(0, 0, edit("one"));
        long first = service.publish(1, 0);
        RuntimeConfigContext.activate(null);
        RuntimeConfigService restarted = newService(cipher);
        restarted.afterSingletonsInstantiated();
        assertEquals("one", RuntimeConfigContext.section(Settings.class).model());
        long second = restarted.rollback(2, first, 0);
        assertTrue(second > first);
        assertEquals("old", RuntimeConfigContext.section(Settings.class).model());
        assertEquals("one", service.historical(first).path("test").path("model").asText());
        assertEquals(2, versions.size());
    }

    @Test
    void missingKeyAllowsBaselineReadsButBlocksWritesAndPublishedStartup() {
        RuntimeConfigService readonly = newService(new RuntimeConfigCipher(""));
        readonly.afterSingletonsInstantiated();
        assertFalse(readonly.writable());
        assertThrows(ClientException.class, () -> readonly.saveDraft(0, 0, edit("new")));
        service.saveDraft(0, 0, edit("new"));
        service.publish(1, 0);
        RuntimeConfigContext.activate(null);
        assertThrows(IllegalStateException.class, readonly::afterSingletonsInstantiated);
    }

    @Test
    void pollingRecoversMissedNotificationAndFailureKeepsLastVersion() {
        RuntimeConfigSnapshot old = RuntimeConfigContext.current();
        service.saveDraft(0, 0, edit("new"));
        service.publish(1, 0);
        RuntimeConfigContext.activate(old);
        preparationFails.set(true);
        service.refresh();
        assertSame(old, RuntimeConfigContext.current());
        assertNotNull(service.applyError());
        preparationFails.set(false);
        service.refresh();
        assertEquals(1, service.appliedVersion());
        assertNull(service.applyError());
    }

    @Test
    void unknownFieldsAndGroupsCannotBePublished() {
        ObjectNode unknown = edit("new");
        unknown.putObject("deployment").put("database", "changed");
        assertThrows(ClientException.class, () -> service.saveDraft(0, 0, unknown));
        ObjectNode nested = edit("new");
        ((ObjectNode) nested.get("test")).put("unknownField", "x");
        assertThrows(ClientException.class, () -> service.saveDraft(0, 0, nested));
    }

    @Test
    void startupDatabaseFailureDoesNotSilentlyFallback() {
        when(mapper.state()).thenThrow(new IllegalStateException("unavailable"));
        assertThrows(
                IllegalStateException.class,
                () -> newService(cipher).afterSingletonsInstantiated());
    }

    @Test
    void savedSecretRotationIsVisibleAsFieldChangeWithoutExposingValues() {
        ObjectNode input = edit("old");
        ObjectNode secret = (ObjectNode) input.path("test").path("apiKey");
        secret.put("action", "replace");
        secret.put("value", "rotated-private");
        service.saveDraft(0, 0, input);
        assertTrue(((List<?>) service.view().get("changes")).contains("test.apiKey"));
        assertFalse(service.view().toString().contains("rotated-private"));
    }

    @Test
    void temporaryDatabaseFailureClearsWhenSameVersionBecomesReadableAgain() {
        when(mapper.state()).thenThrow(new IllegalStateException("temporary"));
        service.refresh();
        assertNotNull(service.applyError());
        doAnswer(inv -> copyState()).when(mapper).state();
        service.refresh();
        assertNull(service.applyError());
    }

    private com.fasterxml.jackson.databind.JsonNode jsonRead(String value) {
        try {
            return json.readTree(value);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}

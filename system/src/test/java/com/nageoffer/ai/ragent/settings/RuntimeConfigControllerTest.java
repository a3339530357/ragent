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
import static org.mockito.Mockito.*;

import cn.dev33.satoken.stp.StpUtil;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.List;

class RuntimeConfigControllerTest {
    @Test
    void everyManagementEndpointRejectsNonAdminBeforeReadingOrWriting() {
        var service = mock(RuntimeConfigService.class);
        var sync = mock(RuntimeConfigSync.class);
        var controller = new RuntimeConfigController(service, sync);
        try (var auth = mockStatic(StpUtil.class)) {
            auth.when(() -> StpUtil.checkRole("admin"))
                    .thenThrow(new IllegalStateException("权限不足"));
            var rev = new RuntimeConfigController.Revision(0, 0);
            List<Runnable> operations =
                    List.of(
                            controller::get,
                            controller::versions,
                            () -> controller.version(1),
                            () ->
                                    controller.draft(
                                            new RuntimeConfigController.Draft(
                                                    0, 0, new ObjectMapper().createObjectNode())),
                            () -> controller.validate(rev),
                            () -> controller.publish(rev),
                            () ->
                                    controller.rollback(
                                            new RuntimeConfigController.Rollback(0, 0, 1)),
                            () ->
                                    controller.test(
                                            new RuntimeConfigController.Probe(0, 0, "chat", "m")));
            operations.forEach(op -> assertThrows(IllegalStateException.class, op::run));
            verifyNoInteractions(service, sync);
        }
    }

    @Test
    void publicationNotificationIsOnlySentAfterSuccessfulCommit() {
        var service = mock(RuntimeConfigService.class);
        var sync = mock(RuntimeConfigSync.class);
        var controller = new RuntimeConfigController(service, sync);
        var revision = new RuntimeConfigController.Revision(2, 1);
        try (var auth = mockStatic(StpUtil.class)) {
            when(service.publish(2, 1)).thenThrow(new IllegalStateException("冲突"));
            assertThrows(IllegalStateException.class, () -> controller.publish(revision));
            verifyNoInteractions(sync);
            doReturn(3L).when(service).publish(2, 1);
            assertEquals(3, controller.publish(revision).getData());
            var order = inOrder(service, sync);
            order.verify(service, times(2)).publish(2, 1);
            order.verify(sync).announce(3);
        }
    }
}

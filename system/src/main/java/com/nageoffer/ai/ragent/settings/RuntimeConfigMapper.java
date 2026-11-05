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

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface RuntimeConfigMapper {
    @Select(
            "SELECT active_version, draft_revision, draft_base_version, draft_payload, updated_by"
                    + " FROM t_runtime_config_state WHERE id = 1")
    RuntimeConfigState state();

    @Select(
            "SELECT active_version, draft_revision, draft_base_version, draft_payload, updated_by"
                    + " FROM t_runtime_config_state WHERE id = 1 FOR UPDATE")
    RuntimeConfigState lockState();

    @Update(
            "UPDATE t_runtime_config_state SET draft_revision=#{revision},"
                    + " draft_base_version=#{base}, draft_payload=#{payload}, updated_by=#{actor},"
                    + " update_time=CURRENT_TIMESTAMP WHERE id=1")
    void saveDraft(
            @Param("revision") long revision,
            @Param("base") long base,
            @Param("payload") String payload,
            @Param("actor") String actor);

    @Update(
            "UPDATE t_runtime_config_state SET active_version=#{version}, draft_payload=NULL,"
                    + " draft_revision=draft_revision+1, draft_base_version=#{version},"
                    + " updated_by=#{actor}, update_time=CURRENT_TIMESTAMP WHERE id=1")
    void publish(@Param("version") long version, @Param("actor") String actor);

    @Insert(
            "INSERT INTO t_runtime_config_version(schema_version,payload,created_by,rollback_from)"
                    + " VALUES(#{schemaVersion},#{payload},#{createdBy},#{rollbackFrom})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(RuntimeConfigVersion version);

    @Select(
            "SELECT id,schema_version,payload,created_by,create_time,rollback_from FROM"
                    + " t_runtime_config_version WHERE id=#{id}")
    RuntimeConfigVersion version(long id);

    @Select(
            "SELECT id,schema_version,created_by,create_time,rollback_from FROM"
                    + " t_runtime_config_version ORDER BY id DESC LIMIT 50")
    List<RuntimeConfigVersion> history();
}

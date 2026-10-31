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

package com.nageoffer.ai.ragent.framework.convention;

/**
 * 模型调用类型：标明一次模型请求是哪个业务环节发起的。
 * 目前只给请求 dump 用——按 {@link #dir} 分子目录落盘，排查时直接进对应目录找。
 */
public enum ModelCallType {

    MAIN_CHAT("main-chat", "主对话（检索后回答）"),
    SYSTEM_CHAT("system-chat", "主对话（不走检索直接回答）"),
    QUERY_REWRITE("query-rewrite", "查询改写与拆分"),
    INTENT_CLASSIFY("intent-classify", "意图识别"),
    AMBIGUITY_CHECK("ambiguity-check", "歧义检查"),
    MEMORY_SUMMARY("memory-summary", "会话记忆摘要"),
    TITLE_GENERATE("title-generate", "会话标题生成"),
    RECOMMEND_QUESTIONS("recommend-questions", "推荐问题生成"),
    AGENT_KB_ANSWER("agent-kb-answer", "Agent 知识库工具的答案合成"),
    AGENT_CHAT("agent-chat", "Agent 主循环"),
    AGENT_MEMORY_JUDGE("agent-memory-judge", "Agent 长期记忆裁决"),
    AGENT_SUMMARY("agent-summary", "Agent 上下文摘要"),
    INGESTION_ENRICH("ingestion-enrich", "入库：分片增强（EnricherNode）"),
    INGESTION_ENHANCE("ingestion-enhance", "入库：文本增强（EnhancerNode）"),
    IMAGE_PARSE("image-parse", "图片解析（视觉模型）"),
    EMBEDDING("embedding", "向量化"),
    RERANK("rerank", "重排序"),
    UNTAGGED("untagged", "未标注类型");

    /**
     * Agent 引擎（AgentScope）请求通过这个请求头携带类型，dump 时读取并在发往上游前摘掉
     */
    public static final String HEADER = "X-Ragent-Call-Type";

    private final String dir;

    private final String description;

    ModelCallType(String dir, String description) {
        this.dir = dir;
        this.description = description;
    }

    public String dir() {
        return dir;
    }

    public String description() {
        return description;
    }
}

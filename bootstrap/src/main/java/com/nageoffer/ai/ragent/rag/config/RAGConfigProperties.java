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

package com.nageoffer.ai.ragent.rag.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 系统功能配置
 *
 * <p>
 * 用于管理 RAG 系统的各项功能开关，例如查询重写等
 * </p>
 *
 * <pre>
 * 示例配置：
 *
 * rag:
 *   query-rewrite:
 *     enabled: true
 *   answer-citation:
 *     enabled: true
 * </pre>
 */
@Data
@Configuration
public class RAGConfigProperties {

    /**
     * 查询重写功能开关
     * <p>
     * 控制是否启用查询重写功能，查询重写可以将用户的查询语句优化为更适合检索的形式
     * 默认值：{@code true}
     */
    @Value("${rag.query-rewrite.enabled:true}")
    private Boolean queryRewriteEnabled;

    /**
     * 查询改写语义相似度校验开关
     * <p>
     * 开启后会在 LLM 改写完成后计算"改写前 Query"与"改写后 Query"的余弦相似度，
     * 低于阈值时丢弃改写结果，回退到术语归一化后的原始问题。
     */
    @Value("${rag.query-rewrite.semantic-validation.enabled:true}")
    private Boolean queryRewriteSemanticValidationEnabled;

    /**
     * 查询改写语义相似度硬阈值
     */
    @Value("${rag.query-rewrite.semantic-validation.min-similarity:0.8}")
    private Double queryRewriteSemanticSimilarityThreshold;

    /**
     * 短 Query 判定长度，按去除标点和空白后的字符数计算
     */
    @Value("${rag.query-rewrite.short-query.max-length:8}")
    private Integer queryRewriteShortQueryMaxLength;

    /**
     * 回答引用来源开关
     * <p>
     * 控制是否在知识库证据中注入来源编号，并在流式完成事件和历史消息中返回 citations 数组
     * 默认值：{@code true}
     */
    @Value("${rag.answer-citation.enabled:true}")
    private Boolean answerCitationEnabled;

    /**
     * Rerank 重排序功能开关
     * <p>
     * 控制是否启用 Rerank 后置处理器对召回结果进行重排序
     * 默认值：{@code true}
     */
    @Value("${rag.rerank.enabled:true}")
    private Boolean rerankEnabled;
}

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

package com.nageoffer.ai.ragent.infra.config;

import com.nageoffer.ai.ragent.infra.chat.GenericOpenAIChatClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * OpenAI 兼容供应商动态注册器
 *
 * <p>
 * {@code ai.providers} 下凡是还没有专用客户端的供应商，启动时自动挂一个
 * {@link GenericOpenAIChatClient}（provider 名 = 配置 key），此后接入任何
 * OpenAI 兼容端点都是纯配置，无需再写客户端类。已有专用客户端的五家
 * （存在家差异定制，如 Ollama 免鉴权、硅基 dimensions 参数裁剪）不覆盖。
 * </p>
 *
 * <p>
 * 注册时机选 {@link BeanDefinitionRegistryPostProcessor}：它先于所有普通 Bean
 * 实例化执行，保证 RoutingChatService 收集 {@code List<ChatClient>} 时动态
 * 客户端已经就位；实例仍由 Spring 管理，基类的 @Autowired 字段注入照常生效。
 * </p>
 */
@Slf4j
@Component
public class OpenAICompatibleClientRegistrar implements BeanDefinitionRegistryPostProcessor, EnvironmentAware {

    /**
     * 已有专用客户端的供应商：注册器不越权，保留各自的行为定制
     */
    private static final Set<String> DEDICATED_PROVIDERS = Set.of("ollama", "bailian", "siliconflow", "aihubmix", "deepseek");

    private Environment env;

    @Override
    public void setEnvironment(Environment env) {
        this.env = env;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        Map<String, Object> providers = Binder.get(env)
                .bind("ai.providers", Bindable.mapOf(String.class, Object.class))
                .orElse(Map.of());

        providers.keySet().stream()
                .filter(name -> !DEDICATED_PROVIDERS.contains(name))
                .forEach(name -> {
                    registry.registerBeanDefinition(
                            "genericChatClient-" + name,
                            BeanDefinitionBuilder.genericBeanDefinition(GenericOpenAIChatClient.class)
                                    .addConstructorArgValue(name)
                                    .getBeanDefinition());
                    log.info("已注册通用 OpenAI 兼容聊天客户端: provider={}", name);
                });
    }

    @Override
    public void postProcessBeanFactory(org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory) {
        // 本注册器只做新增 Bean 定义，不修改既有定义
    }
}
